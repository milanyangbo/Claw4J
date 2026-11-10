package io.github.claw4j.channel.wecom.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.channel.wecom.WecomOutboundDelivery;
import io.github.claw4j.channel.wecom.WecomReplySender;
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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
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
 * 企业微信通道：回调那一侧（验签、解密、三种报文三种去处、重推去重、认不出人）、
 * 发送那一侧（换 token、复用、被拒后重试一次）、装配那一侧（配置不齐就一个 bean 都不留）。
 *
 * <p>这里的 {@code encrypt} 与 {@code signatureOf} 是照着协议描述另写一遍的，不调用生产代码。
 * 用生产的加密去验生产的解密，两边一起错也照样绿，而那正是这类代码最可能的坏法：排序漏了、
 * 长度字段用了小端、receiveid 少读几个字节，全都自洽。两份独立实现才谈得上互相校验。
 *
 * <p>用例一个外部依赖都不需要：不连企业微信、不连数据库。收的那侧用真 {@link InboundDispatcher}
 * 加一个直接执行的 executor（同步跑完，断言不用等），发的那侧用 JDK 自带的 {@link HttpServer}
 * 假扮企业微信的两个接口。
 */
class WecomChannelTest {

    private static final String TOKEN = "claw4j-token";

    private static final String ENCODING_AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C";

    private static final String CORP_ID = "wwtest1234";

    private static final String TIMESTAMP = "1700000000";

    private static final String NONCE = "nonce-1";

    private static final String USER = "zhangsan";

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
            throw new UnsupportedOperationException("企业微信走一次性回答");
        }
    };

    private final WecomReplySender replySender = (userId, text) -> sent.add(userId + "|" + text);

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
    @DisplayName("URL 验证：签名对得上就回解密后的那句明文，后台才显示“验证通过”")
    void urlVerificationEchoesTheDecryptedString() {
        String echo = "5908241731748800";

        String response = controller().verify(signatureOf(encrypt(echo)), TIMESTAMP, NONCE, encrypt(echo));

        assertThat(response).isEqualTo(echo);
    }

    @Test
    @DisplayName("URL 验证：签名不对就回空串，绝不把解出来的内容交出去")
    void urlVerificationRejectsAWrongSignature() {
        String response = controller().verify("deadbeef", TIMESTAMP, NONCE, encrypt("echo"));

        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("一条文本消息交给入站编排：认出的用户与那句话都带过去，回调当场回空串")
    void aTextMessageIsHandedToTheAgent() {
        WecomCallbackController controller = controller();

        String response = post(controller, textXml("msg-1", USER, QUESTION));

        assertThat(response).as("回调必须回空串：五秒内不 ACK，平台会重推").isEmpty();
        assertThat(asked).containsExactly(QUESTION);
    }

    @Test
    @DisplayName("文本消息恒为私聊：企业微信自建应用的回调里没有群这一维")
    void aTextMessageIsAlwaysDirect() {
        WecomMessageTranslator.Parsed parsed =
                new WecomMessageTranslator().parse(textXml("msg-d", USER, QUESTION));

        assertThat(parsed.kind()).isEqualTo(WecomMessageTranslator.Kind.TEXT);
        assertThat(parsed.event().direct())
                .as("官方接收消息的字段清单里没有会话类型：应用消息就是成员发给应用的一对一消息，"
                        + "群消息走群机器人 webhook，不进这个回调")
                .isTrue();
    }

    @Test
    @DisplayName("先回执再答案：用户在那十几秒里至少知道“有人接了”")
    void theReceiptArrivesBeforeTheAnswer() {
        post(controller(), textXml("msg-2", USER, QUESTION));

        assertThat(sent).containsExactly(USER + "|" + WecomChannel.RECEIPT_REPLY, USER + "|答复");
    }

    @Test
    @DisplayName("同一 MsgId 被重推两次：只回答一次，也只回执一次")
    void theSameMessageIdTwiceIsAnsweredOnce() {
        WecomCallbackController controller = controller();

        post(controller, textXml("msg-3", USER, QUESTION));
        String secondResponse = post(controller, textXml("msg-3", USER, QUESTION));

        assertThat(secondResponse).as("重推也要照常 ACK，否则平台会一直重推").isEmpty();
        assertThat(answers).as("模型只该跑一次：重推不是第二次提问").hasValue(1);
        assertThat(sent).as("回执也不该重复：那会让人以为问了两遍").hasSize(2);
    }

    @Test
    @DisplayName("没登记的人：回“没有权限”，这句话不经过模型、也不花 token，连回执都不发")
    void anUnregisteredMemberIsRefusedWithoutCallingTheModel() {
        post(controller(), textXml("msg-4", "wangwu", QUESTION));

        assertThat(answers).hasValue(0);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).as("拒绝理由要说清是哪个通道、找谁开通").contains("没有权限").contains("企业微信");
    }

    @Test
    @DisplayName("图片语音这类：不猜内容，回一句“打成文字”")
    void anUnsupportedMessageGetsATextRequest() {
        String image = "<xml><ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + USER + "]]></FromUserName>"
                + "<CreateTime>1700000000</CreateTime><MsgType><![CDATA[image]]></MsgType>"
                + "<PicUrl><![CDATA[https://example.invalid/a.png]]></PicUrl><MsgId>msg-5</MsgId></xml>";

        post(controller(), image);

        assertThat(answers).hasValue(0);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("打成文字");
    }

    @Test
    @DisplayName("事件通知（例如成员第一次进入应用）必须静默：它不是提问")
    void anEventNotificationIsIgnoredSilently() {
        String event = "<xml><ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + USER + "]]></FromUserName>"
                + "<CreateTime>1700000000</CreateTime><MsgType><![CDATA[event]]></MsgType>"
                + "<Event><![CDATA[enter_agent]]></Event></xml>";

        String response = post(controller(), event);

        assertThat(response).isEmpty();
        assertThat(sent).as("每点一次应用就挨一句“打成文字”，是最常见的自作聪明").isEmpty();
        assertThat(answers).hasValue(0);
    }

    @Test
    @DisplayName("签名不对的消息：丢弃并回空串，不说话也不花 token")
    void aCallbackWithABadSignatureIsDropped() {
        String encrypted = encrypt(textXml("msg-6", USER, QUESTION));

        String response = controller().receive("not-the-right-signature", TIMESTAMP, NONCE, envelope(encrypted));

        assertThat(response).isEmpty();
        assertThat(sent).isEmpty();
        assertThat(answers).hasValue(0);
    }

    @Test
    @DisplayName("解出来的 receiveid 不是本企业：拒绝。签名对、密钥对，内容却是别人家的")
    void aMessageForAnotherCorpIsDropped() {
        String encrypted = encrypt(textXml("msg-7", USER, QUESTION), "wwsomebody-else");

        String response = post(controller(), encrypted, signatureOf(encrypted));

        assertThat(response).isEmpty();
        assertThat(answers).hasValue(0);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("报文坏了（不是 XML、缺 Encrypt）：回空串不抛，抛出去平台会一直重推")
    void aBrokenEnvelopeIsAcknowledgedWithAnEmptyString() {
        WecomCallbackController controller = controller();

        assertThat(controller.receive("sig", TIMESTAMP, NONCE, "这不是 XML")).isEmpty();
        assertThat(controller.receive("sig", TIMESTAMP, NONCE,
                "<xml><ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName></xml>")).isEmpty();
        assertThat(answers).hasValue(0);
    }

    @Test
    @DisplayName("凭据配错在构造时就失败：位数不对、不是 Base64、token 为空")
    void aMisconfiguredCipherFailsAtStartup() {
        assertThatThrownBy(() -> new WecomCipher("", ENCODING_AES_KEY, CORP_ID))
                .hasMessageContaining("token");
        assertThatThrownBy(() -> new WecomCipher(TOKEN, "too-short", CORP_ID))
                .hasMessageContaining("43 位");
        assertThatThrownBy(() -> new WecomCipher(TOKEN, "!".repeat(43), CORP_ID))
                .hasMessageContaining("不是合法的 Base64");
        assertThatThrownBy(() -> new WecomCipher(TOKEN, ENCODING_AES_KEY, " "))
                .hasMessageContaining("corp-id");
    }

    // ---------------------------------------------------------------- 投递

    @Test
    @DisplayName("主动投递：通道名与库里那一列同口径，地址原样交给回推")
    void handsTheAddressToTheReplySender() {
        WecomOutboundDelivery delivery = new WecomOutboundDelivery(replySender);

        delivery.send(USER, "华东上周成交 128400");

        assertThat(delivery.channel()).isEqualTo("wecom");
        assertThat(sent).containsExactly(USER + "|华东上周成交 128400");
    }

    // ---------------------------------------------------------------- 发送这一侧

    @Test
    @DisplayName("token 只换一次：第二次发消息直接用缓存，不重复换（每天有取用上限）")
    void reusesTheAccessToken() {
        AtomicInteger tokenCalls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        String base = new FakeWecom(tokenCalls, bodies, () -> "{\"errcode\":0,\"errmsg\":\"ok\"}").start();

        WecomReplySender sender = sender(base);
        sender.send(USER, "第一条");
        sender.send(USER, "第二条");

        assertThat(tokenCalls).hasValue(1);
        assertThat(bodies).hasSize(2);
        assertThat(bodies.getFirst()).contains("\"touser\":\"" + USER + "\"")
                .contains("\"msgtype\":\"text\"")
                .contains("\"content\":\"第一条\"")
                .contains("\"agentid\":1000002");
    }

    @Test
    @DisplayName("token 被判过期（42001）：换一张重发一次，用户拿到的是一条消息不是零条")
    void refreshesTheTokenWhenRejected() {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger messageCalls = new AtomicInteger();
        String base = new FakeWecom(tokenCalls, new ArrayList<>(),
                () -> messageCalls.incrementAndGet() == 1
                        ? "{\"errcode\":42001,\"errmsg\":\"access_token expired\"}"
                        : "{\"errcode\":0,\"errmsg\":\"ok\"}").start();

        sender(base).send(USER, "重试一次");

        assertThat(tokenCalls).as("第一次换 + 过期后再换一张").hasValue(2);
        assertThat(messageCalls).as("发一次、失败后重发一次").hasValue(2);
    }

    @Test
    @DisplayName("errcode 不是 0：抛出去，别让“没发出去”在日志里看起来像发成功了")
    void surfacesNonZeroErrcode() {
        String base = new FakeWecom(new AtomicInteger(), new ArrayList<>(),
                () -> "{\"errcode\":81013,\"errmsg\":\"user not found\"}").start();

        assertThatThrownBy(() -> sender(base).send(USER, "发不出去"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("81013");
    }

    // ---------------------------------------------------------------- 装配

    @Test
    @DisplayName("五样配置不齐：一个 bean 都不留，只跑 HTTP 的部署照常起来")
    void credentialsIncompleteLeavesTheChannelOut() {
        runner().withPropertyValues("claw4j.channel.wecom.corp-id=wwtest1234").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WecomCallbackController.class);
            assertThat(context).doesNotHaveBean(WecomReplySender.class);
            assertThat(context).doesNotHaveBean(WecomChannel.class);
        });
    }

    @Test
    @DisplayName("五样齐了：回调端点、通道、投递、发送器都在，通道名与库里那一列一致")
    void configuredChannelRegistersTheCallbackAndTheDelivery() {
        runner().withPropertyValues(configuredProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WecomCallbackController.class);
            assertThat(context).hasSingleBean(WecomChannel.class);
            assertThat(context).hasSingleBean(WecomReplySender.class);
            assertThat(context.getBeansOfType(OutboundDelivery.class).values())
                    .as("投递实现要能按通道名被 ConversationDelivery 找到")
                    .singleElement()
                    .extracting(OutboundDelivery::channel)
                    .isEqualTo("wecom");
        });
    }

    @Test
    @DisplayName("同一个人登记了两个角色：启动失败并点名，而不是按书写顺序挑一个")
    void duplicateRegistrationFailsAtStartup() {
        runner().withPropertyValues(configuredProperties())
                .withPropertyValues(
                        "claw4j.channel.wecom.roles.read-only.users[0]=" + USER,
                        "claw4j.channel.wecom.roles.guest.users[0]=" + USER)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(
                                "claw4j.channel.wecom.roles 里同一个人登记了多个角色：" + USER
                                        + "。一个人只能有一个身份，请删掉多余的那几处。"));
    }

    // ---------------------------------------------------------------- 夹具

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WecomChannelAutoConfiguration.class))
                .withBean(AgentPort.class, () -> agent);
    }

    private static String[] configuredProperties() {
        return new String[]{
                "claw4j.channel.wecom.corp-id=" + CORP_ID,
                "claw4j.channel.wecom.corp-secret=secret",
                "claw4j.channel.wecom.agent-id=1000002",
                "claw4j.channel.wecom.token=" + TOKEN,
                "claw4j.channel.wecom.encoding-aes-key=" + ENCODING_AES_KEY
        };
    }

    private WecomCallbackController controller() {
        InboundDispatcher dispatcher = new InboundDispatcher(agent, Runnable::run,
                userId -> USER.equals(userId) ? Optional.of(PRINCIPAL) : Optional.empty(),
                replySender, "企业微信", WecomOutboundDelivery.CHANNEL, WecomChannel.RECEIPT_REPLY);
        return new WecomCallbackController(
                new WecomCipher(TOKEN, ENCODING_AES_KEY, CORP_ID), dispatcher, replySender);
    }

    private String post(WecomCallbackController controller, String plainXml) {
        String encrypted = encrypt(plainXml);
        return post(controller, encrypted, signatureOf(encrypted));
    }

    private String post(WecomCallbackController controller, String encrypted, String signature) {
        return controller.receive(signature, TIMESTAMP, NONCE, envelope(encrypted));
    }

    private static String envelope(String encrypted) {
        return "<xml><ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName>"
                + "<Encrypt><![CDATA[" + encrypted + "]]></Encrypt>"
                + "<AgentID><![CDATA[1000002]]></AgentID></xml>";
    }

    private static String textXml(String msgId, String user, String content) {
        return "<xml><ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + user + "]]></FromUserName>"
                + "<CreateTime>1700000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + content + "]]></Content>"
                + "<MsgId>" + msgId + "</MsgId>"
                + "<AgentID>1000002</AgentID></xml>";
    }

    // ---- 下面这两个是"另一份实现"，故意不调用生产代码（见类注释） ----

    private static String encrypt(String message) {
        return encrypt(message, CORP_ID);
    }

    private static String encrypt(String message, String receiveId) {
        byte[] key = Base64.getDecoder().decode(ENCODING_AES_KEY + "=");
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        byte[] tail = receiveId.getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[16];
        Arrays.fill(random, (byte) 'Q');
        ByteBuffer buffer = ByteBuffer.allocate(random.length + 4 + payload.length + tail.length);
        buffer.put(random).putInt(payload.length).put(payload).put(tail);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(buffer.array()));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String signatureOf(String encrypted) {
        String[] parts = {TOKEN, TIMESTAMP, NONCE, encrypted};
        Arrays.sort(parts);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (byte value : digest) {
                text.append(String.format("%02x", value));
            }
            return text.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 用 JDK 自带的 HTTP 服务器假扮企业微信：{@code gettoken} 记次数、{@code message/send} 记请求体。
     *
     * <p>不引 MockWebServer 之类的库：这里要验的是「换一次 token、复用、失败重试一次」这三条
     * 关于调用顺序的事，一个三十行的假服务比一个新依赖更容易看清。
     */
    private final class FakeWecom {

        private final AtomicInteger tokenCalls;

        private final List<String> bodies;

        /** 发送接口回什么。做成函数是因为"第一发被 token 过期挡回来"要按调用次数变。 */
        private final Supplier<String> messageResponse;

        private FakeWecom(AtomicInteger tokenCalls, List<String> bodies, Supplier<String> messageResponse) {
            this.tokenCalls = tokenCalls;
            this.bodies = bodies;
            this.messageResponse = messageResponse;
        }

        String start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException failure) {
                throw new IllegalStateException("起不了本地假企业微信", failure);
            }
            server.createContext("/cgi-bin/gettoken", exchange -> {
                tokenCalls.incrementAndGet();
                respond(exchange, "{\"errcode\":0,\"access_token\":\"token-" + tokenCalls.get()
                        + "\",\"expires_in\":7200}");
            });
            server.createContext("/cgi-bin/message/send", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, messageResponse.get());
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private WecomReplySender sender(String apiBase) {
        WecomProperties properties = new WecomProperties();
        properties.setCorpId(CORP_ID);
        properties.setCorpSecret("secret");
        properties.setAgentId(1000002);
        properties.setToken(TOKEN);
        properties.setEncodingAesKey(ENCODING_AES_KEY);
        properties.setApiBase(apiBase);
        return new WecomHttpReplySender(properties);
    }
}
