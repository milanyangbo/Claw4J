package io.github.claw4j.agent.audit;

/**
 * 审计里的四类动作（ADR-0027 决定二）。
 *
 * <p><b>为什么是四个而不是"开始 / 结束"两个</b>：OpenClaw 把"一次运行"与"一次工具调用"
 * 分开记（{@code agent.run.*} 与 {@code tool.action.*}），因为它们的归属与用途不同——
 * 前者回答"这一轮跑没跑成"，后者回答"谁让哪个工具做了什么"。
 * 合成一对之后，一次运行里调了三个工具会变成"七个事件里只有两个是关于这一轮的"。
 *
 * <p>枚举而不是字符串常量：{@link #column()} 与迁移里的 check 约束逐字对应，
 * 拼错在编译期就会被拦下，而不是被库拒绝在运行期。
 */
public enum AuditAction {

    AGENT_RUN_STARTED("agent_run", "agent.run.started"),
    AGENT_RUN_FINISHED("agent_run", "agent.run.finished"),
    TOOL_ACTION_STARTED("tool_action", "tool.action.started"),
    TOOL_ACTION_FINISHED("tool_action", "tool.action.finished");

    private final String kind;

    private final String column;

    AuditAction(String kind, String column) {
        this.kind = kind;
        this.column = column;
    }

    /** 这一行动作的类别，落 {@code audit_event.kind}。 */
    public String kind() {
        return kind;
    }

    /** 落库的写法，与 {@code V19__audit_event.sql} 的 check 约束逐字对应。 */
    public String column() {
        return column;
    }
}
