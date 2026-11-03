package io.github.claw4j.channel.slack.client;

import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.channel.slack.SlackReplySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Slack 事件回调的唯一入口（App 后台 Event Subscriptions → Request URL 里填的就是它）。
 *
 * <p><b>与前面三个端点最明显的不同：3 秒是硬限，而且超时的代价不只是重推。</b>
 * 钉钉、企微超时只是多推几次；Slack 在 60 分钟内失败率超过 95% 时会把这个应用的
 * <b>整个事件订阅临时禁用</b>，并给应用所有者发邮件。所以这个端点里只做三件很轻的事：
 * 验签（一次 HMAC）、解析（一次 JSON）、把事件交给 {@link InboundDispatcher}（它返回即 ACK）。
 *
 * <p><b>验签必须用原始报文</b>：Slack 的签名覆盖 {@code v0:timestamp:body} 整串。
 * 这就是方法签名里 {@code @RequestBody String body} 的理由——收成一个 DTO 或 {@code Map}
 * 再序列化回去，字段顺序与转义都会变，签名永远对不上，而症状看起来像密钥配错。
 *
 * <p><b>握手（{@code url_verification}）也排在验签之后</b>，没有给它开后门。给它的诱惑很实在：
 * 配置阶段 secret 还没填对时，跳过验签就能"先把地址存下来"。但那扇门在运行期同样开着——
 * 一个不验签就回 challenge 的端点，等于向所有人证明"这个地址是活的"。
 * secret 错时后台显示"URL 验证失败"是正确行为：它说明这个通道根本没配好。
 *
 * <p><b>失败一律回 200，绝不抛。</b>抛出去是非 2xx，Slack 会算作一次投递失败——重推一条
 * 同样处理不了的报文，还往那条 95% 的线上记一笔。签名错、时间戳过期、报文坏了都记下来然后回空串：
 * 这几件事重试不会变好（ADR-0009 的既有约定，前三个通道同一条）。
 *
 * <p><b>{@code X-Slack-Retry-Num} 只记日志，不用来决定"跳过"。</b>这是这段代码里最反直觉的一处：
 * 看见"这是第 2 次投递"就想 return 的写法很自然，但第一次若是我们自己慢了没处理成，
 * 跳过就等于把唯一一次机会丢了。去重交给 {@link InboundDispatcher} 的 {@code event_id} 表——
 * 它知道"这个事件处理过没有"，而"这是第几次投递"只说明 Slack 没收到应答。
 */
@RestController
public class SlackCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SlackCallbackController.class);

    /** App 后台 Event Subscriptions 里填的地址。改这里就要去后台同步改，所以写死并写进 README。 */
    public static final String PATH = "/api/slack/events";

    /**
     * 回调的应答：空串。协议里的意思只是"收到了"，Slack 不看内容只看状态码。
     *
     * <p>不在响应体里回答案：Slack 的应答窗口是 3 秒，而一次 Text-to-SQL 十几秒。
     * 答案由 {@link InboundDispatcher} 异步推。
     */
    static final String ACK = "";

    /**
     * 只 @ 了应用、没写问题时回什么。
     *
     * <p>这种情况必须回一句：用户点了 @ 却什么也没说，可能是手滑，也可能是想在群里先 @ 一下
     * 看看机器人在不在。静默处理的话，两种人都会以为它坏了。
     *
     * <p>这句话<b>不进模型</b>——空提问进模型会得到一段对着空气编的回答，还花一次 token。
     */
    static final String EMPTY_QUESTION_REPLY =
            "有什么要问的？直接打字发给我就行（例如「上周华东区成交额多少」）。";

    private final SlackSignature signature;

    private final InboundDispatcher dispatcher;

    private final SlackReplySender replySender;

    /** 无状态（只做解析），所以一个实例就够了；不必每来一条消息 new 一个。 */
    private final SlackMessageTranslator translator = new SlackMessageTranslator();

    public SlackCallbackController(SlackSignature signature,
                                   InboundDispatcher dispatcher,
                                   SlackReplySender replySender) {
        this.signature = Objects.requireNonNull(signature, "signature");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.replySender = Objects.requireNonNull(replySender, "replySender");
    }

    /**
     * 接收事件。返回即 ACK：认人、异步执行、回推全在 {@link InboundDispatcher} 里。
     *
     * @param timestamp header 里的 {@code X-Slack-Request-Timestamp}（秒），验签用；
     *                  与当前相差超过 5 分钟即判为重放
     * @param signatureHeader header 里的 {@code X-Slack-Signature}（{@code v0=...}）
     * @param retryNum header 里的 {@code X-Slack-Retry-Num}，Slack 重投时才有；只记日志
     * @param body      <b>原始</b>报文，未反序列化——签名覆盖它
     */
    @PostMapping(PATH)
    public String receive(
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signatureHeader,
            @RequestHeader(value = "X-Slack-Retry-Num", required = false) String retryNum,
            @RequestBody(required = false) String body) {
        try {
            // 先验签再解析：报文是明文的，没有签名的话这个公网端点等于直接把内容交给模型。
            if (!signature.verify(timestamp, signatureHeader, body)) {
                log.warn("Slack 回调：签名不对或时间戳超出 5 分钟窗口，已丢弃。"
                        + "签名对不上查 signing-secret（不是 bot-token），超窗查这台机器的时钟（timestamp={}）",
                        timestamp);
                return ACK;
            }
            if (retryNum != null && !retryNum.isBlank()) {
                log.info("Slack 重投这条事件（第 {} 次）。去重由 event_id 负责，这里不跳过——"
                        + "第一次若是我们慢得没处理成，跳过就把唯一一次机会丢了", retryNum);
            }
            SlackMessageTranslator.Parsed parsed = translator.parse(body);
            switch (parsed.kind()) {
                case HANDSHAKE -> {
                    log.info("Slack 校验请求地址：回 challenge（地址已经能在后台存下来了）");
                    return parsed.challenge();
                }
                case TEXT -> {
                    log.info("Slack 收到消息：eventId={} user={} channel={}",
                            parsed.event().eventId(), parsed.event().senderId(), parsed.event().channel());
                    dispatcher.accept(parsed.event());
                }
                case EMPTY_QUESTION -> replyTo(parsed.event().conversationId(), EMPTY_QUESTION_REPLY);
                case IGNORED -> log.info("Slack 回调不处理：{}", parsed.reason());
                default -> throw new IllegalStateException("未知的报文类型：" + parsed.kind());
            }
        } catch (RuntimeException failure) {
            log.error("Slack 回调处理失败（不抛出：抛出去会被算作一次投递失败，"
                    + "把事件订阅往“临时禁用”那条线上推）", failure);
        }
        return ACK;
    }

    /** 回一句短话。失败只记日志：这条路径上的回话不是答案，丢掉它不该影响别的。 */
    private void replyTo(String conversationId, String text) {
        if (conversationId == null || conversationId.isBlank()) {
            log.warn("要回的话没有收件人，只能记日志");
            return;
        }
        try {
            replySender.send(conversationId, text);
        } catch (RuntimeException failure) {
            log.error("回推「{}」失败：conversationId={}",
                    text.substring(0, Math.min(12, text.length())), conversationId, failure);
        }
    }
}
