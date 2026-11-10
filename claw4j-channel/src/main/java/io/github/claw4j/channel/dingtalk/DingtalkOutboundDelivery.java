package io.github.claw4j.channel.dingtalk;

import io.github.claw4j.domain.port.OutboundDelivery;

import java.util.Objects;

/**
 * 钉钉的主动投递（A05，ADR-0018）：把结果推回某个成员。
 *
 * <p>推的能力在 {@link DingtalkReplySender} 里，这一层补的是与通道无关的收件地址。
 * {@link #CHANNEL} 必须与审计里记的通道同口径，写错不报错，表现是"在钉钉问、回推时找不到实现"：
 * 库里登记了一行永远没人能送的地址。
 *
 * <p>钉钉的"在哪儿问"与"往哪儿推"在这一版是同一个标识（企业内 userId），这一层看上去可以省。
 * 不省的理由是定时任务与审批续跑那条路只知道通道名与地址，不知道任何平台细节，
 * 它需要一个实现。
 */
public class DingtalkOutboundDelivery implements OutboundDelivery {

    /** 与 {@code InboundMessage#channel}、与审计里记的通道同口径。 */
    public static final String CHANNEL = "dingtalk";

    private final DingtalkReplySender sender;

    public DingtalkOutboundDelivery(DingtalkReplySender sender) {
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
