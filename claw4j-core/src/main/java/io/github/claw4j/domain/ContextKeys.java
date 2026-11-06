package io.github.claw4j.domain;

/**
 * 一次调用里"谁在请求"这件事在两处上下文里的键名。
 *
 * <p>它必须定义在 domain 而不是 Agent 侧，理由是一个具体的失效：
 * 委派（Subagent）与脚本执行都发生在<b>工具回调里</b>，那里拿不到 Advisor 的上下文，
 * 只能从 {@code ToolContext} 里读身份；而工具在 {@code claw4j-capability}，
 * 它按架构约束不认识 {@code claw4j.agent}（ADR-0010）。
 * 于是这一把 key 只能住在两边都认识的地方——各写一份字符串常量，
 * 漂移的方式是"工具侧读到 null"，表现为"委派/执行一律没身份"，
 * 而日志上只显示一次拒答。
 *
 * <p>Advisor 的 param 与 prompt 的 {@code toolContext} 用同一把 key，
 * 是有意的：两条路走到的是同一个 Principal，改一处就两边一起变。
 */
public final class ContextKeys {

    /** {@link Principal} 在 Advisor 上下文与工具上下文里的键名。 */
    public static final String PRINCIPAL = "claw4j.principal";

    /**
     * 这一轮的编号，在工具上下文里的键名（ADR-0027）。
     *
     * <p><b>它是一个句柄，不是一件事</b>：一轮答完它就作废，没有状态、没有后续、
     * 也没有"按 id 接上它"那条路。它只用于把同一轮的三样东西对起来——
     * 审计的两条事件、trace 的一段 span、返回给调用方的回执。
     * <p><b>名字照 OpenClaw</b>：它的 {@code runId} 也是这个角色（{@code chat.send} 返回它、
     * {@code chat.abort} 收它、{@code audit --run} 按它过滤），而且原话是"a possibly shared
     * routing, session, or recovery correlation"。我们这里更窄——没有 recovery，也没有跨请求续跑，
     * 所以一轮就是一个 {@code runId}。窄的那一侧不需要额外的名字：同一个概念在两边叫同一个名，
     * 迁过去的客户端不用改字段。
     *
     * <p>放在这里而不是入口那个类上，是因为键名这种东西<b>只有一处定义才不会漂移</b>，
     * 而漂移的表现是"工具读到的值是 null"，看起来像"这一轮没有编号"——一个说得通的假象。
     *
     * <p><b>它是入口写进去的，不是模型传进来的</b>：工具不接这个参数，
     * 于是模型没有"往别人的轮次上写东西"这条路径。
     */
    public static final String RUN_ID = "claw4j.runId";

    /**
     * 这一轮从哪个通道来、通道内的哪段会话（ADR-0027）。
     *
     * <p>两个键解决同一类问题：审批要写"这条请求来自哪段对话"（批准之后回到那里开新一轮），
     * 定时任务要写"这个任务的结果推回哪里"。以前这两处都靠 {@code runId} 去
     * 一张投递表反查，而那张表已经不在了——地址本来就该跟着这一轮走。
     *
     * <p><b>入口写进去，工具只读</b>：与 {@link #PRINCIPAL} 同一个姿势。模型没有参数能改它们，
     * 于是"给别人的对话发审批卡片"这条路径不存在。
     */
    public static final String CHANNEL = "claw4j.channel";

    /** 通道内的会话地址。飞书是 {@code chat_id}，HTTP 是请求头里那个。 */
    public static final String CHAT_REF = "claw4j.chatRef";

    private ContextKeys() {
    }
}
