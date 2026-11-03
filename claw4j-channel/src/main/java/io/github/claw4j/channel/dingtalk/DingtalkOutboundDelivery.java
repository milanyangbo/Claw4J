package io.github.claw4j.channel.dingtalk;

import io.github.claw4j.domain.port.OutboundDelivery;

import java.util.Objects;

/**
 * 钉钉的主动投递（A05，ADR-0018）：把结果推回某个成员。
 *
 * <p>与前两份逐行同构，因为要回答的事本来就一样：推的能力早就在 {@link DingtalkReplySender} 里，
 * 缺的只是一个与通道无关的收件地址。
 *
 * <p><b>它存在的意义全在那一个常量上</b>：{@link #CHANNEL} 必须与
 * {@code agent_run_delivery.channel} 那一列同口径。写错的表现不是报错，
 * 而是"在钉钉问、回推时找不到实现"——库里登记了一行永远没人能送的地址。
 *
 * <p>与另外两个通道还有一个不同：钉钉的"在哪儿问"与"往哪儿推"在这一版里是同一个标识
 * （企业内 userId）。看上去这一层可以省掉，但省掉的代价是"从定时任务/审批续跑里推回来"
 * 这条路少一个实现——那条路只知道通道名与地址，不知道任何平台的细节。
 */
public class DingtalkOutboundDelivery implements OutboundDelivery {

    /** 与 {@code agent_run_delivery.channel} 那一列、与 {@code InboundMessage#channel} 同口径。 */
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
