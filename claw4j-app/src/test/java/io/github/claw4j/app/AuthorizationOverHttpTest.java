package io.github.claw4j.app;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03 号票的 Demo，钉在 HTTP 这一层：**换一个角色头问同一句话**，
 * 一个有答案、一个是明确的"没权限"，中间是同一条链路。
 *
 * <p>为什么值得在 app 里再测一遍：agent 模块里测的是 Advisor 与工具，
 * 通道里测的是"头 → Principal"，两边都绿也不代表**接在一起**是通的。
 * 这条测试从请求头进、从响应体出，中间不跳任何一环。
 *
 * <p>模型用一个两轮的假模型替掉 mock（mock 是回声，永远不会要求调工具，
 * 于是鉴权根本不会被触发——那正是"看起来绿其实没测到"的典型）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"claw4j.model.mock=true"})
class AuthorizationOverHttpTest {

    private static final String QUESTION = "上周华东区成交额多少";

    private static final String FINAL_ANSWER = "已按你的口径查到了。";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 有权限的角色问同一句话拿到答案() throws Exception {
        String body = ask("read-only", "/api/chat");

        assertThat(body).contains(FINAL_ANSWER);
    }

    @Test
    void 换一个角色头问同一句话拿到的是明确的没权限() throws Exception {
        String body = ask("guest", "/api/chat");

        assertThat(body)
                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                .doesNotContain(FINAL_ANSWER);
    }

    @Test
    void 流式路径同样按角色拦() throws Exception {
        String streamed = ask("guest", "/api/chat/stream");

        assertThat(streamed)
                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                .doesNotContain(FINAL_ANSWER);
    }

    private String ask(String role, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", "e2e-tester")
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

    /**
     * 两轮假模型：第一轮要调工具，第二轮作答。
     *
     * <p>按"提示词里有没有工具结果"来分轮，而不是按调用计数器分：
     * 计数器是共享状态，几条测试共用一个 Spring 容器会互相串。
     */
    static final class TwoRoundModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean toolAlreadyRan = prompt.getInstructions()
                    .stream()
                    .anyMatch(ToolResponseMessage.class::isInstance);
            return toolAlreadyRan ? answer() : toolCall();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        /**
         * 必须是 {@code ToolCallingChatOptions}：不是的话 {@code ToolCallingAdvisor}
         * 会整段跳过工具循环，鉴权就永远没机会被调用，测试会假绿。
         */
        @Override
        public ChatOptions getOptions() {
            return DefaultToolCallingChatOptions.builder().model("two-round-stub").build();
        }

        private static ChatResponse toolCall() {
            var call = new AssistantMessage.ToolCall("call-1", "function", "querySales",
                    "{\"region\":\"华东\",\"period\":\"上周\"}");
            return ChatResponse.builder()
                    .generations(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())))
                    .build();
        }

        private static ChatResponse answer() {
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(FINAL_ANSWER))))
                    .build();
        }
    }

    @TestConfiguration
    static class StubModelConfiguration {

        @Bean
        public ChatModel twoRoundModel() {
            return new TwoRoundModel();
        }
    }
}
