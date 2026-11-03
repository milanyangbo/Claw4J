package io.github.claw4j.channel.slack;

import io.github.claw4j.channel.kernel.InboundEvent;

import java.time.Instant;

/**
 * 一条已经从 Slack 事件回调里解出来的入站消息。
 *
 * <p>形状与前三份相同，差别在两个地方：<b>会话标识不是一个人的 id</b>，
 * 而且<b>答案可能要挂回某条线程</b>。所以这里比前三个多一个字段（{@link #threadTs()}），
 * {@link #conversationId()} 由两者拼出来（见 {@link SlackConversation}）。
 *
 * <table border="1">
 *   <caption>四家的字段对照</caption>
 *   <tr><th>内核字段</th><th>飞书</th><th>企业微信</th><th>钉钉</th><th>Slack</th></tr>
 *   <tr><td>{@link #eventId()}</td><td>{@code event_id}</td><td>{@code MsgId}</td>
 *       <td>{@code msgId}</td><td>外层 {@code event_id}</td></tr>
 *   <tr><td>{@link #conversationId()}</td><td>{@code chat_id}</td>
 *       <td>{@code FromUserName}</td><td>{@code senderStaffId}</td>
 *       <td><b>拼出来的</b>：{@code channel} + {@code |} + {@code thread_ts}</td></tr>
 *   <tr><td>{@link #senderId()}</td><td>{@code open_id}</td><td>{@code FromUserName}</td>
 *       <td>{@code senderStaffId}</td><td>{@code event.user}</td></tr>
 * </table>
 *
 * <p><b>三类消息的 {@code threadTs} 取值不同，这是本接口唯一的判断</b>：
 * 频道里 @ 应用（{@code app_mention}）→ 答案挂在原消息下面，所以 {@code threadTs} 是那条消息的
 * {@code ts}；私聊（{@code message} + {@code channel_type: "im"}）→ 发顶层，{@code threadTs} 为空。
 *
 * <p>{@code eventId} 取自<b>外层</b>的 {@code event_id}（文档写的是"globally unique across all
 * workspaces"），不是内层事件的 {@code event_ts}——后者是时间戳，同一微秒内的两条消息会撞。
 */
public interface SlackInboundEvent extends InboundEvent {

    @Override
    default String conversationId() {
        return SlackConversation.of(channel(), threadTs());
    }

    /** 频道或私聊会话的 id（{@code C…} / {@code D…} / {@code G…}）。 */
    String channel();

    /** 线程根的 {@code ts}；空串表示发顶层。 */
    String threadTs();

    @Override
    String eventId();

    @Override
    String senderId();

    @Override
    String text();

    @Override
    Instant receivedAt();
}
