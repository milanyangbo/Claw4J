package io.github.claw4j.agent.audit;

/**
 * 审计的归一化错误码，闭合词表（ADR-0027 决定二）。
 *
 * <p>九个值逐字照 OpenClaw 的 {@code AuditEventErrorCodeSchema}
 * （{@code packages/gateway-protocol/src/schema/audit.ts:27}）。一半属于运行、一半属于工具，前缀就是
 * 归属。前缀不是为了好看——审计的主要读法是按 {@code kind} 与 {@code status} 过滤，而一个
 * {@code agent_run} 的事件配上 {@code tool_failed}，读的人一眼就能看出写错了层。但那只是看得出来，
 * 不是写不进去：库里没有 {@code kind} 与 {@code error_code} 的配对约束，枚举只管值域、管不了这两个
 * 字段搭不搭。要真挡住得再加一条 check，目前没有。
 *
 * <p>为什么是枚举而不是几个 String 常量：闭合词表要真的闭合。常量只在调用方愿意用常量时成立，
 * 而从异常类型名拼出来的字符串照样能写进去；换成枚举，「写一个词表外的值」在编译期就不成立。这与
 * {@code AuditEvent} 里「没有地方装参数原文」是同一个手法：把约束做成类型，而不是写成注释。
 *
 * <p>不细分到异常类型：{@code ToolExecutionException}、{@code SocketTimeoutException} 这些名字是
 * 开放的，而这一列是闭合口径。要查是哪个异常，trace 的 span 上有。
 */
public enum AuditErrorCode {

    /** 运行失败：模型调用抛了、流断了、收尾时出不来回答。 */
    RUN_FAILED("run_failed"),

    /** 运行被取消：{@code /stop}、进程退出、调用方断开。 */
    RUN_CANCELLED("run_cancelled"),

    /** 运行超时。 */
    RUN_TIMED_OUT("run_timed_out"),

    /** 运行被挡住：准入或策略在开跑之前就拒了这一轮。 */
    RUN_BLOCKED("run_blocked"),

    /** 工具抛了异常。 */
    TOOL_FAILED("tool_failed"),

    /** 工具被取消。 */
    TOOL_CANCELLED("tool_cancelled"),

    /** 工具超时。 */
    TOOL_TIMED_OUT("tool_timed_out"),

    /**
     * 工具被策略挡住：没权限、或者这个工具的权限根本没登记。
     *
     * <p>这种调用没有开始，只有一条 {@code finished / blocked}——两条都写的话，「想调但没调成」会被
     * 算成「调过」。
     */
    TOOL_BLOCKED("tool_blocked"),

    /** 工具跑了，但结果不可知：流式中断了、进程在写回之前没了。 */
    TOOL_OUTCOME_UNKNOWN("tool_outcome_unknown");

    private final String column;

    AuditErrorCode(String column) {
        this.column = column;
    }

    /**
     * 落库的写法，与 {@code V6__run.sql} 的 check 约束逐字对应。
     *
     * @return 错误码，如 {@code tool_blocked}
     */
    public String column() {
        return column;
    }
}
