package io.github.claw4j.channel.slack.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.channel.slack.SlackOutboundDelivery;
import io.github.claw4j.channel.slack.SlackReplySender;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slack 通道：回调那一侧（验签、握手、只处理该处理的、机器人自己的消息不回、重投去重、
 * 答案落在线程里）、发送那一侧（判 {@code ok} 而不是判状态码、限流重试、同频道排队）、
 * 装配那一侧（凭据不齐就一个 bean 都不留）。
 *
 * <p>签名照着协议另写一份（见 {@link #signOf}）：用生产的 {@code SlackSignature} 去签、
 * 再用它去验，两边一起错也照样绿。这段 HMAC 只有五行，但它恰好是"门在不在"的唯一判据。
 *
 * <p>不用外部依赖：不连 Slack、不连数据库，{@code 收} 那侧用真 {@link InboundDispatcher}
 * 加一个直接执行的 executor（同步跑完，断言不用等），{@code 发} 那侧用 JDK 自带的
 * {@link HttpServer} 假扮 {@code chat.postMessage}。
 */
class SlackChannelTest {

    private static final String SIGNING_SECRET = "slacktest-signing-secret";

    private static final String BOT_TOKEN = "xoxb-slacktest-token";

    private static final String USER = "U061F7AUR";

    private static final String CHANNEL = "C0LAN2Q65";

    private static final String DM_CHANNEL = "D024BE91L";

    private static final String QUESTION = "上周华东区成交额多少";

    /** 一条频道消息的 ts：文档里的形状是"秒.微秒"。 */
    private static final String TS = "1515449522.000016";

    private static final Principal PRINCIPAL = Principal.of(USER, "admin", Set.of("*"), Set.of("*"));

    /** 记下"推回哪儿、推了什么"。顺序有意义：回执在前、答案在后。 */
    private final List<String> sent = new ArrayList<>();

    private final List<String> asked = new ArrayList<>();

    private final AtomicInteger answers = new AtomicInteger();

    private final AgentPort agent = new AgentPort() {

        @Override
        public OutboundMessage reply(InboundMessage message) {
            answers.incrementAndGet();
            asked.add(message.text());
            return OutboundMessage.of(message.chatId(), "答复");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            throw new UnsupportedOperationException("Slack 走一次性回答");
        }
    };

    private final SlackReplySender replySender = (conversationId, text) -> sent.add(conversationId + "|" + text);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------------------------------------------------------------- 回调这一侧

    @Test
    @DisplayName("频道里 @ 应用：那句话交给编排，mention 被剥掉，回调当场回空串")
    void aMentionIsHandedToTheAgent() {
        String response = post(controller(), mentionBody("Ev-1", QUESTION));

        assertThat(response).as("回调必须回空串：Slack 只看状态码，超过 3 秒算一次投递失败").isEmpty();
        assertThat(asked)
                .as("@ 的那串 id 要剥掉：它占 token，写进 SQL 注释里还像是被人动过")
                .containsExactly(QUESTION);
    }

    @Test
    @DisplayName("先回执再答案：那十几秒里用户至少知道“有人接了”")
    void theReceiptArrivesBeforeTheAnswer() {
        post(controller(), mentionBody("Ev-2", QUESTION));

        assertThat(sent).containsExactly(
                CHANNEL + "|" + TS + "|" + SlackChannel.RECEIPT_REPLY,
                CHANNEL + "|" + TS + "|答复");
    }

    @Test
    @DisplayName("答案落回原消息的线程；私聊不挂线程（发顶层）")
    void theAnswerGoesBackIntoTheThreadAndDirectMessagesStayOnTop() {
        post(controller(), mentionBody("Ev-3", QUESTION));
        post(controller(), directMessageBody("Ev-4", QUESTION));

        assertThat(sent.getFirst()).as("频道里 @ 的回答要挂在原消息下面，不然回执加答案把频道刷满")
                .startsWith(CHANNEL + "|" + TS + "|");
        assertThat(sent.getLast()).as("私聊发顶层：conversationId 里只有一个频道 id，没有竖线")
                .startsWith(DM_CHANNEL + "|")
                .doesNotContain("|" + TS);
    }

    @Test
    @DisplayName("签名不对、或时间戳在 6 分钟前：一律丢弃，不调模型、也不回话")
    void aWrongSignatureOrAStaleTimestampIsDropped() {
        SlackCallbackController controller = controller();
        String body = mentionBody("Ev-5", QUESTION);
        String now = secondsNow();

        assertThat(controller.receive(now, "v0=0000000000000000000000000000000000000000000000000000000000000000",
                null, body)).as("拒绝也回 200：非 2xx 会被算成投递失败，往 95% 那条线上记一笔").isEmpty();
        String stale = String.valueOf(System.currentTimeMillis() / 1000 - Duration.ofMinutes(6).toSeconds());
        assertThat(controller.receive(stale, signOf(stale, body), null, body)).isEmpty();

        assertThat(asked).as("验签没过就不该让任何内容走到模型").isEmpty();
        assertThat(answers).hasValue(0);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("配置请求地址时的握手：回 challenge 原文，地址才存得下来")
    void theHandshakeReturnsTheChallenge() {
        String body = "{\"token\":\"deprecated\",\"challenge\":\"3eZbrw1aBm2rZgRNFdxV\","
                + "\"type\":\"url_verification\"}";

        assertThat(post(controller(), body)).isEqualTo("3eZbrw1aBm2rZgRNFdxV");
        assertThat(answers).hasValue(0);
    }

    @Test
    @DisplayName("握手也排在验签之后：不给它开“先证明地址活着”的后门")
    void theHandshakeIsBehindTheSignatureCheck() {
        String body = "{\"challenge\":\"should-not-leak\",\"type\":\"url_verification\"}";

        assertThat(controller().receive(secondsNow(), "v0=deadbeef", null, body))
                .as("签名不对时连 challenge 都不该回：不验签就回，等于向所有人证明这个地址是活的")
                .isEmpty();
    }

    @Test
    @DisplayName("机器人自己发的、以及被编辑的消息：不处理，模型一次都不跑")
    void messagesFromBotsOrEditsAreIgnored() {
        post(controller(), "{\"type\":\"event_callback\",\"event_id\":\"Ev-bot\",\"event_time\":1700000000,"
                + "\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"" + DM_CHANNEL + "\","
                + "\"user\":\"" + USER + "\",\"bot_id\":\"B0123\",\"text\":\"" + QUESTION + "\",\"ts\":\"" + TS + "\"}}");
        post(controller(), "{\"type\":\"event_callback\",\"event_id\":\"Ev-edit\",\"event_time\":1700000000,"
                + "\"event\":{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel_type\":\"im\","
                + "\"channel\":\"" + DM_CHANNEL + "\",\"user\":\"" + USER + "\",\"ts\":\"" + TS + "\","
                + "\"message\":{\"text\":\"" + QUESTION + "\"}}}");

        assertThat(answers)
                .as("不挡 bot_id 就是自己回自己：一问一答无限循环，不报错、只在账单上体现")
                .hasValue(0);
        assertThat(asked).isEmpty();
        assertThat(sent).as("这两类连回话都不发：编辑一条消息不是一次提问").isEmpty();
    }

    @Test
    @DisplayName("频道里的普通消息（没 @ 应用）：不处理——订阅了 message.channels 也只是白跑")
    void aChannelMessageThatDoesNotMentionUsIsIgnored() {
        post(controller(), "{\"type\":\"event_callback\",\"event_id\":\"Ev-chat\",\"event_time\":1700000000,"
                + "\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"" + CHANNEL + "\","
                + "\"user\":\"" + USER + "\",\"text\":\"" + QUESTION + "\",\"ts\":\"" + TS + "\"}}");

        assertThat(answers).hasValue(0);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("同一个 event_id 被重投两次：只回答一次；重投头只记日志，不用来跳过")
    void theSameEventIdTwiceIsAnsweredOnce() {
        SlackCallbackController controller = controller();
        String body = mentionBody("Ev-6", QUESTION);

        post(controller, body);
        String second = controller.receive(secondsNow(), signOf(secondsNow(), body), null, body);

        assertThat(second).as("重投也要照常 ACK，否则 Slack 会一直重推").isEmpty();
        assertThat(answers).as("模型只该跑一次：重投不是第二次提问").hasValue(1);
        assertThat(sent).as("回执也不该重复：那会让人以为问了两遍").hasSize(2);
    }

    @Test
    @DisplayName("只 @ 了应用、没写问题：回一句“问点什么”，不进模型")
    void aMentionWithNoQuestionGetsAPrompt() {
        post(controller(), mentionBody("Ev-7", ""));

        assertThat(answers).as("空提问进模型会得到一段对着空气编的回答，还花一次 token").hasValue(0);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("有什么要问的");
    }

    @Test
    @DisplayName("报文坏了（不是 JSON）：回空串不抛，抛出去 Slack 会算一次投递失败")
    void aBrokenBodyIsAcknowledged() {
        SlackCallbackController controller = controller();
        String now = secondsNow();

        assertThat(controller.receive(now, signOf(now, "这不是 JSON"), null, "这不是 JSON")).isEmpty();
        assertThat(controller.receive(now, signOf(now, ""), null, "")).isEmpty();
        assertThat(answers).hasValue(0);
    }

    @Test
    @DisplayName("signing-secret 是空的：构造就失败。空密钥能算出签名，等于让任何自造请求都验得过")
    void aBlankSecretFailsAtConstruction() {
        assertThatThrownBy(() -> new SlackSignature(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing-secret");
    }

    // ---------------------------------------------------------------- 投递

    @Test
    @DisplayName("主动投递：通道名与库里那一列同口径，地址（含线程）原样交给回推")
    void handsTheAddressToTheReplySender() {
        SlackOutboundDelivery delivery = new SlackOutboundDelivery(replySender);

        delivery.send(CHANNEL + "|" + TS, "华东上周成交 128400");

        assertThat(delivery.channel()).isEqualTo("slack");
        assertThat(sent).containsExactly(CHANNEL + "|" + TS + "|华东上周成交 128400");
    }

    // ---------------------------------------------------------------- 发送这一侧

    @Test
    @DisplayName("发消息：Bearer 令牌、channel、thread_ts 都在，成功只看 ok")
    void sendsChannelTextAndThreadTs() {
        List<String> bodies = new ArrayList<>();
        List<String> auths = new ArrayList<>();
        String base = new FakeSlack(bodies, auths, new ArrayList<>(), () -> Reply.ok()).start();

        sender(base, Duration.ZERO).send(CHANNEL + "|" + TS, "华东上周成交 128400");

        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst())
                .as("thread_ts 必须是线程根的 ts，传错就是开了一条新线程，群里看到的还是两条独立消息")
                .contains("\"channel\":\"" + CHANNEL + "\"")
                .contains("\"thread_ts\":\"" + TS + "\"")
                .contains("华东上周成交 128400");
        assertThat(auths).containsExactly("Bearer " + BOT_TOKEN);
    }

    @Test
    @DisplayName("HTTP 200 但 ok=false：必须抛，不能吞成“发出去了”")
    void anOkFalseIsThrownEvenOnHttp200() {
        List<String> bodies = new ArrayList<>();
        String base = new FakeSlack(bodies, new ArrayList<>(), new ArrayList<>(),
                () -> new Reply(200, "{\"ok\":false,\"error\":\"not_in_channel\"}", "")).start();

        assertThatThrownBy(() -> sender(base, Duration.ZERO).send(CHANNEL, "一句话"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not_in_channel");
    }

    @Test
    @DisplayName("撞上限流（429 + Retry-After）：等一会儿再试一次，第二次成了就不抛")
    void surfacesRateLimitAndRetriesOnce() {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        String base = new FakeSlack(bodies, new ArrayList<>(), new ArrayList<>(),
                // Retry-After 给 0 秒：这条用例要验的是"会不会重试"，不是"等得对不对"
                () -> calls.incrementAndGet() == 1
                        ? new Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", "0")
                        : Reply.ok()).start();

        sender(base, Duration.ZERO).send(CHANNEL, "一句话");

        assertThat(calls).as("第一次 429、第二次成功：这条消息最终发出去了").hasValue(2);
        assertThat(bodies).hasSize(2);
    }

    @Test
    @DisplayName("同一个频道连着发两条：第二条要等够间隔——不然长答案第 2 段就没了")
    void waitsBetweenMessagesToTheSameChannel() {
        List<Long> times = new ArrayList<>();
        String base = new FakeSlack(new ArrayList<>(), new ArrayList<>(), times, () -> Reply.ok()).start();
        SlackReplySender sender = sender(base, Duration.ofMillis(150));

        sender.send(CHANNEL, "第一段");
        sender.send(CHANNEL, "第二段");

        assertThat(times).hasSize(2);
        assertThat(times.get(1) - times.get(0))
                .as("同频道 1 秒 1 条；撞上之后 InboundDispatcher 会停在第 2 段，用户收到半截答案")
                .isGreaterThanOrEqualTo(120L);
    }

    // ---------------------------------------------------------------- 装配

    @Test
    @DisplayName("凭据不全（只给了 signing-secret）：一个 bean 都不留，HTTP 照常")
    void credentialsIncompleteLeavesTheChannelOut() {
        runner().withPropertyValues("claw4j.channel.slack.signing-secret=" + SIGNING_SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SlackCallbackController.class);
            assertThat(context).doesNotHaveBean(SlackReplySender.class);
            assertThat(context).doesNotHaveBean(SlackChannel.class);
        });
    }

    @Test
    @DisplayName("两样齐了：端点、通道、投递、发送器都在，通道名与库里那一列一致")
    void configuredChannelRegistersTheEndpointAndTheDelivery() {
        runner().withPropertyValues(configuredProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SlackCallbackController.class);
            assertThat(context).hasSingleBean(SlackChannel.class);
            assertThat(context).hasSingleBean(SlackReplySender.class);
            assertThat(context.getBeansOfType(OutboundDelivery.class).values())
                    .as("投递实现要能按通道名被 ConversationDelivery 找到")
                    .singleElement()
                    .extracting(OutboundDelivery::channel)
                    .isEqualTo("slack");
        });
    }

    @Test
    @DisplayName("同一个人登记了两个角色：启动失败并点名，而不是按书写顺序挑一个")
    void duplicateRegistrationFailsAtStartup() {
        runner().withPropertyValues(configuredProperties())
                .withPropertyValues(
                        "claw4j.channel.slack.roles.read-only.users[0]=" + USER,
                        "claw4j.channel.slack.roles.guest.users[0]=" + USER)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(
                                "claw4j.channel.slack.roles 里同一个人登记了多个角色：" + USER
                                        + "。一个人只能有一个身份，请删掉多余的那几处。"));
    }

    // ---------------------------------------------------------------- 夹具

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SlackChannelAutoConfiguration.class))
                .withBean(AgentPort.class, () -> agent);
    }

    private static String[] configuredProperties() {
        return new String[]{
                "claw4j.channel.slack.signing-secret=" + SIGNING_SECRET,
                "claw4j.channel.slack.bot-token=" + BOT_TOKEN
        };
    }

    private SlackCallbackController controller() {
        InboundDispatcher dispatcher = new InboundDispatcher(agent, Runnable::run,
                userId -> USER.equals(userId) ? Optional.of(PRINCIPAL) : Optional.empty(),
                replySender, "Slack", SlackOutboundDelivery.CHANNEL, SlackChannel.RECEIPT_REPLY);
        return new SlackCallbackController(new SlackSignature(SIGNING_SECRET), dispatcher, replySender);
    }

    private String post(SlackCallbackController controller, String body) {
        String timestamp = secondsNow();
        return controller.receive(timestamp, signOf(timestamp, body), null, body);
    }

    /** Slack 的时间戳是秒。照抄钉钉的毫秒会让每一条回调都被判成过期。 */
    private static String secondsNow() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    private static String mentionBody(String eventId, String question) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId + "\",\"event_time\":1700000000,"
                + "\"event\":{\"type\":\"app_mention\",\"user\":\"" + USER + "\","
                + "\"text\":\"<@U0LAN0Z89> " + question + "\",\"ts\":\"" + TS + "\","
                + "\"channel\":\"" + CHANNEL + "\"}}";
    }

    private static String directMessageBody(String eventId, String question) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId + "\",\"event_time\":1700000000,"
                + "\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"user\":\"" + USER + "\","
                + "\"text\":\"" + question + "\",\"ts\":\"" + TS + "\",\"channel\":\"" + DM_CHANNEL + "\"}}";
    }

    // ---- 下面这个是"另一份实现"，故意不调用生产代码（见类注释） ----

    private static String signOf(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private SlackReplySender sender(String apiBase, Duration minInterval) {
        SlackProperties properties = new SlackProperties();
        properties.setSigningSecret(SIGNING_SECRET);
        properties.setBotToken(BOT_TOKEN);
        properties.setApiBase(apiBase);
        properties.setMinInterval(minInterval);
        return new SlackHttpReplySender(properties);
    }

    /** 一次假响应：状态码、响应体、{@code Retry-After}。限流那条要 429，其余 200。 */
    private record Reply(int status, String body, String retryAfter) {

        static Reply ok() {
            return new Reply(200, "{\"ok\":true,\"ts\":\"" + TS + "\"}", "");
        }
    }

    /**
     * 用 JDK 自带的 HTTP 服务器假扮 {@code chat.postMessage}：记请求体、授权头与到达时刻。
     *
     * <p>不用 MockWebServer 之类的库：这里要验的是"请求长什么样、隔多久发"这两件事，
     * 一个四十行的假服务比一个新依赖更容易看清。
     */
    private final class FakeSlack {

        private final List<String> bodies;

        private final List<String> auths;

        /** 到达时刻（毫秒）。间隔那条用例要看两次请求差多少。 */
        private final List<Long> times;

        private final Supplier<Reply> response;

        private FakeSlack(List<String> bodies, List<String> auths, List<Long> times, Supplier<Reply> response) {
            this.bodies = bodies;
            this.auths = auths;
            this.times = times;
            this.response = response;
        }

        String start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException failure) {
                throw new IllegalStateException("起不了本地假 Slack", failure);
            }
            server.createContext("/chat.postMessage", exchange -> {
                times.add(System.currentTimeMillis());
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                auths.add(auth == null ? "<missing>" : auth);
                Reply reply = response.get();
                if (!reply.retryAfter().isBlank()) {
                    exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
                }
                respond(exchange, reply.status(), reply.body());
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
