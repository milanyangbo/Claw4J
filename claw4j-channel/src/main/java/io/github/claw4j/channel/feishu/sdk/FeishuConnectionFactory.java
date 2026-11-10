package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.EventDispatcher;

/**
 * 开一条新的飞书长连接。每次调用返回一条独立的连接：重连就是关掉旧的、开一条新的，
 * 不让 SDK 去救活那条已经失败的。
 *
 * <p>不用 SDK 的 {@code autoReconnect}（默认 {@code true}，2.8.7 的 {@code Client} 构造器里
 * {@code if null → true}）：这条路只在已经连上之后断开时有用，退避策略不归调用方管，
 * 状态也读不出来。首连失败后要做的是等一段时间再完整地开一条，自己控制才有日志、
 * 有状态、有上限。
 *
 * <p>参数里有 {@link EventDispatcher}：回调属于通道，连接只是承载它。让工厂自己造事件分发器，
 * 等于把「收到消息怎么办」搬进 SDK 包里，而那是这条通道唯一需要被默认构建测到的部分。
 */
public interface FeishuConnectionFactory {

    /**
     * 开一条连接。
     *
     * @param events 收到事件时调的分发器，由通道提供
     * @return 一条新连接
     */
    FeishuConnection open(EventDispatcher events);
}
