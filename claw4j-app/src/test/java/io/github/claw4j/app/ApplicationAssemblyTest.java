package io.github.claw4j.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个完整功能：整机装配。
 *
 * <p>这里只证明一件事——**接上了**：工具注册进了 ChatClient、鉴权 Advisor 挂进了链、
 * 迁移在真库上跑过、只读执行接在工具后面。这四件在模块级永远不会红：那边每个模块手里都有替身，
 * Advisor 是测试自己挂的，工具是测试自己塞的。
 *
 * <p>所以这里**不验业务规则**（越权判定、守卫的正则那些在各自功能那份里已经有更深的证明），
 * 也**不验接口契约**——状态码、请求头、SSE 分帧是验收的活（README 里那几条 curl 跑一遍即可），
 * 写进回归只会每天多一个与业务无关的红灯。
 *
 * <p>模型是真的（ADR-0011 没有 mock 兜底：没 key 就起不来），因此只留两条、共两次调用：
 * 一条证明"问得到数"，一条证明"越权问不到数"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationAssemblyTest {

    /** 种子里华东区上周 = 54000 + 74400。这个数只有真查了库才拿得到。 */
    private static final String EAST_LAST_WEEK = "128400";

    private static final String QUESTION = "上周华东区成交额多少";

    /** 容器要在 Spring 建上下文之前起好：DynamicPropertySource 要读它的端口。 */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("工具真的注册进了链：admin 问一句，回答里带着库里那个数")
    void toolIsRegisteredAndReachesTheDatabase() throws Exception {
        String answer = ask("assembly-admin", "admin", QUESTION);

        assertThat(mentions(answer, EAST_LAST_WEEK))
                .as("回答里应当出现库里的 128400，实际：%s", answer)
                .isTrue();
    }

    @Test
    @DisplayName("鉴权 Advisor 真的挂进了链：guest 问同一句，拿不到那个数")
    void authzAdvisorIsWiredIntoTheChain() throws Exception {
        String answer = ask("assembly-guest", "guest", QUESTION);

        assertThat(mentions(answer, EAST_LAST_WEEK))
                .as("guest 只配了 region 表，越权放行会让这个数出现在回答里：%s", answer)
                .isFalse();
    }

    private String ask(String user, String role, String question) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", user)
                .header("X-Claw4j-Role", role)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + question + "\"}"))
                .build();
        return new String(http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body(),
                StandardCharsets.UTF_8);
    }

    /** 金额可能被写成 128,400 或 ￥128400：去掉分隔符再比，比的就是那个数。 */
    private static boolean mentions(String answer, String amount) {
        String normalized = answer.replace(",", "").replace("，", "").replace(" ", "");
        return normalized.contains(amount);
    }
}
