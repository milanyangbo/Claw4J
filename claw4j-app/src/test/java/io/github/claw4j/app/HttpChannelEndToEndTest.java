package io.github.claw4j.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 的验收口径：一个人在本机 curl 一句话，能拿到回答；流式同样能逐块收到。
 *
 * <p>刻意用真实端口 + 真实 HTTP 客户端，不走 MockMvc——SSE 是"连接先返回、内容后到"，
 * MockMvc 测不出这件事。这里从通道接缝进（HTTP 请求）、从通道接缝出（HTTP 响应），
 * 中间经过完整的 agent 与模型装配，是唯一一条覆盖全链路的测试。
 *
 * <p>模型强制 mock：CI 上没有 API key，也不该为了跑测试烧 token（ADR-0006）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"claw4j.model.mock=true"})
class HttpChannelEndToEndTest {

    private static final String QUESTION = "上周华东区成交额多少";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void 一句话进一整段出() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("/api/chat"), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        String body = text(response);
        assertTrue(body.contains(QUESTION), "回答应当把问题走完全链路回声出来，实际：" + body);
        assertTrue(body.contains("[mock]"), "这条链路用的是 mock 模型，实际：" + body);
    }

    @Test
    void 没有用户标识的请求进不来() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + QUESTION + "\"}"))
                .build();

        assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void 流式请求逐块返回并以done收尾() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("/api/chat/stream"), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        List<SseEvent> events = parseSse(text(response));

        List<String> chunks = events.stream().filter(event -> "chunk".equals(event.name())).map(SseEvent::data).toList();
        assertTrue(chunks.size() > 1,
                "必须收到多块；只有一块说明流式被降级成了一次性响应，实际块数：" + chunks.size());
        assertTrue(String.join("", chunks).contains(QUESTION), "分块之后拼回来应当是完整回答，实际：" + chunks);
        assertEquals("done", events.get(events.size() - 1).name(), "最后一个事件必须是 done，否则客户端不知道什么时候停");
    }

    @Test
    void 流式与同步两条路径给出同一个答案() throws Exception {
        String sync = text(http.send(ask("/api/chat"), HttpResponse.BodyHandlers.ofByteArray()));
        String streamed = parseSse(text(http.send(ask("/api/chat/stream"), HttpResponse.BodyHandlers.ofByteArray())))
                .stream()
                .filter(event -> "chunk".equals(event.name()))
                .map(SseEvent::data)
                .reduce("", String::concat);

        assertTrue(sync.contains(streamed.trim()),
                "流式拼接结果应当是同步结果的全部或一部分，实际 sync=" + sync + " streamed=" + streamed);
    }

    private HttpRequest ask(String path) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", "e2e-tester")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + QUESTION + "\"}"))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * 最小 SSE 解析：只认 event 与 data 两种字段，够断言"分了几块、最后一块叫什么"。
     */
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
            } else if (line.startsWith("event:")) {
                name = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:")) {
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
}
