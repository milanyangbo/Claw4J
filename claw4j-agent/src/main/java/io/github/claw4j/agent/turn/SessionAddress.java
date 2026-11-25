package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionScopeProperties;
import io.github.claw4j.domain.port.InboundMessage;

/**
 * 这一轮属于哪段对话：一个键，一处算法。
 *
 * <p>2026-11-25 起只有一个键（ADR-0026 修订）。此前是两把——含段的 {@code sessionId} 与不含段的
 * {@code conversationKey}，因为段会换而节点不变。段整体下线之后两者同值，留两个名字只会让调用方
 * 猜"这次该用哪个"。
 *
 * <p>键的形状仍由 {@code dmScope} 决定（{@link SessionScopeProperties}）：私聊默认落 {@code main}，
 * 于是同一个人所有私聊算同一条会话；要按通道分开就配成 {@code per-channel-peer}。这一层
 * 与"有没有段"无关。
 *
 * <p>抽成一处是因为 {@code reply} 与 {@code stream} 两条路各要算一次，而它们必须算出同一个 id——
 * 一条消息走流式、下一条走非流式是常态，两处各写一遍的话，"什么算同一条对话"就有了两个答案。
 */
final class SessionAddress {

    /**
     * 键的分段规则。可以为 null：没装配会话层时退回两侧默认值。
     *
     * <p>不存 {@code SessionService}：这里只算字符串，不碰存储。会话什么时候建、历史怎么读，
     * 都是 advisor 与调用方的事。
     */
    private final SessionScopeProperties scopes;

    /**
     * @param scopes 键的分段规则，可以为 null（按两侧默认值）
     */
    SessionAddress(SessionScopeProperties scopes) {
        this.scopes = scopes;
    }

    /**
     * 这一轮的会话 id。
     *
     * <p>调用方直接给了整段键就用它：两条路走这里——审批批准之后回到发起请求的那一段
     * （ADR-0027），以及定时任务（每次触发自带一个一次性的键）。不重新拼，是因为那个键代表
     * "这一轮仍属于发起时那一段"，重拼会算出另一个。
     *
     * @param message 入站消息，可能自带整段会话键
     * @param owner 归属键，必须来自 {@link SessionKeys#ownerOf}
     * @return 这一轮用的 sessionId
     */
    String sessionIdOf(InboundMessage message, String owner) {
        if (message.sessionId() != null && !message.sessionId().isBlank()) {
            return message.sessionId();
        }
        return conversationKeyOf(message, owner);
    }

    /**
     * 这段对话的键。会话 id 就是它，不再有后缀。
     *
     * <p><b>这是键形状的唯一一处定义。</b>含段时代它要再拼一次段，两边各写一份的话，会出现
     * "记忆落在 {@code ...:main:<段>}、目标落在 {@code ...:<channel>:<chatId>}"，两个节点都不是对方，
     * 而报错一个也没有。现在没有段，这个分叉从形状上消失了——仍然只留一处，是为了下次加东西时
     * 不会重新长出第二份。
     *
     * @param message 入站消息
     * @param owner 归属键
     * @return 会话键
     */
    String conversationKeyOf(InboundMessage message, String owner) {
        SessionScopeProperties effective = scopes == null ? SessionScopeProperties.defaults() : scopes;
        return SessionKeys.conversationKeyOf(
                owner, message.direct(), message.channel(), conversationOf(message), effective);
    }

    /**
     * 这段对话的身份：调用方给了 {@code conversationId} 就用它，没给就按 {@code chatId} 派生。
     *
     * <p>为什么 chatId 不再兼任：它同时要回答「结果送到哪儿」——审批卡片、续跑结果、通知策略的推送
     * 全走它，而定时任务要的是「跑在自己的会话里，结果仍送到原来那个地方」。一个字段担两件事时，
     * 改哪一件都会把另一件弄坏：把 chatId 换成 {@code cron:<taskId>}，审批卡片就会推进一个不存在的
     * 会话，而这一轮看起来一切正常。
     *
     * @param message 入站消息
     * @return 对话身份
     */
    static String conversationOf(InboundMessage message) {
        String explicit = message.conversationId();
        return explicit == null || explicit.isBlank() ? message.chatId() : explicit;
    }
}
