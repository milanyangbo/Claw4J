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
     * 当前这一轮落在哪个 run 上，在工具上下文里的键名（A03）。
     *
     * <p>只有工具上下文这一侧需要它：写 run 的是 Agent 入口，读它的是计划工具——
     * 两者都在 {@code claw4j.agent} 里。放在这里而不是入口那个类上，
     * 是因为键名这种东西<b>只有一处定义才不会漂移</b>，而漂移的表现是
     * "工具读到的 runId 是 null"，看起来像"这一轮没有 run"——一个完全说得通的假象。
     * 后面接上 run 的（长 run 上下文 A08、预算 A04）也读这一把 key。
     *
     * <p><b>它是入口写进去的，不是模型传进来的</b>：计划工具不接 {@code run_id} 参数，
     * 于是模型没有"点名别人的 run 写计划"这条路径，也就不需要每次调用都查一次 owner。
     */
    public static final String RUN_ID = "claw4j.runId";

    /**
     * 这一轮从哪个通道来、通道内的哪段会话（ADR-0027）。
     *
     * <p>两个键解决同一类问题：审批要写"这条请求来自哪段对话"（批准之后回到那里开新一轮），
     * 定时任务要写"这个任务的结果推回哪里"。以前这两处都靠 {@code runId} 去
     * {@code agent_run_delivery} 反查，而那张表已经不下线了——地址本来就该跟着这一轮走。
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
