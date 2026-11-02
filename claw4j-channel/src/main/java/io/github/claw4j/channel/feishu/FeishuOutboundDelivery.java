package io.github.claw4j.channel.feishu;

import io.github.claw4j.domain.port.OutboundDelivery;

import java.util.Objects;

/**
 * 飞书的主动投递（A05，ADR-0018）：把结果推回某个会话。
 *
 * <p><b>这里几乎没有代码，那是刻意的</b>：推的能力早就有了——
 * {@link FeishuReplySender#send} 走的是 IM 的 {@code CreateMessage}
 * （{@code receive_id_type=chat_id}），与"当前有没有一个请求在等着"毫无关系。
 * 缺的从来不是它，缺的是一个与通道无关的收件地址。地址那一半落在库里，
 * 本类只负责"给一个 {@code chat_id}，把这段话送过去"。
 *
 * <p><b>失败约定照 {@link FeishuReplySender} 的原样：抛，不吞。</b>
 * 那一层的 javadoc 已经写清为什么不能吞——吞掉的表现是群里没有回答、
 * 日志里也没有痕迹，与"根本没跑"一模一样。要不要重试由投递那一段决定：
 * 重试策略取决于飞书自己的限流规则，它属于通道，不属于投递。
 */
public class FeishuOutboundDelivery implements OutboundDelivery {

    /** 与 {@code agent_run_delivery.channel} 那一列同口径。 */
    public static final String CHANNEL = "feishu";

    private final FeishuReplySender sender;

    public FeishuOutboundDelivery(FeishuReplySender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public void send(String channelRef, String text) {
        sender.send(channelRef, text);
    }
}
