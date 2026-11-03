package io.github.claw4j.app;

import io.github.claw4j.channel.slack.SlackReplySender;
import io.github.claw4j.channel.slack.client.SlackChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真模型闭环（P13）：Slack 里 @ 一句，回调进来、验签过、真模型算完、结果推回那条线程。
 *
 * <p>与企微（P06）、钉钉（P12）两条同形，但<b>验的那一道门完全不同</b>：Slack 的签名覆盖
 * <b>整个原始报文</b>（{@code v0:timestamp:body}），而不只是时间戳。所以这里走真 HTTP
 * 加真 header 签名，并且把报文作为一个字符串原样发出去——反序列化再拼回去就验不过。
 *
 * <p>签名是<b>照协议另写一份</b>（见 {@link #slackSign}）：用生产的那份去签、再用它去验，
 * 两边一起错也照样绿。
 *
 * <p><b>为什么单独一个类而不是并进 {@code ApplicationAssemblyTest}</b>：那个类的方法数已经顶格
 * （20 条），而且它的容器装的是企微与钉钉那套配置。这里要验的是"答案落回线程"——
 * 一个只在这个通道存在的形状，配置也只需要 Slack 那两样。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SlackFlowTest.Recorder.class)
class SlackFlowTest {

    private static final String SIGNING_SECRET = "slackflow-signing-secret";

    private static final String BOT_TOKEN = "xoxb-slackflow-token";

    private static final String USER = "U0FLOW1234";

    private static final String CHANNEL = "C0FLOW1234";

    /** 频道消息的 ts：答案要作为这条消息的线程回复。 */
    private static final String TS = "1515449522.000016";

    private static final String QUESTION = "上周华东区成交额多少";

    /** 容器要在 Spring 建上下文之前起好：DynamicPropertySource 要读它的端口。 */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Slack（P13）：两样齐了通道才装配。同样把回推换成记录器（{@link Recorder#SENT}），
        // 不会真去连 Slack——但验签、解析、编排、回推这一整条链是真的。
        registry.add("claw4j.channel.slack.signing-secret", () -> SIGNING_SECRET);
        registry.add("claw4j.channel.slack.bot-token", () -> BOT_TOKEN);
        registry.add("claw4j.channel.slack.roles.admin.users[0]", () -> USER);
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("真模型闭环：Slack 频道里 @ 一句（真签名），真模型算完，答案落回那条线程")
    void aSlackMentionIsAnsweredThroughTheCallback() throws Exception {
        Recorder.SENT.clear();
        String body = "{\"type\":\"event_callback\",\"event_id\":\"Ev-flow-1\",\"event_time\":1700000000,"
                + "\"event\":{\"type\":\"app_mention\",\"user\":\"" + USER + "\","
                + "\"text\":\"<@U0LAN2Q65> " + QUESTION + "\",\"ts\":\"" + TS + "\","
                + "\"channel\":\"" + CHANNEL + "\"}}";

        String ack = slackCallback(body);

        assertThat(ack).as("回调必须当场回空串：Slack 3 秒收不到 2xx 就算一次投递失败").isEmpty();
        awaitSends(2);
        assertThat(Recorder.SENT.getFirst())
                .as("先一句回执：那十几秒里用户至少知道有人接了，而且它也在线程里")
                .isEqualTo(CHANNEL + "|" + TS + "|" + SlackChannel.RECEIPT_REPLY);
        String answer = Recorder.SENT.get(1);
        assertThat(answer)
                .as("答案要落回原消息的线程（thread_ts 是它的 ts）；实际：%s", answer)
                .startsWith(CHANNEL + "|" + TS + "|");
        assertThat(mentions(answer, "128400"))
                .as("答案里该出现库里的 128400，实际：%s", answer)
                .isTrue();
    }

    /**
     * 金额可能被写成 128,400 或 ￥128400：去掉分隔符再比，比的就是那个数。
     */
    private static boolean mentions(String text, String expected) {
        return text.replaceAll("[,，￥¥\\s]", "").contains(expected);
    }

    /**
     * 发一条 Slack 回调，返回平台的应答文本。
     *
     * <p>走真 HTTP：{@code @LocalServerPort} 那个端口上挂着真正的控制器。
     * 签名放在 header 里，时间戳是<b>秒</b>——照抄钉钉的毫秒会让每一条回调都被判成过期。
     */
    private String slackCallback(String body) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/slack/events"))
                .header("Content-Type", "application/json")
                .header("X-Slack-Request-Timestamp", timestamp)
                .header("X-Slack-Signature", slackSign(timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();
        return new String(http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body(),
                StandardCharsets.UTF_8);
    }

    /**
     * Slack 的签名：{@code "v0=" + hex(HmacSHA256(key=signingSecret, data="v0:" + timestamp + ":" + body))}。
     *
     * <p>两处容易写错，写在这里备查：basestring 里是冒号 {@code v0:}，签名里是等号 {@code v0=}；
     * 而 body 必须是<b>原始</b>字符串——反序列化再序列化一遍，字段顺序变了就验不过。
     */
    private static String slackSign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
        return "v0=" + HexFormat.of().formatHex(digest);
    }

    private static void awaitSends(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && Recorder.SENT.size() < count) {
            Thread.sleep(100);
        }
        assertThat(Recorder.SENT).as("等不到第 %d 条推送", count).hasSizeGreaterThanOrEqualTo(count);
    }

    /**
     * 把回推换成记录器。
     *
     * <p>自动配置那边是 {@code @ConditionalOnMissingBean}，于是这个替身赢：真实实现会去连 Slack，
     * 而这条用例要问的是"推回来的那段文字是什么、推到了哪个线程"——不是"接口通不通"。
     * 记录成 {@code conversationId|text}，断言里一眼看出落点。
     */
    @TestConfiguration
    static class Recorder {

        /** 顺序有意义：回执在前、答案在后。 */
        static final List<String> SENT = Collections.synchronizedList(new ArrayList<>());

        @Bean
        SlackReplySender slackReplySender() {
            return (conversationId, text) -> SENT.add(conversationId + "|" + text);
        }
    }
}
