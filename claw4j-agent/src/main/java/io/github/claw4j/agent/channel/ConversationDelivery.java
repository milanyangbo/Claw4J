package io.github.claw4j.agent.channel;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.audit.MessageReason;
import io.github.claw4j.domain.audit.MessageOutcome;
import io.github.claw4j.domain.port.MessageAudit;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.untrusted.OutboundText;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 一轮的结果怎么回到人手里（ADR-0018，ADR-0027 决定四重写）。
 *
 * <p>只有一件事：把结果推到「这一轮来自的那段对话」。没有登记表，也没有「按 {@code runId} 查这个 run
 * 登记过哪些地址」。
 *
 * <p>不做一对多投递：一条消息只来自一段对话，那段对话的地址就在消息上（{@code channel + chatId}）。
 * 结果回到它开始的地方，不回退到「最近用过的通道」。
 *
 * <p>推与存是两件事：推失败只记一条日志（重试策略属于具体平台，见 {@link OutboundDelivery}）；结果本身
 * 早就交回调用方了（{@code reply} 的返回值，或流式最后一帧），收不到推送的人照样拿得到。这是「投递」
 * 敢失败的前提——它从来不是结果的唯一副本。
 */
public class ConversationDelivery {

    /** 投递指标的前缀。与 {@code claw4j.run.*} 同一条纪律：判据回读，不问措辞。 */
    public static final String METRIC = "claw4j.run.delivery";

    private static final Logger log = LoggerFactory.getLogger(ConversationDelivery.class);

    /** 各通道的推送实现。没有实现推的通道（HTTP）不在里面，它靠轮询。 */
    private final List<OutboundDelivery> deliveries;

    /** 为 null 表示没装 actuator：照常投递，只是没有指标可报。 */
    private final MeterRegistry registry;

    /** 消息审计（ADR-0037 票 04）。默认空实现：没有审计设施时不写消息行。 */
    private final MessageAudit messageAudit;

    /**
     * 投递与投递的计数。
     *
     * @param deliveries 各通道的推送实现，为 null 时按空处理
     * @param registry 指标注册表，为 null 时不记指标
     */
    public ConversationDelivery(List<OutboundDelivery> deliveries, MeterRegistry registry) {
        this(deliveries, registry, MessageAudit.NONE);
    }

    /**
     * 带上消息审计的那一份。
     *
     * @param deliveries 各通道的推送实现，为 null 时按空处理
     * @param registry 指标注册表，为 null 时不记指标
     * @param messageAudit 消息审计，null 时按空实现
     */
    public ConversationDelivery(List<OutboundDelivery> deliveries, MeterRegistry registry,
                                MessageAudit messageAudit) {
        this.deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        this.registry = registry;
        this.messageAudit = messageAudit == null ? MessageAudit.NONE : messageAudit;
    }

    /**
     * 结果出来了，按通知策略送到这段对话（ADR-0027 决定四）。
     *
     * <p>调用方只在「没有人在就地等」的时候调它：同步 HTTP 那一轮的回答已经从请求里返回了，再推一次
     * 就是同一条内容出现两遍。审批续跑与定时任务没有人等，所以它们推。
     *
     * <p>策略在这里生效：{@link NotifyPolicy#SILENT} 一句都不推，但它不等于结果丢了——查得到与推不推
     * 是两件事。
     *
     * @param target 送到哪个通道的哪个会话
     * @param policy 这一轮的通知策略
     * @param text 结果全文。空就不推——「什么都没有」推出去只会让人以为坏了
     */
    public void announce(DeliveryTarget target, NotifyPolicy policy, String text) {
        if (target == null || text == null || text.isBlank()) {
            // 没有内容可推也是一条事实：客户问"为什么没收到"，答案可能是"那轮什么都没产出"。
            auditOutbound(target, MessageOutcome.SUPPRESSED, MessageReason.NO_VISIBLE_PAYLOAD,
                    null, 0);
            return;
        }
        long startedAt = System.nanoTime();
        if (policy != null && !policy.announcesFinish()) {
            count("skipped", "policy", policy.column());
            // 策略说"别推"：这是被抑制，不是失败——没有人期待它出现。
            auditOutbound(target, MessageOutcome.SUPPRESSED, MessageReason.NO_VISIBLE_PAYLOAD,
                    null, elapsedMillis(startedAt));
            return;
        }
        OutboundDelivery delivery = deliveryFor(target.channel());
        if (delivery == null) {
            // 没有实现推的通道（HTTP）：它的结果靠轮询取，这不是失败，是设计。
            // 这一路不写消息行：答案已经随那次请求返回了，写一行"suppressed"是撒谎，
            // 写"sent"又没有投递这件事。缺口记在票 04 里。
            count("polled", "channel", target.channel());
            return;
        }
        try {
            // 主动推的文本同样过一遍出站剥离：定时任务与审批续跑的结果也是模型写的，
            // 也可能复述工具返回（票 02）。
            delivery.send(target.channelRef(), OutboundText.clean(text));
            count("sent", "channel", target.channel());
            auditOutbound(target, MessageOutcome.SENT, null, null, elapsedMillis(startedAt));
        }
        catch (RuntimeException failure) {
            count("failed", "channel", target.channel());
            log.error("投递失败 channel={}（结果仍在库里，可查）", target.channel(), failure);
            auditOutbound(target, MessageOutcome.FAILED, null, "platform_send",
                    elapsedMillis(startedAt));
        }
    }

    /**
     * 记一条「这条出站消息投递结束」。
     *
     * @param target 送到哪里；为 null 时（没有来处）不记
     * @param outcome 结果
     * @param reasonCode 原因码，没有时为 null
     * @param failureStage 失败发生在哪一段，没有失败时为 null
     * @param durationMs 从开始投递算起的毫秒数
     */
    private void auditOutbound(DeliveryTarget target, MessageOutcome outcome, String reasonCode,
                               String failureStage, long durationMs) {
        if (target == null) {
            return;
        }
        try {
            messageAudit.outbound(new MessageAudit.Outbound(null, null, target.channel(),
                    target.channelRef(), target.channelRef(), outcome, reasonCode, failureStage,
                    "text", durationMs, target.direct()));
        } catch (RuntimeException ignored) {
            // 审计缺一行不该让一次投递失败；实现本身也承诺吞异常，这里再兜一层。
            log.debug("写投递审计时出错：channel={}", target.channel());
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private OutboundDelivery deliveryFor(String channel) {
        for (OutboundDelivery delivery : deliveries) {
            if (delivery.channel().equals(channel)) {
                return delivery;
            }
        }
        return null;
    }

    private void count(String suffix) {
        count(suffix, null, null);
    }

    private void count(String suffix, String tagKey, String tagValue) {
        if (registry == null) {
            return;
        }
        Counter.Builder counter = Counter.builder(METRIC + "." + suffix)
                .description("结果投递的计数：" + suffix);
        if (tagKey != null) {
            counter = counter.tag(tagKey, tagValue);
        }
        counter.register(registry).increment();
    }
}
