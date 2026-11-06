package io.github.claw4j.channel.dingtalk.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.claw4j.channel.dingtalk.DingtalkOutboundDelivery;
import io.github.claw4j.channel.dingtalk.DingtalkReplySender;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉钉通道：回调那一侧（验签、时间戳窗口、四种报文四种去处、重推去重、认不出人）、
 * 发送那一侧（换 token、复用、{@code msgParam} 的形状、限流与错误码）、
 * 装配那一侧（配置不齐就一个 bean 都不留）。
 *
 * <p><b>签名是照着协议另写一份</b>（见 {@link #signOf}）：用生产的 {@code DingtalkSignature}
 * 去签、再用它去验，两边一起错也照样绿。这段 HMAC 只有五行，但它恰好是"门在不在"的唯一判据，
 * 值得写两遍。
 *
 * <p>一个外部依赖都不需要：不连钉钉、不连数据库，{@code 收} 那侧用真 {@link InboundDispatcher}
 * 加一个直接执行的 executor（同步跑完，断言不用等），{@code 发} 那侧用 JDK 自带的
 * {@link HttpServer} 假扮钉钉的两个接口。
 */
@ExtendWith(OutputCaptureExtension.class)
class DingtalkChannelTest {

    private static final String CLIENT_ID = "dingtest-client";

    private static final String CLIENT_SECRET = "dingtest-secret";

    private static final String ROBOT_CODE = "dingtest-robot";

    private static final String USER = "manager1234";

    private static final String QUESTION = "上周华东区成交额多少";

    private static final Principal PRINCIPAL = Principal.of(USER, "admin", Set.of("*"), Set.of("*"));

    /** 记下"推回给谁、推了什么"。顺序有意义：回执在前、答案在后。 */
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
            throw new UnsupportedOperationException("钉钉走一次性回答");
        }
    };

    private final DingtalkReplySender replySender = (userId, text) -> sent.add(userId + "|" + text);

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
    @DisplayName("一条文本消息交给入站编排：认出的用户与那句话都带过去，回调当场回空串")
    void aTextMessageIsHandedToTheAgent() {
        String response = post(controller(), textBody("msg-1", USER, QUESTION));

        assertThat(response).as("回调必须回空串：钉钉等不到响应会重推").isEmpty();
        assertThat(asked).containsExactly(QUESTION);
    }

    @Test
    @DisplayName("先回执再答案：用户在那十几秒里至少知道“有人接了”")
    void theReceiptArrivesBeforeTheAnswer() {
        post(controller(), textBody("msg-2", USER, QUESTION));

        assertThat(sent).containsExactly(USER + "|" + DingtalkChannel.RECEIPT_REPLY, USER + "|答复");
    }

    @Test
    @DisplayName("签名不对但报文完全合法：丢弃、回空串（不是 5xx）、不调模型、不花 token")
    void aWrongSignatureIsDroppedEvenWhenTheBodyLooksRight() {
        // 报文是真的，签名是别人的：这正是"只验报文不验签"会放过去的那一条
        String response = controller().receive(timestampNow(), "bm90LXRoZS1yaWdodC1zaWduYXR1cmU=",
                textBody("msg-3", USER, QUESTION));

        assertThat(response).as("拒绝也回 200：5xx 在协议里是“没收到，再发一次”").isEmpty();
        assertThat(asked).as("验签没过就不该让任何内容走到模型").isEmpty();
        assertThat(answers).hasValue(0);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("签名对得上但时间戳在 2 小时前：拒绝——签名本身不过期，窗口就是唯一的时效")
    void aStaleTimestampIsRejectedEvenWithAValidSignature() {
        String stale = String.valueOf(System.currentTimeMillis() - Duration.ofHours(2).toMillis());

        String response = controller().receive(stale, signOf(stale), textBody("msg-4", USER, QUESTION));

        assertThat(response).isEmpty();
        assertThat(answers).as("窗口外的一律不处理，哪怕签名算得出来").hasValue(0);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("同一 msgId 被重推两次：只回答一次，也只回执一次")
    void theSameMessageIdTwiceIsAnsweredOnce() {
        DingtalkCallbackController controller = controller();

        post(controller, textBody("msg-5", USER, QUESTION));
        String secondResponse = post(controller, textBody("msg-5", USER, QUESTION));

        assertThat(secondResponse).as("重推也要照常 ACK，否则钉钉会一直重推").isEmpty();
        assertThat(answers).as("模型只该跑一次：重推不是第二次提问").hasValue(1);
        assertThat(sent).as("回执也不该重复：那会让人以为问了两遍").hasSize(2);
    }

    @Test
    @DisplayName("没登记的人：回“没有权限”，这句话不经过模型、也不花 token，连回执都不发")
    void anUnregisteredUserIsRefusedWithoutCallingTheModel() {
        post(controller(), textBody("msg-6", "stranger", QUESTION));

        assertThat(answers).hasValue(0);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).as("拒绝理由要说清是哪个通道、找谁开通")
                .contains("没有权限").contains("钉钉");
    }

    @Test
    @DisplayName("群聊（conversationType=2）：不答模型，但回一句“私聊我”——静默丢弃等于机器人坏了")
    void aGroupMessageGetsAPrivateChatRequest() {
        String group = "{\"msgId\":\"msg-7\",\"senderStaffId\":\"" + USER + "\","
                + "\"conversationType\":\"2\",\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + QUESTION + "\"},\"createAt\":1700000000000}";

        post(controller(), group);

        assertThat(answers).hasValue(0);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).as("第一版只在单聊作答，这个裁剪要让用户看得见")
                .contains("私聊").contains("按人算");
    }

    @Test
    @DisplayName("图片语音这类：不猜内容，回一句“打成文字”")
    void anUnsupportedMessageGetsATextRequest() {
        String image = "{\"msgId\":\"msg-8\",\"senderStaffId\":\"" + USER + "\","
                + "\"conversationType\":\"1\",\"msgtype\":\"picture\","
                + "\"content\":{\"downloadCode\":\"xyz\"},\"createAt\":1700000000000}";

        post(controller(), image);

        assertThat(answers).hasValue(0);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("打成文字");
    }

    @Test
    @DisplayName("没有 senderStaffId：日志说“钉钉没给身份”，别写成“这个人没登记”")
    void aMessageWithoutStaffIdIsNotMistakenForAnUnregisteredUser(CapturedOutput output) {
        String noSender = "{\"msgId\":\"msg-9\",\"conversationType\":\"1\",\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + QUESTION + "\"},\"createAt\":1700000000000}";

        post(controller(), noSender);

        assertThat(answers).hasValue(0);
        assertThat(sent).as("没有收件人就没有可发的地方，连“没有权限”也发不出去").isEmpty();
        assertThat(output)
                .as("这两件事在 IM 里长得一样，排查成本差很远：一个是机器人没发布，一个是人没登记")
                .contains("钉钉没给身份")
                .contains("不是“这个人没登记”");
    }

    @Test
    @DisplayName("报文坏了（不是 JSON）：回空串不抛，抛出去钉钉会一直重推")
    void aBrokenBodyIsAcknowledgedWithAnEmptyString() {
        DingtalkCallbackController controller = controller();

        assertThat(controller.receive(timestampNow(), signOf(timestampNow()), "这不是 JSON")).isEmpty();
        assertThat(controller.receive(timestampNow(), signOf(timestampNow()), "")).isEmpty();
        assertThat(answers).hasValue(0);
    }

    @Test
    @DisplayName("client-secret 是空的：构造就失败。空密钥能算出签名，等于让任何自造请求都验得过")
    void aBlankSecretFailsAtConstruction() {
        assertThatThrownBy(() -> new DingtalkSignature(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-secret");
    }

    // ---------------------------------------------------------------- 投递

    @Test
    @DisplayName("主动投递：通道名与库里那一列同口径，地址原样交给回推")
    void handsTheAddressToTheReplySender() {
        DingtalkOutboundDelivery delivery = new DingtalkOutboundDelivery(replySender);

        delivery.send(USER, "华东上周成交 128400");

        assertThat(delivery.channel()).isEqualTo("dingtalk");
        assertThat(sent).containsExactly(USER + "|华东上周成交 128400");
    }

    // ---------------------------------------------------------------- 发送这一侧

    @Test
    @DisplayName("token 只换一次；msgParam 是字符串不是对象；令牌走钉钉那个 header")
    void reusesTheTokenAndKeepsMsgParamAsAString() {
        AtomicInteger tokenCalls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        List<String> tokenHeaders = new ArrayList<>();
        String base = new FakeDingtalk(tokenCalls, bodies, tokenHeaders,
                () -> new Reply(200, "{\"processQueryKey\":\"pq-1\"}")).start();

        DingtalkReplySender sender = sender(base);
        sender.send(USER, "第一条");
        sender.send(USER, "第二条");

        assertThat(tokenCalls).as("7200 秒的令牌不该每发一条换一次").hasValue(1);
        assertThat(bodies).hasSize(2);
        assertThat(bodies.getFirst())
                .contains("\"robotCode\":\"" + ROBOT_CODE + "\"")
                .contains("\"userIds\":[\"" + USER + "\"]")
                .contains("\"msgKey\":\"sampleText\"")
                // msgParam 的值是一段**被序列化的 JSON 文本**，所以引号是转义的。
                // 写成嵌套对象的话这里会是 "msgParam":{"content":...}——钉钉判参数非法。
                .contains("\"msgParam\":\"{\\\"content\\\":\\\"第一条\\\"}\"");
        assertThat(tokenHeaders).as("写成 Authorization: Bearer 会被判未授权")
                .containsExactly("token-1", "token-1");
    }

    @Test
    @DisplayName("限流：记一条 WARN 再抛——“等一会儿就好”与“配置错了”不是一回事")
    void surfacesRateLimitWithAWarning(CapturedOutput output) {
        String base = new FakeDingtalk(new AtomicInteger(), new ArrayList<>(), new ArrayList<>(),
                () -> new Reply(400, "{\"code\":\"send.too.fast\",\"message\":\"send too fast\"}")).start();

        assertThatThrownBy(() -> sender(base).send(USER, "发太快了"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("send.too.fast");
        assertThat(output).as("限流要能被看见：它不会自己好，但也不是配置错")
                .contains("钉钉限流")
                .contains("send.too.fast");
    }

    @Test
    @DisplayName("code 不是空的：一律抛，别让“没发出去”在日志里看起来像发成功了")
    void surfacesNonEmptyCode() {
        String base = new FakeDingtalk(new AtomicInteger(), new ArrayList<>(), new ArrayList<>(),
                () -> new Reply(400, "{\"code\":\"robot.oto.notExist\","
                        + "\"message\":\"robot oto not exist\"}")).start();

        assertThatThrownBy(() -> sender(base).send(USER, "发不出去"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("robot.oto.notExist");
    }

    // ---------------------------------------------------------------- 装配

    @Test
    @DisplayName("三样配置不齐：一个 bean 都不留，只跑 HTTP 的部署照常起来")
    void credentialsIncompleteLeavesTheChannelOut() {
        runner().withPropertyValues("claw4j.channel.dingtalk.client-id=dingtest-client").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DingtalkCallbackController.class);
            assertThat(context).doesNotHaveBean(DingtalkReplySender.class);
            assertThat(context).doesNotHaveBean(DingtalkChannel.class);
        });
    }

    @Test
    @DisplayName("三样齐了：回调端点、通道、投递、发送器都在，通道名与库里那一列一致")
    void configuredChannelRegistersTheCallbackAndTheDelivery() {
        runner().withPropertyValues(configuredProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DingtalkCallbackController.class);
            assertThat(context).hasSingleBean(DingtalkChannel.class);
            assertThat(context).hasSingleBean(DingtalkReplySender.class);
            assertThat(context.getBeansOfType(OutboundDelivery.class).values())
                    .as("投递实现要能按通道名被 ConversationDelivery 找到")
                    .singleElement()
                    .extracting(OutboundDelivery::channel)
                    .isEqualTo("dingtalk");
        });
    }

    @Test
    @DisplayName("同一个人登记了两个角色：启动失败并点名，而不是按书写顺序挑一个")
    void duplicateRegistrationFailsAtStartup() {
        runner().withPropertyValues(configuredProperties())
                .withPropertyValues(
                        "claw4j.channel.dingtalk.roles.read-only.users[0]=" + USER,
                        "claw4j.channel.dingtalk.roles.guest.users[0]=" + USER)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(
                                "claw4j.channel.dingtalk.roles 里同一个人登记了多个角色：" + USER
                                        + "。一个人只能有一个身份，请删掉多余的那几处。"));
    }

    // ---------------------------------------------------------------- 夹具

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DingtalkChannelAutoConfiguration.class))
                .withBean(AgentPort.class, () -> agent);
    }

    private static String[] configuredProperties() {
        return new String[]{
                "claw4j.channel.dingtalk.client-id=" + CLIENT_ID,
                "claw4j.channel.dingtalk.client-secret=" + CLIENT_SECRET,
                "claw4j.channel.dingtalk.robot-code=" + ROBOT_CODE
        };
    }

    private DingtalkCallbackController controller() {
        InboundDispatcher dispatcher = new InboundDispatcher(agent, Runnable::run,
                userId -> USER.equals(userId) ? Optional.of(PRINCIPAL) : Optional.empty(),
                replySender, "钉钉", DingtalkOutboundDelivery.CHANNEL, DingtalkChannel.RECEIPT_REPLY);
        return new DingtalkCallbackController(
                new DingtalkSignature(CLIENT_SECRET), dispatcher, replySender);
    }

    private String post(DingtalkCallbackController controller, String body) {
        String timestamp = timestampNow();
        return controller.receive(timestamp, signOf(timestamp), body);
    }

    private static String timestampNow() {
        return String.valueOf(System.currentTimeMillis());
    }

    private static String textBody(String msgId, String user, String content) {
        return "{\"msgId\":\"" + msgId + "\",\"senderStaffId\":\"" + user + "\","
                + "\"conversationType\":\"1\",\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + content + "\"},"
                + "\"createAt\":1700000000000}";
    }

    // ---- 下面这个是"另一份实现"，故意不调用生产代码（见类注释） ----

    private static String signOf(String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "\n" + CLIENT_SECRET)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private DingtalkReplySender sender(String apiBase) {
        DingtalkProperties properties = new DingtalkProperties();
        properties.setClientId(CLIENT_ID);
        properties.setClientSecret(CLIENT_SECRET);
        properties.setRobotCode(ROBOT_CODE);
        properties.setApiBase(apiBase);
        return new DingtalkHttpReplySender(properties);
    }

    /** 一次假响应：状态码加响应体。限流那条要 400，成功那条要 200。 */
    private record Reply(int status, String body) {
    }

    /**
     * 用 JDK 自带的 HTTP 服务器假扮钉钉：{@code accessToken} 记次数、{@code batchSend} 记请求体
     * 与令牌 header。
     *
     * <p>不用 MockWebServer 之类的库：这里要验的是"换一次令牌、复用、msgParam 的形状"
     * 这三条关于<b>请求长什么样</b>的事，一个四十行的假服务比一个新依赖更容易看清。
     */
    private final class FakeDingtalk {

        private final AtomicInteger tokenCalls;

        private final List<String> bodies;

        private final List<String> tokenHeaders;

        /** 发送接口回什么。做成函数是因为成功与限流要走不同的状态码。 */
        private final Supplier<Reply> messageResponse;

        private FakeDingtalk(AtomicInteger tokenCalls, List<String> bodies, List<String> tokenHeaders,
                             Supplier<Reply> messageResponse) {
            this.tokenCalls = tokenCalls;
            this.bodies = bodies;
            this.tokenHeaders = tokenHeaders;
            this.messageResponse = messageResponse;
        }

        String start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException failure) {
                throw new IllegalStateException("起不了本地假钉钉", failure);
            }
            server.createContext("/v1.0/oauth2/accessToken", exchange -> {
                tokenCalls.incrementAndGet();
                respond(exchange, 200, "{\"accessToken\":\"token-" + tokenCalls.get() + "\",\"expireIn\":7200}");
            });
            server.createContext("/v1.0/robot/oToMessages/batchSend", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String header = exchange.getRequestHeaders().getFirst("x-acs-dingtalk-access-token");
                tokenHeaders.add(header == null ? "<missing>" : header);
                Reply reply = messageResponse.get();
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
