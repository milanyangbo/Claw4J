package io.github.claw4j.channel.feishu;

/**
 * 把一段文本主动推回某个会话。
 *
 * <p>飞书的普通消息没有 SSE，逐块增量更新要额外调消息更新接口并处理限频，所以这里只有
 * "发一条完整文本"这一种形态；超过平台单条上限的拆分由调用方（{@link InboundDispatcher}）负责，
 * 不交给实现——否则每个实现都要重新决定一次怎么切。
 *
 * <p>实现由 SDK 绑定层提供。失败要抛出来（网络、限频、凭据过期都算）：调度层要能在日志里
 * 记下"这一条没发出去"，而不是以为发出去了。{@code chatId} 与入站那条同源。
 */
public interface FeishuReplySender {

    /**
     * @param chatId 回哪条会话
     * @param text   已经切好的一段文本，不会为空
     */
    void send(String chatId, String text);
}
