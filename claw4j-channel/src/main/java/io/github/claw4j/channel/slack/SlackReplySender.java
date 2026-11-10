package io.github.claw4j.channel.slack;

import io.github.claw4j.channel.kernel.ReplySender;

/**
 * 把一段文本推回 Slack。
 *
 * <p>{@code conversationId} 与其他通道不同：它是 {@code 频道|线程根}
 * （见 {@link SlackConversation}），不是一个用户标识。
 *
 * <p>实现要自己管两件 Slack 独有的事：
 * <ul>
 *   <li>{@code chat.postMessage} 失败时也回 HTTP 200，只有 body 里的 {@code ok} 是 {@code false}。
 *       判状态码而不判 {@code ok}，会得到一个永远报成功的发送器。</li>
 *   <li>同一个频道约每秒只允许 1 条。超过 4000 字的答案会被切成几段连着发，第 2 段就会撞上限流，
 *       而 {@code InboundDispatcher} 遇异常会停止后续段落，用户收到的是半截答案加一行 ERROR。</li>
 * </ul>
 *
 * <p>失败要抛：调度层要能在日志里记下"这一条没发出去"。吞掉的表现是会话里没有回答、
 * 日志里也没有痕迹，与"根本没跑"一模一样。
 */
public interface SlackReplySender extends ReplySender {

    /**
     * 发一段文本到 {@code conversationId} 指定的频道或线程。
     *
     * @param conversationId {@code 频道|线程根}，线程根可以没有
     * @param text 已经切好的一段文本，不会为空
     * @throws IllegalStateException 发送失败，或 {@code conversationId} 里没有频道
     */
    @Override
    void send(String conversationId, String text);
}
