package io.github.claw4j.channel.feishu;

import io.github.claw4j.channel.kernel.ReplySender;

/**
 * 把一段文本主动推回某个会话。
 *
 * <p>只有「发一条完整文本」这一种形态：飞书普通消息没有 SSE，逐块增量更新要另调消息更新接口
 * 并处理限频。超过平台单条上限的拆分由调用方
 * （{@link io.github.claw4j.channel.kernel.InboundDispatcher}）负责，不交给实现，
 * 否则每个实现都要重新决定一次怎么切。
 *
 * <p>命名只是把 {@link ReplySender} 挪到飞书这一侧：{@code conversationId} 在飞书是
 * {@code chat_id}，签名与内核一致，飞书自己的代码仍然写 {@code send(chatId, text)} 读得通。
 *
 * <p>实现由 SDK 绑定层提供。网络、限频、凭据过期都要抛出来：调度层要能在日志里记下
 * 这一条没发出去，而不是以为发出去了。{@code chatId} 与入站那条同源。
 */
public interface FeishuReplySender extends ReplySender {

    /**
     * 把一段已经切好的文本发到指定会话。
     *
     * @param chatId 回哪条会话
     * @param text 已经切好的一段文本，不会为空
     */
    void send(String chatId, String text);
}
