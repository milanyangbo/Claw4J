package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.channel.dingtalk.DingtalkReplySender;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 钉钉机器人回调的唯一入口（开发者后台"消息接收地址"里填的就是它）。
 *
 * <p><b>与企业微信那个端点最明显的不同：签名在 header 里。</b>
 * {@code timestamp} 与 {@code sign} 是 HTTP header，报文是<b>明文 JSON</b>；
 * 企业微信那次是 query 里的 {@code msg_signature} 加一个加密 XML。
 * 所以这里没有"先取密文再验签"的顺序问题——先验签，签过了才解析报文。
 *
 * <p><b>失败一律回 200，绝不抛。</b>抛出去是 5xx，钉钉会当成"没收到"继续重推，
 * 而重推的往往是一条同样处理不了的消息：失败 → 重推 → 再失败能跑好几轮，
 * 日志里刷满同一个堆栈。签名错、时间戳过期、报文坏了，都记下来然后回空串——
 * 这几件事重试不会变好（ADR-0009 的既有约定，前两个通道同一条）。
 *
 * <p><b>也别返回 5xx 来表达"我拒绝了"</b>：那条语义在协议里是"你没收到，再发一次"。
 * 拒绝是"收到了，不处理"，两者在成本上差好几轮重复投递。
 *
 * <p>四种报文四种去处（见 {@link DingtalkMessageTranslator}）：单聊文本交给编排、
 * 群聊回一句"私聊我"、非文本回一句"打成文字"、没有身份只记日志。
 * 前两类回话是**在回调线程上直接发**的：它们只有一句话，不涉及模型，
 * 而延迟回的话用户那边已经不知道自己在等什么了。
 */
@RestController
public class DingtalkCallbackController {

    private static final Logger log = LoggerFactory.getLogger(DingtalkCallbackController.class);

    /** 开发者后台"机器人 → 消息接收地址"里填的地址。改这里就要去后台同步改，所以写死并写进 README。 */
    public static final String PATH = "/api/dingtalk/callback";

    /**
     * 回调的应答：空串。协议里的意思是"收到了，不回复"。
     *
     * <p>钉钉支持在响应体里回一条 {@code {"msgtype":"text",...}} 做被动回复，
     * 但那条路要求答案在几秒内就有——一次 Text-to-SQL 十几秒，走不通。
     * 所以这里回空串，答案由 {@link InboundDispatcher} 异步推。
     */
    static final String ACK = "";

    /**
     * 处理不了的消息回什么。
     *
     * <p>不点具体原因（图片／语音／文件／富文本都走这条路）：
     * 说错原因比只说"处理不了"更糟——用户会照着错的原因去改自己的操作。
     */
    static final String CANNOT_HANDLE_REPLY =
            "这条消息我没法处理：把问题直接打成文字发给我就行（例如「上周华东区成交额多少」）。";

    /**
     * 群聊里回什么。
     *
     * <p>第一版只在单聊作答（P12 的裁剪）。<b>必须回一句</b>：静默地把群里的提问
     * 当成没发生，群里的人只会觉得机器人坏了——而实际上它好好的，只是不作答。
     *
     * <p>理由写进文案里，因为这条裁剪用户看得见、也一定会被问"为什么"：
     * 能查哪些数据是按人算的（同一个群里的人权限并不相同），
     * 而第一版的回推是私聊，群里回不了——两件事叠在一起，说清比含糊好。
     */
    static final String GROUP_REPLY =
            "群里我不作答，请私聊我再问一次。能查哪些数据是按人算的，同一个群里的人权限并不相同。";

    private final DingtalkSignature signature;

    private final InboundDispatcher dispatcher;

    private final DingtalkReplySender replySender;

    /** 无状态（只做解析），所以一个实例就够了；不必每来一条消息 new 一个。 */
    private final DingtalkMessageTranslator translator = new DingtalkMessageTranslator();

    public DingtalkCallbackController(DingtalkSignature signature,
                                      InboundDispatcher dispatcher,
                                      DingtalkReplySender replySender) {
        this.signature = Objects.requireNonNull(signature, "signature");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.replySender = Objects.requireNonNull(replySender, "replySender");
    }

    /**
     * 接收消息。返回即 ACK：认人、异步执行、回推全在 {@link InboundDispatcher} 里。
     *
     * @param timestamp header 里的毫秒时间戳，验签用；与当前相差超过 1 小时即判非法
     * @param sign      header 里的签名（Base64）
     */
    @PostMapping(PATH)
    public String receive(@RequestHeader(value = "timestamp", required = false) String timestamp,
                          @RequestHeader(value = "sign", required = false) String sign,
                          @RequestBody(required = false) String body) {
        try {
            // 先验签再解析：报文是明文，没有签名的话这个公网端点等于直接把内容交给模型。
            if (!signature.verify(timestamp, sign)) {
                log.warn("钉钉回调：签名不对或时间戳超出 1 小时窗口，已丢弃。"
                        + "签名对不上查 client-secret，时间戳超窗查这台机器的时钟（timestamp={}）", timestamp);
                return ACK;
            }
            DingtalkMessageTranslator.Parsed parsed = translator.parse(body);
            switch (parsed.kind()) {
                case TEXT -> {
                    log.info("钉钉收到消息：eventId={} user={}", parsed.eventId(), parsed.userId());
                    dispatcher.accept(parsed.event());
                }
                case GROUP -> replyTo(parsed.userId(), GROUP_REPLY);
                case UNSUPPORTED -> replyTo(parsed.userId(), CANNOT_HANDLE_REPLY);
                case NO_IDENTITY -> log.warn(
                        "钉钉回调里没有 senderStaffId：这是“钉钉没给身份”，不是“这个人没登记”。"
                                + "常见原因有两个——机器人还没发布上线（后台里发布之后才返回），"
                                + "或者这条消息来自外部群。eventId={}", parsed.eventId());
                default -> throw new IllegalStateException("未知的报文类型：" + parsed.kind());
            }
        } catch (RuntimeException failure) {
            log.error("钉钉回调处理失败（不抛出：抛出去钉钉会重推一条同样处理不了的消息）", failure);
        }
        return ACK;
    }

    /** 回一句短话。失败只记日志：这条路径上的回话不是答案，丢掉它不该影响别的。 */
    private void replyTo(String userId, String text) {
        if (userId == null || userId.isBlank()) {
            log.warn("要回的话没有收件人，只能记日志");
            return;
        }
        try {
            replySender.send(userId, text);
        } catch (RuntimeException failure) {
            log.error("回推「{}」失败：user={}", text.substring(0, Math.min(12, text.length())), userId, failure);
        }
    }
}
