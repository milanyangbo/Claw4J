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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个完整功能：整机一条对话。HTTP 进 → 认人 → 鉴权 → 模型 → 工具 → SQL → 真库 → 回答出。
 *
 * <p>这是唯一一份"装配"被证明过的地方：Flyway 迁移、配置绑定、自动配置顺序、
 * Advisor 链、工具清单、只读执行，任何一环掉了，模块级测试都不会红——它们各自手里都有替身。
 *
 * <p>模型是真 DeepSeek（ADR-0011），库是真 Postgres。所以断言钉的是"回答里有没有库里那个数"，
 * 而不是"回答非空"——后者无论哪一环断了都照样绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationEndToEndTest {

    /** 种子里华东区上周 = 54000 + 74400。 */
    private static final String EAST_LAST_WEEK = "128400";

    private static final String QUESTION = "上周华东区成交额多少";

    /** 容器在 Spring 建上下文之前起好：DynamicPropertySource 要读它的端口。 */
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
    @DisplayName("一句话进、一整段出：回答里带着库里那个数")
    void answerCarriesTheNumberFromTheDatabase() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("e2e-admin", "admin", "/api/chat", QUESTION),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        // 这个数只有真查了库才拿得到，所以它同时证明模型、工具、SQL、迁移、通道都在
        assertThat(mentions(text(response), EAST_LAST_WEEK))
                .as("回答里应当出现库里的 128400，实际：%s", text(response))
                .isTrue();
    }

    @Test
    @DisplayName("流式：多块推送，以 done 收尾")
    void streamEndsWithDone() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("e2e-admin", "admin", "/api/chat/stream", QUESTION),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);

        List<String[]> events = parseSse(text(response));
        List<String> chunks = events.stream()
                .filter(event -> "chunk".equals(event[0]))
                .map(event -> event[1])
                .toList();

        assertThat(chunks.size())
                .as("只有一块说明流式被降级成了一次性返回")
                .isGreaterThan(1);
        assertThat(mentions(String.join("", chunks), EAST_LAST_WEEK)).isTrue();
        assertThat(events.getLast()[0]).as("最后一个事件必须是 done").isEqualTo("done");
    }

    @Test
    @DisplayName("没有用户标识的请求进不来")
    void requestWithoutIdentityIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + QUESTION + "\"}"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("表级越权在真链路里也拦得住：guest 问成交额拿不到那个数")
    void tableLevelDenialHoldsInTheWholeChain() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("e2e-guest", "guest", "/api/chat", QUESTION),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mentions(text(response), EAST_LAST_WEEK))
                .as("guest 只配了 region 表，越权放行会让这个数出现在回答里：%s", text(response))
                .isFalse();
    }

    private HttpRequest ask(String user, String role, String path, String question) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", user)
                .header("X-Claw4j-Role", role)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + question + "\"}"))
                .build();
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** 金额可能被写成 128,400 或 ￥128400：去掉分隔符再比，比的就是那个数。 */
    private static boolean mentions(String answer, String amount) {
        String normalized = answer.replace(",", "").replace("，", "").replace(" ", "");
        return normalized.contains(amount);
    }

    /** SSE 解析成 [事件名, 数据] 列表；只认 event: 与 data: 两行。最后一块可能没有结尾空行。 */
    private static List<String[]> parseSse(String body) {
        List<String[]> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()));
            } else if (line.isEmpty() && name != null) {
                events.add(new String[]{name, data.toString()});
                name = null;
            }
        }
        if (name != null) {
            events.add(new String[]{name, data.toString()});
        }
        return events;
    }
}
