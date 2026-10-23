package io.github.claw4j.app;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把"流式路径静默跳过 Advisor"这个坑也钉在 HTTP 这一层。
 *
 * <p>agent 模块里的 {@code AdvisorBothPathsTest} 证明的是框架行为；这里证明的是
 * **我们这条链路没踩坑**：一个挂进容器的 Advisor，在 SSE 上真的生效了。
 * 将来鉴权 Advisor 接进来的那天，这条测试就是"流式也受鉴权保护"的证据。
 *
 * <p>为什么值得单独端到端测一遍：跳过是静默的——不报错、不告警、客户端照常拿到回答，
 * 只有"内容里应当有而实际没有某个标记"这种断言才抓得到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdvisorNotSkippedOverHttpTest {

    private static final String STAMP = "[ADVISOR]";

    private static final String QUESTION = "华东区上周成交额";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 挂载的Advisor在流式路径上生效() throws Exception {
        String streamed = send("/api/chat/stream");

        assertTrue(streamed.contains(STAMP),
                "Advisor 的标记应当出现在 SSE 内容里；没出现说明它被流式路径跳过了。实际：" + streamed);
    }

    @Test
    void 同一个Advisor在同步路径上同样生效() throws Exception {
        assertTrue(send("/api/chat").contains(STAMP), "同步路径也应当经过 Advisor");
    }

    private String send(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", "e2e-tester")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + QUESTION + "\"}"))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String body = new String(response.body(), StandardCharsets.UTF_8);
        return body.startsWith("event:") ? sseData(body) : body;
    }

    /**
     * 把 SSE 的 data 行拼回一整段。必须拼：分块边界落在标记中间是常有的事
     * （比如 {@code [mock] [} 与 {@code ADVISOR]} 被切成两块），
     * 直接对原始报文做 contains 会因为换行而误判成"Advisor 没生效"。
     */
    private static String sseData(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).strip())
                .reduce("", String::concat);
    }

    /**
     * 容器里挂一个会留痕的 Advisor。它按 ADR-0007 的要求双实现（{@code BaseAdvisor}
     * 同时覆盖 call 与 stream），所以它自己就是正确写法的范例。
     */
    @TestConfiguration
    static class StampingAdvisorConfiguration {

        @Bean
        public Advisor stampingAdvisor() {
            return new BaseAdvisor() {

                @Override
                public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
                    // 注意：augmentUserMessage(String) 是"替换"而不是"追加"——
                    // 传字符串会把用户原话整段冲掉，要加内容必须走 Function 版本。
                    return request.mutate()
                            .prompt(request.prompt()
                                    .augmentUserMessage(user -> user.mutate().text(STAMP + user.getText()).build()))
                            .build();
                }

                /**
                 * 留痕放在 **after**（出站）而不是 before（入站）：
                 * 换真模型之前（ADR-0011）标记加在用户消息上、靠回声模型把它念回客户端——
                 * 真模型不会复述用户消息，那条判据就废了。标记打在出站响应上则不依赖模型行为，
                 * 而"两条路径都出现过这个标记"仍然证明两条路径都走过了 Advisor。
                 */
                @Override
                public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
                    ChatResponse chatResponse = response.chatResponse();
                    if (chatResponse == null || chatResponse.getResults() == null
                            || chatResponse.getResults().isEmpty()) {
                        return response;
                    }
                    String text = chatResponse.getResults().get(0).getOutput().getText();
                    if (text == null || text.isBlank()) {
                        return response;
                    }
                    ChatResponse stamped = ChatResponse.builder()
                            .from(chatResponse)
                            .generations(List.of(new Generation(
                                    new AssistantMessage(STAMP + text))))
                            .build();
                    return response.mutate().chatResponse(stamped).build();
                }

                /**
                 * 必须显式给名字：{@code BaseAdvisor} 的默认实现取 {@code getClass().getSimpleName()}，
                 * 匿名类的 simpleName 是空串，会让观测上下文在运行时抛
                 * "advisorName cannot be null or empty"——而且是流式路径先炸。
                 */
                @Override
                public String getName() {
                    return "stampingAdvisor";
                }

                @Override
                public int getOrder() {
                    return Ordered.HIGHEST_PRECEDENCE;
                }
            };
        }
    }
}
