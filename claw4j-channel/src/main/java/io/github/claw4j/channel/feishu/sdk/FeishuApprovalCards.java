package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import io.github.claw4j.channel.feishu.FeishuApprovalCard;
import io.github.claw4j.channel.feishu.FeishuOutboundDelivery;
import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.port.ApprovalDecider;
import tools.jackson.databind.ObjectMapper;

/**
 * 把审批卡片发进飞书会话（P03）。
 *
 * <p>与 {@link FeishuOpenApiReplySender} 同一个套路：{@code receive_id_type=chat_id}
 * （入站事件与地址表里记的就是它），正文是序列化后的卡片 JSON 而不是纯文本。
 * 飞书消息体始终是一个 JSON 字符串，{@code msg_type=interactive} 时装的就是卡片本身。
 *
 * <p>失败一律抛：调用方 {@code ConversationApprovalGate} 记一条 WARN 后继续，
 * 那一轮已经停住、编号也在对话里，缺的只是这个更好点的按钮。
 */
class FeishuApprovalCards implements ApprovalCards {

    private static final String RECEIVE_ID_TYPE = "chat_id";

    private static final String INTERACTIVE = "interactive";

    private final Client client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    FeishuApprovalCards(String appId, String appSecret) {
        this.client = Client.newBuilder(appId, appSecret).build();
    }

    @Override
    public String channel() {
        return FeishuOutboundDelivery.CHANNEL;
    }

    @Override
    public void announce(String chatId, ApprovalDecider.Pending card) {
        CreateMessageReq request = CreateMessageReq.newBuilder()
                .receiveIdType(RECEIVE_ID_TYPE)
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(chatId)
                        .msgType(INTERACTIVE)
                        .content(objectMapper.writeValueAsString(FeishuApprovalCard.of(card)))
                        .build())
                .build();
        try {
            CreateMessageResp response = client.im().message().create(request);
            if (!response.success()) {
                throw new IllegalStateException("飞书拒绝发送审批卡片：code=" + response.getCode()
                        + " msg=" + response.getMsg() + " chatId=" + chatId);
            }
        } catch (IllegalStateException rejected) {
            throw rejected;
        } catch (Exception failure) {
            throw new IllegalStateException("发送审批卡片失败：chatId=" + chatId + " " + failure.getMessage(),
                    failure);
        }
    }
}
