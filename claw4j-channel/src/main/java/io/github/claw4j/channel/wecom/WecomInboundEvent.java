package io.github.claw4j.channel.wecom;

import io.github.claw4j.channel.kernel.InboundEvent;

import java.time.Instant;

/**
 * 一条已经从企业微信回调里解出来的入站消息。
 *
 * <p>形状与 {@link io.github.claw4j.channel.feishu.FeishuInboundEvent 飞书那一份}相同，
 * 差别只在字段的来源和"回哪儿"的含义：
 *
 * <table border="1">
 *   <caption>两家的字段对照</caption>
 *   <tr><th>内核字段</th><th>飞书</th><th>企业微信</th></tr>
 *   <tr><td>{@link #eventId()}</td><td>{@code event_id}</td><td>{@code MsgId}</td></tr>
 *   <tr><td>{@link #conversationId()}</td><td>{@code chat_id}（群或单聊）</td>
 *       <td>{@code FromUserName}（成员 UserID）</td></tr>
 *   <tr><td>{@link #senderId()}</td><td>{@code open_id}</td><td>{@code FromUserName}</td></tr>
 * </table>
 *
 * <p>会话标识与发送者是同一个值，这是企业微信的形态决定的：自建应用的消息是一对一的，
 * 推送接口的 {@code touser} 就是成员的 UserID，没有群这一层。回哪儿与谁问的在这里是同一个字符串，
 * 拆成两个字段只是为了让编排层不必知道这件事。
 *
 * <p>{@code MsgId} 是重推时不变的那个值，去重按它。它可能缺失（极少数回调里没有），
 * 那种情况下这条消息照常处理，只是去重失效——宁可重复回答一次，
 * 也不因为少一个字段丢掉用户说的话。
 */
public interface WecomInboundEvent extends InboundEvent {

    @Override
    default String conversationId() {
        return userId();
    }

    @Override
    String eventId();

    @Override
    String senderId();

    /** 发消息的成员 UserID，与应用消息的 {@code touser} 同口径。 */
    String userId();

    /**
     * 恒为 {@code true}：企业微信自建应用的回调里没有"群"这一维。
     *
     * <p>这不是"没判所以按默认走"。官方接收消息的字段清单是 {@code ToUserName}、
     * {@code FromUserName}、{@code CreateTime}、{@code MsgType}、{@code MsgId}、{@code AgentID}
     * （图片/语音等再加媒体字段），其中没有任何会话类型字段——应用消息就是成员发给应用的一对一消息，
     * 群里的消息走的是群机器人 webhook，不进这个回调。
     *
     * <p>不靠 {@link InboundEvent} 的默认值：那行写在别的模块里，改这个通道的人不会去看它。
     * 将来换成有群的接入方式（群机器人回调、智能机器人），该改的是这个方法。
     */
    @Override
    default boolean direct() {
        return true;
    }

    @Override
    String text();

    @Override
    Instant receivedAt();
}
