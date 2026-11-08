package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.EventDispatcher;

/**
 * 开一条新的飞书长连接。每次调用都返回**一条独立的连接**——重连就是"关掉旧的、开一条新的"，
 * 不试图让 SDK 自己救活那条已经失败的。
 *
 * <p>为什么不让 SDK 自己重连：它确实有 {@code autoReconnect}（默认 {@code true}，2.8.7 的
 * {@code Client} 构造器里 {@code if null → true}），但那条路只在**已经连上之后断开**时有用，
 * 而且退避策略不归我们管、状态也读不出来。首连失败之后我们要做的是"等一段时间再完整地开一条"，
 * 这件事由我们控制才有日志、有状态、有上限。
 *
 * <p>参数里有 {@link EventDispatcher}：回调属于通道，连接只是承载它。让工厂自己造一个事件分发器
 * 等于把"收到消息怎么办"这一层搬进 SDK 包里，那是这条通道唯一需要被默认构建测到的部分。
 */
public interface FeishuConnectionFactory {

    FeishuConnection open(EventDispatcher events);
}
