package io.github.claw4j.agent.channel;

/**
 * 一个收件地址：这件事的结果要回到"哪个通道的哪个会话"（ADR-0018）。
 *
 * <p><b>它不是回调地址</b>。回调地址是单点，还要能被外网访问到，
 * 于是"在飞书问、在企微收"就得先给企微那边开一个入口。这里记的是
 * "回到那个会话去"——一件已经存在的会话，不需要新开任何入口。
 *
 * <p><b>一对多</b>：同一件事可以登记多个地址。跨通道收结果的意思是
 * "这件事的结果在两个地方都能被找到"，不是"把它从 A 挪到 B"。
 *
 * @param channel     通道标识，与 {@code OutboundDelivery#channel()} 同口径
 * @param channelRef  通道内的地址（飞书是 {@code chat_id}）
 */
public record DeliveryTarget(String channel, String channelRef) {

    public DeliveryTarget {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("收件地址不能没有通道名：没有通道名就不知道该交给谁去送");
        }
        if (channelRef == null || channelRef.isBlank()) {
            throw new IllegalArgumentException("收件地址不能没有通道内的地址");
        }
    }
}
