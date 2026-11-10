package io.github.claw4j.channel.spi;

/**
 * 通道：把某个外部世界（HTTP、飞书、A2A、Teammors）接进来的适配器。
 *
 * <p>一个实现只负责三件事：收进来 → 认出是谁 → 发出去。中间"怎么回答"不属于通道，交给构造时
 * 注入的 {@code AgentPort}。
 *
 * <p>实现必须满足：
 * <ul>
 *   <li>拿到入站消息就尽快返回确认，不要在收消息的线程里同步等 Agent。飞书要求 3 秒内处理完
 *       事件，否则重推（ADR-0009）；Agent 一次 Text-to-SQL 可能十几秒，所以必须
 *       「立刻 ACK → 异步编排 → 主动回推」。</li>
 *   <li>用 {@code InboundMessage#eventId()} 做幂等，重推不能让同一句话被处理两次。</li>
 *   <li>认人失败就拒绝，不要把匿名请求放进来。</li>
 * </ul>
 */
public interface ChatChannel {

    /**
     * 通道标识，用于日志、配置前缀与路由。如 {@code http}、{@code feishu}、{@code a2a}。
     */
    String id();

    /**
     * 建立连接或注册端点。
     *
     * <p>只抛"换个时间点还是错"的失败：凭据错、应用未开通、配置写错。网络抖动、握手超时、
     * 对端暂时不可用由实现自己在后台退避重试并记下状态——抛出去在 K8s 上就是 CrashLoopBackOff，
     * 一次对端重启会变成整个应用不可用，连不依赖这条连接的通道也一起下线。
     *
     * <p>判据是"这条错误会不会自己好"，不是"这个错严不严重"。OpenClaw 的分法见
     * docs/channels/slack/transports.md:71（票 02）。
     */
    void start();

    /**
     * 释放连接、注销端点。必须可重复调用。
     */
    void stop();
}
