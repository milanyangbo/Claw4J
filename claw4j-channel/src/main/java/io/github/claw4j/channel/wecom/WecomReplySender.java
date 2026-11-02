package io.github.claw4j.channel.wecom;

import io.github.claw4j.channel.kernel.ReplySender;

/**
 * 把一段文本推回某个企业微信成员。
 *
 * <p>与 {@link io.github.claw4j.channel.feishu.FeishuReplySender 飞书那一份}同一个形状，
 * 只是"会话"在企业微信里是成员 UserID：自建应用的消息是一对一的，
 * 推送接口 {@code /cgi-bin/message/send} 的 {@code touser} 收的就是它。
 *
 * <p><b>实现要自己管 access_token</b>：企业微信的每一个业务接口都靠它，
 * 有效期两小时、每天有取用上限，所以换一次要缓存起来，快到期再换——
 * 每次都换会在量一上来时被限流，而限流的表现是"消息偶发发不出去"，最难查。
 *
 * <p>失败要抛（网络、{@code errcode} 非 0、token 过期都算）：调度层要能在日志里记下
 * "这一条没发出去"，而不是以为发出去了。
 */
public interface WecomReplySender extends ReplySender {

    /**
     * @param userId 成员 UserID，与入站事件里的 {@code FromUserName} 同源
     * @param text   已经切好的一段文本，不会为空
     */
    @Override
    void send(String userId, String text);
}
