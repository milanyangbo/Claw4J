package io.github.claw4j.agent.turn;

import io.github.claw4j.agent.session.SessionEntries;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionScopeProperties;
import io.github.claw4j.domain.port.InboundMessage;

/**
 * 这一轮属于哪段对话：两个键、一处算法。
 *
 * <p>键有两个，寿命不同，<b>不能合成一个</b>：
 *
 * <ul>
 *   <li>{@code sessionId}（含段）——会话记忆按它隔离，{@code /new} 换一段；
 *   <li>{@code conversationKey}（不含段）——目标、提权这类「这段对话的属性」挂在它上面，
 *       {@code /new} 之后仍然有效。
 * </ul>
 *
 * <p>抽出来是因为 {@code reply} 与 {@code stream} 两条路各要算一次，而它们必须算出同一个 id——
 * 一条消息走流式、下一条走非流式是常态，两处各写一遍的话，「什么算同一段对话」就有了两个答案。
 */
final class SessionAddress {

    /**
     * 这段对话现在叫什么（ADR-0026）。可以为 null：没有会话存储就没有「段」这回事，于是
     * {@code sessionId} 退回加段之前的形状。
     *
     * <p>它不属于 run 那一组：一轮的编号活一轮，段跨多轮，寿命不同，开关也不同——关掉那层持久化时
     * 段照常工作。
     */
    private final SessionEntries pointers;

    /**
     * @param pointers 会话指针，可以为 null（没有会话存储时就没有"段"）
     */
    SessionAddress(SessionEntries pointers) {
        this.pointers = pointers;
    }

    /**
     * 这一轮的对话键：段由指针决定，指针缺席或读不到就是加段之前的形状。
     *
     * @param message 入站消息，可能自带整段会话键
     * @param owner 归属键
     * @param reset 是否换段，{@code /new} 时为真
     * @return 这一轮用的 sessionId
     */
    String sessionIdOf(InboundMessage message, String owner, boolean reset) {
        // 调用方直接给了整段会话键：用它，不换段也不查指针。
        // 两条路走这里——审批批准之后回到发起请求的那一段（ADR-0027），
        // 以及定时任务（每次触发自带一个一次性的键）。
        if (message.sessionId() != null && !message.sessionId().isBlank()) {
            return message.sessionId();
        }
        String peer = conversationOf(message);
        // 指针缺席或读不到时就是"不加段"：段回答"这段对话叫什么"，不是"能不能回答"。
        String segment = pointers == null
                ? ""
                : reset
                        ? pointers.rotate(owner, message.direct(), message.channel(), peer)
                        : pointers.segmentOf(owner, message.direct(), message.channel(), peer);
        return SessionKeys.sessionIdOf(conversationKeyOf(message, owner), segment);
    }

    /**
     * 换一段，返回新的 {@code sessionId}。
     *
     * <p>只有 {@code /new} 走这里。光发一个 {@code /new} 时这一句不落库、不进提示词，返回值因此
     * 被丢掉——换段本身已经发生了，下一句自然落在新段上。走同一个入口，是因为"单独 /new"与
     * "/new 带内容"不该各自长出一套换段逻辑。
     *
     * @param message 入站消息
     * @param owner 归属键
     * @return 这一句本该用的新 sessionId
     */
    String rotate(InboundMessage message, String owner) {
        return sessionIdOf(message, owner, true);
    }

    /**
     * 这一轮属于哪个会话节点（不含段）。目标与状态行都按它定位。
     *
     * <p><b>这是键形状的唯一一处定义。</b>含段的 {@link #sessionIdOf} 也是先拿到它再拼段——
     * 两边各写一份的话，会出现"记忆落在 {@code ...:main:<段>}、目标落在
     * {@code ...:<channel>:<chatId>}"，两个节点都不是对方，而报错一个也没有：
     * {@code /goal} 只会说"这个会话还没有目标"。ADR-0029 把 peer 段交给 dmScope 之后，
     * 这个分叉不再是"某个配置下才发生"，默认配置下就是它。
     *
     * <p>不复用 sessionId 去解析：{@code chatId} 里可以有冒号（定时任务就是
     * {@code cron:<taskId>:<时刻>}），按分隔符切会在那种 id 上切错，而切错的表现是目标挂到了另一个
     * 节点上，不报错，只是读到空。
     *
     * @param message 入站消息
     * @param owner 归属键
     * @return 会话节点键
     */
    String conversationKeyOf(InboundMessage message, String owner) {
        SessionScopeProperties scopes = pointers == null
                ? SessionScopeProperties.defaults()
                : pointers.scopes();
        return SessionKeys.conversationKeyOf(
                owner, message.direct(), message.channel(), conversationOf(message), scopes);
    }

    /**
     * 这段对话的身份：调用方给了 {@code conversationId} 就用它，没给就按 {@code chatId} 派生
     * （ADR-0026 决定三）。
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
