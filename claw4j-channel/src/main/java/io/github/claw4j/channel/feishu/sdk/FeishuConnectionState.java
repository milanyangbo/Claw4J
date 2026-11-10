package io.github.claw4j.channel.feishu.sdk;

/** 长连接的状态。四个值够回答「这台机器上的飞书通道现在能不能收到消息」。 */
public enum FeishuConnectionState {

    /** 还没开始连，或者正在连。初值。 */
    CONNECTING,

    /** 连着。 */
    CONNECTED,

    /** 没连上，后台在退避重连。应用是起着的，只是这条通道暂时收不到消息。 */
    DISCONNECTED,

    /** 停了：正常关闭，或者遇到了永久错（后者启动就失败，看不到这个值）。 */
    STOPPED
}
