package io.github.claw4j.channel.slack;

import io.github.claw4j.channel.kernel.InboundEvent;

import java.time.Instant;

/**
 * 一条已从 Slack 事件回调里解出来的入站消息。
 *
 * <p>与其他通道的区别在会话标识：它不是一个用户，而是"频道 + 线程根"拼出的串
 * （见 {@link SlackConversation}），因此比 {@link InboundEvent} 多一个 {@link #threadTs()}。
 *
 * <p>{@code threadTs} 决定答案落在哪儿：频道里 @ 应用（{@code app_mention}）取原消息的
 * {@code ts}，答案挂在它下面；私聊（{@code message} 加 {@code channel_type: "im"}）取空串，发顶层。
 *
 * <p>{@code eventId} 取自外层 {@code event_id}，不是内层事件的 {@code event_ts}——
 * 后者是时间戳，同一微秒内的两条消息会撞。去重按 {@code eventId}，撞了就是重复回答。
 *
 * <p>其余字段：{@code channel} 取 {@code event.channel}，{@code senderId} 取 {@code event.user}。
 */
public interface SlackInboundEvent extends InboundEvent {

    /** 会话标识：{@link SlackConversation#of} 拼出的"频道|线程根"，没有线程根时只有频道。 */
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
