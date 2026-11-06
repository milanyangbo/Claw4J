package io.github.claw4j.agent.channel;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 一轮的结果怎么回到人手里（ADR-0018，ADR-0027 决定四重写）。
 *
 * <p><b>只有一件事：把结果推到"这一轮来自的那段对话"</b>。没有登记表，没有"按 runId
 * 查这个 run 登记过哪些地址"。
 *
 * <p><b>不做一对多投递</b>：一条消息只来自一段对话，而那段对话的地址就在消息上
 * （{@code channel + chatId}）。OpenClaw 的原文是 <b>"never falls back to lastChannel"</b>——
 * 结果回到它开始的地方，不回退到"最近用过的通道"。
 *
 * <p><b>推与存是两件事</b>：推失败只记一条日志（重试策略属于具体平台，见
 * {@link OutboundDelivery}）；结果本身早就交回调用方了（{@code reply} 的返回值、
 * 或流式最后一帧），收不到推送的人照样拿得到。这是"投递"敢失败的前提——
 * 它从来不是结果的唯一副本。
 */
public class ConversationDelivery {

    /** 投递指标的前缀。与 {@code claw4j.run.*} 同一条纪律：判据回读，不问措辞。 */
    public static final String METRIC = "claw4j.run.delivery";

    private static final Logger log = LoggerFactory.getLogger(ConversationDelivery.class);

    /** 各通道的推送实现。没有实现推的通道（HTTP）不在里面，它靠轮询。 */
    private final List<OutboundDelivery> deliveries;

    /** 为 null 表示没装 actuator：照常投递，只是没有指标可报。 */
    private final MeterRegistry registry;

    public ConversationDelivery(List<OutboundDelivery> deliveries, MeterRegistry registry) {
        this.deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        this.registry = registry;
    }

    /**
     * 结果出来了，按通知策略送到这段对话（ADR-0027 决定四）。
     *
     * <p><b>调用方只在"没有人在就地等"的时候调它</b>：同步 HTTP 那一轮的回答已经从请求里
     * 返回了，再推一次就是同一条内容出现两遍。审批续跑与定时任务没有人等，所以它们推。
     *
     * <p><b>策略在这儿生效</b>：{@link NotifyPolicy#SILENT} 一句都不推——它不等于结果丢了，
     * 查得到与推不推是两件事。
     *
     * @param target 送到哪个通道的哪个会话
     * @param policy 这一轮的通知策略
     * @param text   结果全文。空就不推——"什么都没有"推出去只会让人以为坏了
     */
    public void announce(DeliveryTarget target, NotifyPolicy policy, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        if (policy != null && !policy.announcesFinish()) {
            count("skipped", "policy", policy.column());
            return;
        }
        OutboundDelivery delivery = deliveryFor(target.channel());
        if (delivery == null) {
            // 没有实现推的通道（HTTP）：它的结果靠轮询取，这不是失败，是设计。
            count("polled", "channel", target.channel());
            return;
        }
        try {
            delivery.send(target.channelRef(), text);
            count("sent", "channel", target.channel());
        }
        catch (RuntimeException failure) {
            count("failed", "channel", target.channel());
            log.error("投递失败 channel={}（结果仍在库里，可查）", target.channel(), failure);
        }
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
