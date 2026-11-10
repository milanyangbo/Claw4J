package io.github.claw4j.channel.slack;

import io.github.claw4j.domain.port.OutboundDelivery;

import java.util.Objects;

/**
 * Slack 的主动投递（A05，ADR-0018）：把结果推回某条会话。
 *
 * <p>{@link #CHANNEL} 必须与审计里记的通道同口径。写错的表现不是报错，而是
 * "在 Slack 问、回推时找不到实现"：库里登记了一行永远没人能送的地址。
 *
 * <p>登记的地址是"频道 + 线程"，所以几小时后推回来的结果落在当初那次提问的线程下面，
 * 而不是给提问者发私聊。发私聊要先 {@code conversations.open} 换一个 {@code D} 开头的会话 id，
 * 那是另一次 API 调用，第一版不做。落点是"同一条线程里能看到当时问了什么、后来算出了什么"。
 */
public class SlackOutboundDelivery implements OutboundDelivery {

    /** 与 {@code InboundMessage#channel}、与审计里记的通道同口径。 */
    public static final String CHANNEL = "slack";

    private final SlackReplySender sender;

    public SlackOutboundDelivery(SlackReplySender sender) {
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
