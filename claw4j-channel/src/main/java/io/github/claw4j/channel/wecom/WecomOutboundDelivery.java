package io.github.claw4j.channel.wecom;

import io.github.claw4j.domain.port.OutboundDelivery;

import java.util.Objects;

/**
 * 企业微信的主动投递（A05，ADR-0018）：把结果推回某个成员。
 *
 * <p>与 {@link io.github.claw4j.channel.feishu.FeishuOutboundDelivery 飞书那一份}逐行同构，
 * 因为两件事本来就一样：推的能力早就在 {@link WecomReplySender} 里，
 * 缺的只是一个与通道无关的收件地址。本类只负责"给一个 UserID，把这段话送过去"。
 *
 * <p>它存在的意义全在那一个常量上：{@link #CHANNEL} 必须与
 * 审计里记的通道同口径。写错的表现不是报错，
 * 而是"在飞书问、企微收"这条路上，库里登记了一行永远没有实现能送的地址。
 */
public class WecomOutboundDelivery implements OutboundDelivery {

    /** 与 {@code InboundMessage#channel}、与审计里记的通道同口径。 */
    public static final String CHANNEL = "wecom";

    private final WecomReplySender sender;

    public WecomOutboundDelivery(WecomReplySender sender) {
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
