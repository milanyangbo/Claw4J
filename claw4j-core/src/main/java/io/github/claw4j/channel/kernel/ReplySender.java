package io.github.claw4j.channel.kernel;

/**
 * 把一段文本主动推回某个会话。
 *
 * <p>只有"发一条完整文本"这一种形态。超过平台单条上限的拆分由调用方
 * （{@link InboundDispatcher}）负责，不交给实现：否则每个通道都要重新决定一次怎么切，
 * 而"别把代码围栏切成两半"这件事与平台无关。
 *
 * <p>实现由各通道自己提供（飞书走 OpenAPI、Telegram 走 {@code sendMessage}）。失败要抛出来：
 * 网络、限频、凭据过期都算。编排层要能在日志里记下"这一条没发出去"，而不是以为发出去了。
 */
public interface ReplySender {

    /**
     * 发一段文本。
     *
     * @param conversationId 回哪条会话，与 {@link InboundEvent#conversationId()} 同源
     * @param text           已经切好的一段文本，不会为空
     * @throws RuntimeException 发送失败，实现按平台语义选择具体类型
     */
    void send(String conversationId, String text);
}
