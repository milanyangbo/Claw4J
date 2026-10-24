package io.github.claw4j.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整条链路的一条主流程：HTTP 进 → 认人 → Agent → 模型 → 工具 → SQL → 回答出。
 *
 * <p>刻意用真实端口 + 真实 HTTP 客户端，不走 MockMvc——SSE 是"连接先返回、内容后到"，
 * MockMvc 测不出这件事。这里也是唯一一处能证明"完整装配下每一环都在"的地方：
 * Flyway 迁移、application.yml、自动配置顺序，模块级测试一条都不会红。
 *
 * <p>模型是真 DeepSeek（ADR-0011），库也是真的。所以断言可以钉到"回答里有没有库里那个数"，
 * 比"回答非空"强得多——后者无论哪一环断了都照样绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpEndToEndTest {

    private static final String QUESTION = "上周华东区成交额多少";

    /** 种子里华东区上周就是 128400 = 54000 + 74400。 */
    private static final String EAST = "128400";

    /** 种子里华南区上周。追问那一轮要拿到的就是它。 */
    private static final String SOUTH = "26000";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("一句话进、一整段出：回答里带着库里那个数")
    void 一句话进一整段出() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("admin", "/api/chat", QUESTION),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        // 真模型是自己去查库才拿得到这个数的，所以这一条同时证明模型、工具、SQL、通道都在
        Amounts.assertMentions(text(response), EAST);
    }

    @Test
    @DisplayName("流式逐块返回，并以 done 收尾")
    void 流式逐块与done收尾() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("admin", "/api/chat/stream", QUESTION),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        List<SseEvent> events = parseSse(text(response));
        List<String> chunks = events.stream()
                .filter(event -> "chunk".equals(event.name()))
                .map(SseEvent::data)
                .toList();

        assertTrue(chunks.size() > 1,
                "必须收到多块；只有一块说明流式被降级成了一次性响应，实际块数：" + chunks.size());
        Amounts.assertMentions(String.join("", chunks), EAST);
        assertEquals("done", events.get(events.size() - 1).name(),
                "最后一个事件必须是 done，否则客户端不知道什么时候停");
    }

    @Test
    @DisplayName("没有用户标识的请求进不来")
    void 无身份拒绝() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + QUESTION + "\"}"))
                .build();

        assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    @DisplayName("追问继承上一轮的口径：只问「那华南呢」也拿得到数")
    void 追问继承口径() throws Exception {
        String user = "e2e-followup-" + System.nanoTime();

        Amounts.assertMentions(send(user, "admin", "/api/chat", QUESTION), EAST);
        Amounts.assertMentions(send(user, "admin", "/api/chat", "那华南呢"), SOUTH);
    }

    /**
     * 流式路径会<b>静默</b>跳过只实现 {@code CallAdvisor} 的 Advisor：不报错、客户端照常拿到回答。
     * 所以判据只能是"它被调用了几次"，不能是"回答里有什么"——真模型不会复述我们加的标记，
     * 而流式的正文是逐块发出去的，在 {@code after} 里改聚合结果改不到已经发出的块
     * （试过：同步路径标记可见，流式路径永远不可见，那会让这条断言恒红且指向错误的方向）。
     */
    @Test
    @DisplayName("挂进容器的 Advisor 在同步与流式两条路径上都生效")
    void Advisor不被流式路径跳过() throws Exception {
        CountingAdvisorConfiguration.reset();
        send("e2e-advisor-1", "admin", "/api/chat", QUESTION);
        assertTrue(CountingAdvisorConfiguration.calls() > 0, "同步路径应当经过 Advisor");

        CountingAdvisorConfiguration.reset();
        send("e2e-advisor-2", "admin", "/api/chat/stream", QUESTION);
        assertTrue(CountingAdvisorConfiguration.calls() > 0,
                "流式路径同样应当经过 Advisor；一次都没被调到，说明它被静默跳过了");
    }

    private String send(String user, String role, String path, String question) throws Exception {
        HttpResponse<byte[]> response = http.send(ask(user, role, path, question),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        String body = text(response);
        return body.startsWith("event:") ? sseData(body) : body;
    }

    private HttpRequest ask(String role, String path, String question) {
        return ask("e2e-" + System.nanoTime(), role, path, question);
    }

    /**
     * 每条用例换一个用户：会话落库，固定用户会让下一次跑读到上一次的问答，
     * 而真模型会顺着历史里的"没权限"继续答"没权限"，于是测试之间互相污染。
     * 角色显式写出来，别让断言的含义取决于 yml 里的默认值。
     */
    private HttpRequest ask(String user, String role, String path, String question) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", user)
                .header("X-Claw4j-Role", role)
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + question + "\"}"))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * 把 SSE 的 data 行拼回一整段。必须拼：分块边界落在标记中间是常有的事，
     * 直接对原始报文做 contains 会因为换行而误判成"标记没生效"。
     */
    private static String sseData(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).strip())
                .reduce("", String::concat);
    }

    /** 最小 SSE 解析：只认 event 与 data 两种字段，够断言"分了几块、最后一块叫什么"。 */
    private static List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();

        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (name != null) {
                    events.add(new SseEvent(name, data.toString()));
                }
                name = null;
                data.setLength(0);
            }
            else if (line.startsWith("event:")) {
                name = line.substring("event:".length()).strip();
            }
            else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).strip());
            }
        }
        if (name != null) {
            events.add(new SseEvent(name, data.toString()));
        }
        return events;
    }

    private record SseEvent(String name, String data) {
    }

    /**
     * 容器里挂一个会留痕的 Advisor。它按 ADR-0007 的要求同时实现 call 与 stream
     * （{@code BaseAdvisor} 就是那个双实现），所以它自己就是正确写法的范例。
     *
     * <p>留痕只留"被调到了"这一件事，不去改请求或响应内容：这一条要证明的是
     * <b>通道用的那条链里确实有它</b>，而不是它能把回答改成什么样。
     */
    @TestConfiguration
    static class CountingAdvisorConfiguration {

        /** static：三条用例共用一个容器，实例级计数会在测试之间串。断言一律"重置后比差值"。 */
        private static final AtomicInteger CALLS = new AtomicInteger();

        static void reset() {
            CALLS.set(0);
        }

        static int calls() {
            return CALLS.get();
        }

        @Bean
        public Advisor countingAdvisor() {
            return new BaseAdvisor() {

                @Override
                public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
                    CALLS.incrementAndGet();
                    return request;
                }

                @Override
                public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
                    return response;
                }

                /**
                 * 必须显式给名字：BaseAdvisor 的默认实现取 getClass().getSimpleName()，
                 * 匿名类是空串，会让观测上下文在运行时抛 "advisorName cannot be null or empty"，
                 * 而且是流式路径先炸。
                 */
                @Override
                public String getName() {
                    return "countingAdvisor";
                }

                @Override
                public int getOrder() {
                    return Ordered.HIGHEST_PRECEDENCE;
                }
            };
        }
    }
}
