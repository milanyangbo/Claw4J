package io.github.claw4j.channel.kernel;

import java.time.Instant;

/**
 * 一条已经解析出来的入站消息，与平台无关。
 *
 * <p>它是编排层的入口类型：平台的事件（JSON、protobuf、事件对象）在到达这里之前，
 * 必须已经被压成这五个字段。压不出来的部分——原始事件里的附件、表情、引用块——
 * 留在通道自己那里，编排层不认识它们。
 *
 * <p>五个字段各自对应编排层的一个动作，去掉任何一个都会让某个动作失准：
 *
 * <table border="1">
 *   <caption>字段与它服务的动作</caption>
 *   <tr><th>字段</th><th>编排层拿它做什么</th><th>没有它会怎样</th></tr>
 *   <tr><td>{@link #eventId()}</td><td>去重</td>
 *       <td>平台重推一次，群里出现两条一样的回答</td></tr>
 *   <tr><td>{@link #conversationId()}</td><td>回推的目标</td>
 *       <td>算完了不知道发回哪儿</td></tr>
 *   <tr><td>{@link #senderId()}</td><td>认人</td>
 *       <td>只能匿名放行，或整条丢弃</td></tr>
 *   <tr><td>{@link #text()}</td><td>交给 Agent</td><td>—</td></tr>
 *   <tr><td>{@link #receivedAt()}</td><td>日志与耗时</td>
 *       <td>只能记"处理完了"，算不出排队等了多久</td></tr>
 * </table>
 *
 * <p>三个平台在这五列上形状一致，差别只在参数名：飞书的 {@code event_id} /
 * {@code chat_id} / {@code open_id}、Telegram 的 {@code update_id} / {@code chat.id} /
 * {@code from.id}、Slack 的 {@code event_id} / {@code channel} / {@code user}。
 * 跨通道复用就建立在这五列逐个对得上，不靠"大概差不多"。
 */
public interface InboundEvent {

    /**
     * 平台侧的事件标识，去重就按它。
     *
     * <p>允许为 {@code null} 或空白：那时这条消息照常处理，只是去重失效并记一条 WARN。
     * 宁可重复回答，也不能因为少一个字段把用户的话丢掉。
     *
     * <p>它必须是平台重推时不变的值：飞书的 {@code event_id}、Telegram 的 {@code update_id}、
     * Slack 的 {@code event_id}。拿每次投递都变的字段（比如收到时间）做幂等键等于没有去重。
     */
    String eventId();

    /**
     * 回哪条会话。飞书是 {@code chat_id}，Telegram 是 {@code chat.id}，
     * Slack 是 {@code channel}。
     *
     * <p>编排层不解析它，只把它原样交回 {@link ReplySender}。
     */
    String conversationId();

    /**
     * 发消息的人在平台上的标识，用它去角色表里认人。
     *
     * <p>认不出就拒绝：不放行（等于匿名访问），也不静默丢弃（用户会以为机器人坏了）。
     */
    String senderId();

    /** 用户说的话。 */
    String text();

    /** 平台侧收到这条消息的时间，用于日志与耗时，不参与去重判定。 */
    Instant receivedAt();

    /**
     * 这是不是一条私聊（ADR-0029）。
     *
     * <p>编排层不解析它，只把它交给 {@code InboundMessage#direct()}，由会话键的分段规则决定
     * 这条消息落在哪个会话节点上：私聊默认并进一个人的主会话，群聊按群隔离。判错的后果不是
     * 报错，是把群里的上下文并进私聊，或者让同一个人跨通道接不上。
     *
     * <p>默认 {@code true}：没实现这个方法的通道（HTTP、内部调用、测试）没有"群"这个概念。
     * 会收到群消息的通道必须覆盖它——Slack 看 {@code channel_type: im}，飞书看
     * {@code chat_type: p2p}。
     *
     * @return 私聊为 {@code true}
     */
    default boolean direct() {
        return true;
    }
}
