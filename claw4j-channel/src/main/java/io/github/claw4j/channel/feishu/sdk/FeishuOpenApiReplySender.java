package io.github.claw4j.channel.feishu.sdk;

import tools.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import io.github.claw4j.channel.feishu.FeishuReplySender;

import java.util.Map;

/**
 * 用飞书的消息 API 把一段文本发进会话。
 *
 * <p>两个常量值得说明，因为它们写错了不会报"参数错"，只会让消息发不出去或者变成一堆裸花括号：
 * <ul>
 *   <li>{@code receive_id_type=chat_id}：{@link FeishuReplySender#send} 拿到的是入站事件里的
 *       {@code chat_id}。用别的 id 类型（{@code open_id}）会把回答发到机器人跟这个人的单聊里，
 *       而不是他提问的那个群。</li>
 *   <li>{@code msg_type=text} + {@code content={"text":"..."}}：飞书的正文永远是一个字符串化的
 *       JSON，不是纯文本。直接塞原文的话，群里看到的是 JSON 解析失败。</li>
 * </ul>
 *
 * <p>失败一律抛出去（{@link IllegalStateException}）：调度层要能在日志里记下"这一条没发出去"。
 * 吞掉的话，群里没有回答、日志里也没有痕迹——最难查的那种故障。
 */
class FeishuOpenApiReplySender implements FeishuReplySender {

    private static final String RECEIVE_ID_TYPE = "chat_id";

    private static final String TEXT_MESSAGE = "text";

    private final Client client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    FeishuOpenApiReplySender(String appId, String appSecret) {
        this.client = Client.newBuilder(appId, appSecret).build();
    }

    @Override
    public void send(String chatId, String text) {
        CreateMessageReq request = CreateMessageReq.newBuilder()
                .receiveIdType(RECEIVE_ID_TYPE)
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(chatId)
                        .msgType(TEXT_MESSAGE)
                        .content(contentOf(text))
                        .build())
                .build();
        try {
            CreateMessageResp response = client.im().message().create(request);
            if (!response.success()) {
                throw new IllegalStateException("飞书拒绝发送消息：code=" + response.getCode()
                        + " msg=" + response.getMsg() + " chatId=" + chatId);
            }
        } catch (IllegalStateException rejected) {
            throw rejected;
        } catch (Exception failure) {
            throw new IllegalStateException("发送飞书消息失败：chatId=" + chatId + " " + failure.getMessage(),
                    failure);
        }
    }

    /** 把文本包成飞书要的 {@code {"text":"..."}}。走 Jackson 而不是手拼，是为了转义引号换行不用重新发明。 */
    private String contentOf(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of("text", text));
        } catch (RuntimeException impossible) {
            // 一个 Map<String,String> 序列化不出来只可能是 Jackson 自己坏了
            throw new IllegalStateException("拼飞书消息正文失败", impossible);
        }
    }
}
