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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 的验收口径：一个人在本机 curl 一句话，能拿到回答；流式同样能逐块收到。
 *
 * <p>刻意用真实端口 + 真实 HTTP 客户端，不走 MockMvc——SSE 是"连接先返回、内容后到"，
 * MockMvc 测不出这件事。这里从通道接缝进（HTTP 请求）、从通道接缝出（HTTP 响应），
 * 中间经过完整的 agent 与模型装配，是唯一一条覆盖全链路的测试。
 *
 * <p>模型是真 DeepSeek（ADR-0011），库也是真的（app 侧由 {@code compose.yaml} 提供）。
 * 所以断言可以钉到"回答里有没有库里那个数"上——比"回答非空"强得多，
 * 后者无论中间哪一环断了（模型没调工具、SQL 报错、通道丢了内容）都照样绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpChannelEndToEndTest {

    private static final String QUESTION = "上周华东区成交额多少";

    /**
     * 种子里华东区上周就是 128400 = 54000 + 74400。真模型会自己决定要不要加千分位、
     * 带不带小数，所以按这几种写法匹配，而不是钉死一种措辞。
     */
    private static final Pattern EAST_AMOUNT = Pattern.compile("128,?400(\\.\\d+)?");

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void 一句话进一整段出() throws Exception {
        HttpResponse<byte[]> response = http.send(ask("/api/chat"), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        // 换真模型之后（ADR-0011）不能再断言"回答里回着问题原话"——那是回声模型才有的行为。
        // 但也不能退到"回答非空"：那等于没有判据，中间哪一环断了都照样绿。
        // 真模型是自己去查库才拿得到这个数的，所以它同时证明模型、工具、SQL、通道都在。
        String body = text(response);
        assertMentionsEast(body);
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
        assertMentionsEast(String.join("", chunks));
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

        // 真模型不保证两条路径逐字一致（温度 0 只是压低随机，不是消除），
        // 所以这条不再比对原文，而是"两条路都查到了那个数"——
        // 它要抓的是某一条路径根本没走通（流式把内容丢了、同步少跑了一环），
        // 而不是措辞差异。只断言"都非空"抓不到这个：空串之外的任何东西都会让它绿。
        assertMentionsEast(sync);
        assertMentionsEast(streamed);
    }

    private static void assertMentionsEast(String answer) {
        assertTrue(EAST_AMOUNT.matcher(answer).find(),
                "回答里应当出现华东区上周成交额 128400（真查库才拿得到），实际是：" + answer);
    }

    /**
     * 每条用例换一个用户，并显式带角色头。两个理由，都是换真模型之后才暴露的：
     *
     * <p>一是**会话会污染下一条**。会话落库，而 app 这一侧用的是常驻库，
     * 固定用户下一次跑就会读到上一次的问答。假模型时代看不出来（它不看历史）；
     * 真模型会，且它会顺着历史里的"没权限"继续答"没权限"——于是"能查到数"那条绿不了。
     *
     * <p>二是**默认角色是 read-only 而不是 admin**。这里要的是"真的查得到那个数"，
     * 所以把角色显式写出来，别让断言的含义取决于 yml 里的默认值。
     */
    private HttpRequest ask(String path) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Claw4j-User", "e2e-" + System.nanoTime())
                .header("X-Claw4j-Role", "admin")
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
