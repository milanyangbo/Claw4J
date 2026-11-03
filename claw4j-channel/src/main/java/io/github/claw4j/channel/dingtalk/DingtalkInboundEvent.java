package io.github.claw4j.channel.dingtalk;

import io.github.claw4j.channel.kernel.InboundEvent;

import java.time.Instant;

/**
 * 一条已经从钉钉机器人回调里解出来的入站消息。
 *
 * <p>形状与前两份（飞书、企业微信）相同，差别只在字段的来源：
 *
 * <table border="1">
 *   <caption>三家的字段对照</caption>
 *   <tr><th>内核字段</th><th>飞书</th><th>企业微信</th><th>钉钉</th></tr>
 *   <tr><td>{@link #eventId()}</td><td>{@code event_id}</td><td>{@code MsgId}</td>
 *       <td>{@code msgId}</td></tr>
 *   <tr><td>{@link #conversationId()}</td><td>{@code chat_id}</td>
 *       <td>{@code FromUserName}</td><td>{@code senderStaffId}</td></tr>
 *   <tr><td>{@link #senderId()}</td><td>{@code open_id}</td>
 *       <td>{@code FromUserName}</td><td>{@code senderStaffId}</td></tr>
 * </table>
 *
 * <p><b>这条接口只代表单聊</b>。钉钉有群聊（{@code conversationType=2}），而群聊的
 * "回哪儿"不是一个人的 id，是一个 {@code openConversationId}——那件事第一版不做，
 * 收到群消息时通道回一句"私聊我"就结束（见 {@code DingtalkCallbackController}）。
 * 所以这里可以放心让会话标识与发送者是同一个值，与企业微信同一个理由：
 * 走到这条路上的消息本来就是一对一的。
 *
 * <p>{@code msgId} 是重推时<b>应该</b>不变的那个值，去重按它。官方文档对它的说明是
 * "暂无使用场景，可忽略"——也就是说它的稳定性没有任何承诺。真出现"重推换了 msgId"，
 * 去重会失效（同一个问题会被回答两次），这一点写在 P12 的 Comments 里，不假装验过。
 */
public interface DingtalkInboundEvent extends InboundEvent {

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
