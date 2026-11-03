package io.github.claw4j.channel.slack;

import io.github.claw4j.domain.port.OutboundDelivery;

import java.util.Objects;

/**
 * Slack 的主动投递（A05，ADR-0018）：把结果推回某条会话。
 *
 * <p>与前三份逐行同构。{@link #CHANNEL} 必须与 {@code agent_run_delivery.channel} 那一列同口径——
 * 写错的表现不是报错，而是"在 Slack 问、回推时找不到实现"：库里登记了一行永远没人能送的地址。
 *
 * <p><b>与另外三个通道有一处实质不同</b>：登记的地址是"频道 + 线程"，
 * 所以几小时后推回来的那条结果会落在<b>当初那次提问的线程下面</b>，而不是给这个人发私聊。
 * 这是第一版的裁剪（发私聊要先 {@code conversations.open} 换一个 {@code D} 开头的会话 id，
 * 那是另一次 API 调用），但落点是对的：同一条线程里能看到"当时问了什么、后来算出了什么"。
 */
public class SlackOutboundDelivery implements OutboundDelivery {

    /** 与 {@code agent_run_delivery.channel} 那一列、与 {@code InboundMessage#channel} 同口径。 */
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
