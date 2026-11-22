package io.github.claw4j.agent.audit;

/**
 * 审计里的四类动作（ADR-0027 决定二）。
 *
 * <p>为什么是四个而不是「开始 / 结束」两个：OpenClaw 把一次运行与一次工具调用分开记
 * （{@code agent.run.*} 与 {@code tool.action.*}，{@code packages/gateway-protocol/src/schema/audit.ts:10}），
 * 因为两者的归属与用途不同——前者回答「这一轮跑没跑成」，后者回答「谁让哪个工具做了什么」。
 * 合成一对之后，一次运行里调了三个工具，就变成「七个事件里只有两个是关于这一轮的」。
 *
 * <p>用枚举而不是字符串常量：{@link #column()} 与迁移里的 check 约束逐字对应，拼错在编译期被拦下，
 * 而不是被库拒绝在运行期。
 */
public enum AuditAction {

    AGENT_RUN_STARTED("agent_run", "agent.run.started"),
    AGENT_RUN_FINISHED("agent_run", "agent.run.finished"),
    TOOL_ACTION_STARTED("tool_action", "tool.action.started"),
    TOOL_ACTION_FINISHED("tool_action", "tool.action.finished"),

    /**
     * 一条入站消息处理完了（ADR-0037 票 04）。
     *
     * <p>只有终态一条，没有 started 那一半：消息的"开始"就是它到达，而到达有平台自己的时间戳，
     * 不需要我们再记一遍。OpenClaw 的消息动作同样是「一次处理一条终态」
     * （{@code src/audit/audit-event-types.ts:220}）。
     */
    MESSAGE_INBOUND_PROCESSED("message", "message.inbound.processed"),

    /**
     * 一条出站消息投递结束（ADR-0037 票 04）。
     *
     * <p>同样只有终态：{@code queued} / {@code platform-started} 那种进度行在 OpenClaw 里也不进
     * {@code audit.activity.list}（{@code docs/gateway/protocol/ledgers.md:132-136}）。
     */
    MESSAGE_OUTBOUND_FINISHED("message", "message.outbound.finished");

    private final String kind;

    private final String column;

    AuditAction(String kind, String column) {
        this.kind = kind;
        this.column = column;
    }

    /**
     * 这一行动作的类别，落 {@code audit_event.kind}。
     *
     * @return {@code agent_run} 或 {@code tool_action}
     */
    public String kind() {
        return kind;
    }

    /**
     * 落库的写法，与 {@code V6__run.sql} 的 check 约束逐字对应。
     *
     * @return 动作名，如 {@code agent.run.started}
     */
    public String column() {
        return column;
    }
}
