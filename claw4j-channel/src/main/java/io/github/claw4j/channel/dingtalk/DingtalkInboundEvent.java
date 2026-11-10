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
 *
 * <p>{@link #direct()} 在这里是<b>抽象方法</b>，不像别的通道那样继承 {@code InboundEvent}
 * 的默认 {@code true}。理由是钉钉的回调里有权威的 {@code conversationType}：写成默认值的话，
 * 将来放开群聊时没人会想起回来改它，症状是"群里的上下文并进了这个人的私聊"，而那时候
 * 代码看起来完全正常。留给实现去回答，加群那条路就必须先过这一关。
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

    /**
     * 这条消息来自单聊还是群聊，由 {@code conversationType} 决定（{@code "1"} 单聊、{@code "2"} 群聊）。
     *
     * <p>当前只有单聊会走到这里（群消息在回调层就回一句"私聊我"结束了），所以实现恒返回
     * {@code true}——但那是判定的结果，不是默认值。
     */
    @Override
    boolean direct();

    @Override
    String text();

    @Override
    Instant receivedAt();
}
