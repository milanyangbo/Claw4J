package io.github.claw4j.channel.slack;

import io.github.claw4j.channel.kernel.ReplySender;

/**
 * 把一段文本推回 Slack。
 *
 * <p>{@code conversationId} 的形状与其他三个通道不同：它是
 * {@code 频道|线程根}（见 {@link SlackConversation}），不是一个用户标识。
 *
 * <p><b>实现要自己管两件事，都是 Slack 独有的：</b>
 *
 * <ol>
 *   <li><b>HTTP 200 不代表成功</b>。{@code chat.postMessage} 失败时也回 200，
 *       只是 body 里 {@code ok} 是 {@code false}。官方原话是
 *       "Callers should always check the value of the {@code ok} parameter in the response"。
 *       照抄钉钉/企微那套"状态码 200 就当成功"的写法，会得到一个<b>永远成功的发送器</b>。</li>
 *   <li><b>同一个频道 1 秒只能发 1 条</b>。一条超过 4000 字的答案会被切成几段连着发，
 *       第 2 段就会撞上限流——而 {@code InboundDispatcher} 遇到异常会<b>停止后续段落</b>，
 *       于是用户收到的是半截答案加一行 ERROR。</li>
 * </ol>
 *
 * <p>失败要抛：调度层要能在日志里记下"这一条没发出去"。吞掉的表现是会话里没有回答、
 * 日志里也没有痕迹——与"根本没跑"一模一样。
 */
public interface SlackReplySender extends ReplySender {

    /**
     * @param conversationId {@code 频道|线程根}，线程根可以没有
     * @param text           已经切好的一段文本，不会为空
     */
    @Override
    void send(String conversationId, String text);
}
