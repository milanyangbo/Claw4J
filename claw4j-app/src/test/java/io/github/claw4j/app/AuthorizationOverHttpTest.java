package io.github.claw4j.app;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.capability.builtin.sales.QueryResult;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03 号票的 Demo，钉在 HTTP 这一层：**换一个角色头问同一句话**，
 * 一个有答案、一个是明确的"没权限"，中间是同一条链路。
 *
 * <p>为什么值得在 app 里再测一遍：agent 模块里测的是 Advisor 与工具，
 * 通道里测的是"头 → Principal"，两边都绿也不代表**接在一起**是通的。
 * 这条测试从请求头进、从响应体出，中间不跳任何一环。
 *
 * <p>模型是真的（ADR-0011）：假模型时代这里用一个"两轮假模型"替掉回声模型，
 * 因为回声永远不会要求调工具，鉴权根本不会被触发——那是"看起来绿其实没测到"的典型。
 * 换真模型之后这个问题从根上没了：模型自己决定要不要调工具，调了才轮到鉴权拦。
 * 代价是断言不能钉"它一定调了"，所以判据落在**工具执行次数**上（替身执行器计数），
 * 而不是落在回答的措辞上。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationOverHttpTest {

    private static final String QUESTION = "上周华东区成交额多少";

    /** 种子里华东区上周就是 128400 = 54000 + 74400。 */
    private static final String EAST = "128400";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 03 号票要的是"**用调用计数证明工具一次都没被调用**"，
     * 而不是靠"回答里没出现数字"来猜——猜不出来一种情况：
     * 工具其实跑了，只是查出来是空的，那回答里同样没有数字。
     * 所以这里比的是请求前后的计数器差值。
     */
    @Test
    void 换一个角色头问同一句话拿到的是明确的没权限() throws Exception {
        int before = CountingSqlExecutor.calls();
        String body = ask("guest", "/api/chat");

        // 换真模型之后（ADR-0011）不能再断言"回答里出现那句原话"：
        // 拒绝文本回到模型之后会被它自己组织一遍，原话不保真。
        // 判据因此落在两件确定的事上——工具一次都没被执行、回答里没有那个数。
        Amounts.assertOmits(body, EAST);
        assertThat(CountingSqlExecutor.calls())
                .as("越权的那一次，工具一次都没被执行：拦截发生在执行之前")
                .isEqualTo(before);
    }

    /**
     * 有权限的那条同样要计数，否则"拒绝"与"放行"会变成同一件事：
     * 两条都只断言文本的话，工具跑没跑根本看不出来。
     */
    @Test
    void 有权限的角色问同一句话拿到答案且工具真的被调了一次() throws Exception {
        int before = CountingSqlExecutor.calls();
        String body = ask("read-only", "/api/chat");

        // 真模型（ADR-0011）不再复述写死的那一句，它自己组织的回答里带着替身执行器
        // 返回的数。128,400 与 128400 都算——千分位是模型自己加的，不是我们要抓的东西。
        Amounts.assertMentions(body, EAST);
        assertThat(CountingSqlExecutor.calls()).isGreaterThan(before);
    }

    @Test
    void 流式路径同样按角色拦() throws Exception {
        int before = CountingSqlExecutor.calls();
        String streamed = ask("guest", "/api/chat/stream");

        Amounts.assertOmits(streamed, EAST);
        assertThat(CountingSqlExecutor.calls()).isEqualTo(before);
    }

    /**
     * 每条用例换一个用户：三条用例共用一个 Spring 容器，而会话是按人切分的。
     * 假模型时代共用同一个 user 看不出问题（它不看历史），真模型会把上一轮的
     * "没权限"带进下一轮，于是"有权限"那条也答成没权限——测试之间互相污染。
     */
    private String ask(String role, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", "e2e-" + role + "-" + System.nanoTime())
                .header("X-Claw4j-Role", role)
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + QUESTION + "\"}"))
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        String body = new String(response.body(), StandardCharsets.UTF_8);
        return body.startsWith("event:") ? sseData(body) : body;
    }

    private static String sseData(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).strip())
                .reduce("", String::concat);
    }

    @TestConfiguration
    static class StubModelConfiguration {

        /**
         * 数据源用替身，但<b>要计数</b>：03 号票要的是"用调用计数证明工具一次都没被调用"，
         * 只比对响应文本等于在猜——工具跑了但查出来是空的，文本里同样没有数字。
         *
         * <p>不用真库还有一个实际理由：默认配置里的 DataSource 指向本机 5432，
         * 没有 Docker 时工具会走"连接失败"那条路，最终回答照样是 FINAL_ANSWER——
         * 测试会绿，但绿的原因是"工具失败了"，而不是"工具被放行了"。
         * 这种假绿比红更难发现。
         */
        @Bean
        public ReadOnlySqlExecutor stubSqlExecutor() {
            return new CountingSqlExecutor();
        }
    }

    /**
     * 计数器是 static 的：这是个 {@code @Bean} 单例，而三条测试共用一个 Spring 容器，
     * 实例级的计数会在测试之间串。断言一律用"请求前后的差值"，不看绝对值。
     */
    static final class CountingSqlExecutor implements ReadOnlySqlExecutor {

        private static final AtomicInteger CALLS = new AtomicInteger();

        static int calls() {
            return CALLS.get();
        }

        @Override
        public QueryResult execute(String sql) {
            CALLS.incrementAndGet();
            return new QueryResult(List.of("amount"), List.of(List.of("128400.00")), false);
        }
    }
}
