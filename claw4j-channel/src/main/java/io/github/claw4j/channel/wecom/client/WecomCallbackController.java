package io.github.claw4j.channel.wecom.client;

import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.channel.wecom.WecomReplySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * 企业微信回调的两个入口：URL 验证与消息接收，后台「接收消息」里填的就是这个地址。
 *
 * <p>一定要回 200 与空串。企业微信五秒内拿不到响应就重推，重推的还是同一条；而回调里只做
 * 验签、解密、交给入站编排，编排第一步就是提交到线程池，返回时模型还没开始跑。空串在协议里
 * 的意思是「收到了，不回复」：被动回复要在五秒内现加密一段 XML，而答案可能十几秒后才算得出来，
 * 那条路走不通。
 *
 * <p>这里绝不抛异常。抛出去是 500，企业微信当成没收到继续重推，而重推的往往是同一条处理不了的
 * 消息，日志里刷满同一个堆栈。签名错、解密失败、报文坏了都记下来然后回空串，
 * 这几件事重试不会变好（ADR-0009 的既有约定）。
 *
 * <p>三种报文三种去处，混成两种就会出错（见 {@link WecomMessageTranslator}）：
 * 文本交给编排、事件通知静默、图片语音这类回一句「打成文字」。
 */
@RestController
public class WecomCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WecomCallbackController.class);

    /** 后台"接收消息 → URL"里填的地址。改这里就要去后台同步改，所以写死并写进 README。 */
    public static final String PATH = "/api/wecom/callback";

    /**
     * 回调的应答：空串，协议里的意思是「收到了，不回复」。
     *
     * <p>返回 {@code "success"} 之类也行，但空串最不容易被误读——写成一句人话，
     * 早晚有人以为它会显示给用户。
     */
    static final String ACK = "";

    /**
     * 处理不了的消息回什么。
     *
     * <p>与飞书那句同一个理由：不点具体原因（图片、语音、位置都走这条路），
     * 说错原因比只说处理不了更糟——用户会照着错的原因去改自己的操作。
     */
    static final String CANNOT_HANDLE_REPLY =
            "这条消息我没法处理：把问题直接打成文字发给我就行（例如「上周华东区成交额多少」）。";

    private final WecomCipher cipher;

    private final InboundDispatcher dispatcher;

    private final WecomReplySender replySender;

    /** 无状态（只做解析），所以一个实例就够了；不必每来一条消息 new 一个。 */
    private final WecomMessageTranslator translator = new WecomMessageTranslator();

    public WecomCallbackController(WecomCipher cipher,
                                   InboundDispatcher dispatcher,
                                   WecomReplySender replySender) {
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.replySender = Objects.requireNonNull(replySender, "replySender");
    }

    /**
     * URL 验证：把解密后的 {@code echostr} 原样回给平台，后台才会显示验证通过。
     *
     * <p>回的是<b>明文</b>，不是解密前那一串。回错了的表现是后台保存配置时提示验证失败，
     * 而这里只留下一行签名不对——平台从不明说它是拿明文比对的。
     *
     * @param signature 平台带来的 {@code msg_signature}，允许缺失（缺失即验签失败）
     * @param timestamp 平台带来的时间戳
     * @param nonce 平台带来的随机串
     * @param echostr 加密后的验证串
     * @return 解密后的 {@code echostr}；验签或解密失败时返回空串
     */
    @GetMapping(PATH)
    public String verify(@RequestParam(value = "msg_signature", required = false) String signature,
                         @RequestParam(value = "timestamp", required = false) String timestamp,
                         @RequestParam(value = "nonce", required = false) String nonce,
                         @RequestParam(value = "echostr", required = false) String echostr) {
        if (!cipher.verify(signature, timestamp, nonce, echostr)) {
            log.warn("企业微信 URL 验证：签名不对，已拒绝。检查 claw4j.channel.wecom.token 与后台是否一致");
            return ACK;
        }
        try {
            return cipher.decrypt(echostr);
        } catch (RuntimeException failure) {
            log.warn("企业微信 URL 验证：echostr 解不开。检查 encoding-aes-key 与 corp-id", failure);
            return ACK;
        }
    }

    /**
     * 接收消息。返回即 ACK：认人、异步执行、回推全在 {@link InboundDispatcher} 里。
     *
     * @param signature 平台带来的 {@code msg_signature}，覆盖的是密文
     * @param timestamp 平台带来的时间戳
     * @param nonce 平台带来的随机串
     * @param body 回调报文，形如只含 {@code Encrypt} 的 XML
     * @return 恒为空串
     */
    @PostMapping(PATH)
    public String receive(@RequestParam(value = "msg_signature", required = false) String signature,
                          @RequestParam(value = "timestamp", required = false) String timestamp,
                          @RequestParam(value = "nonce", required = false) String nonce,
                          @RequestBody(required = false) String body) {
        try {
            String encrypted = encryptedOf(body);
            // 签名要覆盖密文，所以顺序只能是"先取出 Encrypt 再验签"
            if (!cipher.verify(signature, timestamp, nonce, encrypted)) {
                log.warn("企业微信回调：签名不对，已丢弃（检查 token 与后台是否一致）");
                return ACK;
            }
            WecomMessageTranslator.Parsed parsed = translator.parse(cipher.decrypt(encrypted));
            switch (parsed.kind()) {
                case EVENT -> log.info("企业微信事件通知（不需要回答）：user={}", parsed.userId());
                case UNSUPPORTED -> replyCannotHandle(parsed.userId());
                case TEXT -> {
                    log.info("企业微信收到消息：eventId={} user={}",
                            parsed.event().eventId(), parsed.userId());
                    dispatcher.accept(parsed.event());
                }
                default -> throw new IllegalStateException("未知的报文类型：" + parsed.kind());
            }
        } catch (RuntimeException failure) {
            log.error("企业微信回调处理失败（不抛出：抛出去平台会重推一条同样处理不了的消息）", failure);
        }
        return ACK;
    }

    private static String encryptedOf(String body) {
        Map<String, String> envelope = WecomXml.childrenOf(body);
        String encrypted = envelope.get("Encrypt");
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalStateException("回调报文里没有 Encrypt 字段（后台接收消息配成了明文模式？）");
        }
        return encrypted;
    }

    /** 翻不出来的消息也要回一句：对话里的沉默分不清「没收到」和「不会」。 */
    private void replyCannotHandle(String userId) {
        if (userId == null || userId.isBlank()) {
            log.warn("处理不了的企业微信消息里连发送者都没有，只能记日志");
            return;
        }
        try {
            replySender.send(userId, CANNOT_HANDLE_REPLY);
        } catch (RuntimeException failure) {
            log.error("回推「处理不了」失败：user={}", userId, failure);
        }
    }
}
