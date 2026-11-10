package io.github.claw4j.channel.feishu;

import io.github.claw4j.domain.port.OutboundDelivery;

import java.util.Objects;

/**
 * 飞书的主动投递（A05，ADR-0018）：把结果推回某个会话。
 *
 * <p>推送能力本来就有：{@link FeishuReplySender#send} 走 IM 的 {@code CreateMessage}
 * （{@code receive_id_type=chat_id}），与有没有请求在等无关。缺的是与通道无关的收件地址，
 * 那一半落在库里，本类只做「给一个 {@code chat_id}，把这段话送过去」。
 *
 * <p>失败照 {@link FeishuReplySender} 的约定：抛，不吞。吞掉的表现是群里没有回答、
 * 日志里也没有痕迹，与根本没跑一样。要不要重试由投递那一段决定，
 * 重试策略取决于飞书自己的限流规则，属于通道，不属于投递。
 */
public class FeishuOutboundDelivery implements OutboundDelivery {

    /** 与审计里记的通道同口径。 */
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
