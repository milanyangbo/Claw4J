package io.github.claw4j.channel.dingtalk;

import io.github.claw4j.channel.kernel.InboundEvent;

import java.time.Instant;

/**
 * 一条已从钉钉机器人回调里解出来的入站消息。
 *
 * <p>字段来源：{@code eventId} 取 {@code msgId}，{@code conversationId} 与 {@code senderId}
 * 都取 {@code senderStaffId}，{@code text} 取 {@code text.content}，{@code receivedAt} 由
 * {@code createAt} 换算。
 *
 * <p>这条接口只代表单聊。钉钉有群聊（{@code conversationType=2}），而群聊的"回哪儿"
 * 不是一个人的 id，是一个 {@code openConversationId}，第一版不做，收到群消息时通道回一句
 * "私聊我"就结束（见 {@code DingtalkCallbackController}）。所以这里可以放心让会话标识与
 * 发送者是同一个值：走到这条路上的消息本来就是一对一的。
 *
 * <p>去重按 {@code msgId}，它是重推时应当不变的那个值。钉钉文档没有为它的唯一性给出承诺，
 * 真出现"重推换了 msgId"，去重会失效，同一个问题会被回答两次。
 */
public interface DingtalkInboundEvent extends InboundEvent {

    /** 会话标识：单聊里就是发消息的人。 */
    @Override
    default String conversationId() {
        return userId();
    }

    @Override
    String eventId();

    @Override
    String senderId();

    /** 发消息的人的企业内 userId（钉钉回调里叫 {@code senderStaffId}）。 */
    String userId();

    @Override
    String text();

    @Override
    Instant receivedAt();
}
