package io.github.claw4j.domain.inbound;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条待处理（已落库）的入站事件：{@link InboundEvent} 的五个字段，加上"它在哪个队列"。
 *
 * <p><b>整条落成一列 JSON，不拆成列</b>（见 {@code V7__channel.sql} 的注释）：拆了之后每加一个字段就是一次迁移，
 * 而队列表从来不按内容查询——只按 id、状态、车道、时间。这一层也不读里面的内容，
 * 它只是把同一个值原样取回来。
 *
 * @param queueName      队列名。我们一个通道一份配置，所以它就是通道名
 *                       （{@code feishu} / {@code slack} / {@code wecom} / {@code dingtalk}）。
 *                       留着它当主键的一半，是为了同一个通道将来接第二个账号时不用改主键
 * @param eventId        平台侧的事件标识（重推时不变的那个）
 * @param conversationId 回哪条会话；同时也是串行车道的键
 * @param senderId       发消息的人，认人用
 * @param text           用户说的话
 * @param receivedAt     平台侧收到的时间。**顺序由它给**（同一毫秒再按 eventId 兜底）——
 *                       不取号、不加行锁，代价是"顺序等于到达顺序"，而对入站来说那才是对的
 */
public record QueuedInbound(String queueName,
                            String eventId,
                            String conversationId,
                            String senderId,
                            String text,
                            Instant receivedAt) {

    public QueuedInbound {
        Objects.requireNonNull(queueName, "queueName");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /** 串行车道：同一条会话上同时只有一条在跑。 */
    public String laneKey() {
        return conversationId;
    }
}
