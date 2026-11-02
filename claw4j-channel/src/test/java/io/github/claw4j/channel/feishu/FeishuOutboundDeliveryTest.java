package io.github.claw4j.channel.feishu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 飞书的主动投递（A05）：一个 chat_id 加一段文本，交给本来就在的回推。
 *
 * <p>它只有三行代码，值得一条用例的地方在于<b>两件事不能被改错</b>：
 * 通道名必须与 {@code agent_run_delivery.channel} 那一列同口径（错了的表现是
 * "登记了地址，但永远没有实现能送"），地址必须原样交给回推（错了会推到一个
 * 拼出来的地址上，而那种错在日志里看着像"发成功了"）。
 */
class FeishuOutboundDeliveryTest {

    @Test
    @DisplayName("通道名与库里那一列同口径，地址原样交给回推")
    void handsTheAddressToTheReplySender() {
        List<String> sent = new ArrayList<>();
        FeishuOutboundDelivery delivery = new FeishuOutboundDelivery(
                (chatId, text) -> sent.add(chatId + "|" + text));

        delivery.send("oc_zhang", "华东上周成交 128400");

        assertEquals("feishu", delivery.channel());
        assertEquals(List.of("oc_zhang|华东上周成交 128400"), sent);
    }
}
