package io.github.claw4j.app;

import io.github.claw4j.agent.channel.ConversationDelivery;
import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.OutboundDelivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果送到哪（A05，ADR-0018）。
 *
 * <p>曾经与"这一轮花了多少"合成一份，因为两件事的 setup 都是同一套真库容器。2026-11-25 之后
 * 额度那一半随目标下线（ADR-0025 修订：额度是 goal 的属性，goal 没有了，额度无处可挂），
 * 只剩投递这一半——它不需要库，地址是人给的参数。
 *
 * <p>推送那一头用假的：{@link OutboundDelivery} 的实现属于各通道，由各通道自己的测试管。
 */
class TurnFlowTest {

    @Test
    @DisplayName("结果推回它开始的那段对话：没有登记表，也不回退到「最近用过的通道」")
    void theResultGoesBackToTheConversationItStartedIn() {
        Recording feishu = new Recording("feishu");
        ConversationDelivery delivery = delivery(feishu);

        delivery.announce(new DeliveryTarget("feishu", "oc_zhang"), NotifyPolicy.DONE_ONLY, "口径对完了");
        // 没有实现推的通道（HTTP）不在 deliveries 里：它靠轮询，这不是失败
        delivery.announce(new DeliveryTarget("http", "http:u-1"), NotifyPolicy.DONE_ONLY, "口径对完了");

        assertThat(feishu.sent)
                .as("只推给它自己那段对话（ADR-0027 决定四）")
                .containsExactly("oc_zhang|口径对完了");
    }

    @Test
    @DisplayName("silent 一句都不推：定时任务每跑一趟推一条，等于把自动化做成骚扰")
    void silentSaysNothing() {
        Recording deliveryFeishu = new Recording("feishu");
        ConversationDelivery delivery = delivery(deliveryFeishu);

        delivery.announce(new DeliveryTarget("feishu", "oc_zhang"), NotifyPolicy.SILENT,
                "这周没有对不上的地方");

        assertThat(deliveryFeishu.sent).isEmpty();
    }

    private ConversationDelivery delivery(OutboundDelivery... channels) {
        // 没有 registry 时照常投递，只是没有指标——能力不在场，不是失败。
        return new ConversationDelivery(List.of(channels), null);
    }

    private static final class Recording implements OutboundDelivery {

        private final String channel;

        private final List<String> sent = new ArrayList<>();

        Recording(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public void send(String channelRef, String text) {
            sent.add(channelRef + "|" + text);
        }
    }
}
