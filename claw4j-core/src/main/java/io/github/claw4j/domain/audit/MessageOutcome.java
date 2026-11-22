package io.github.claw4j.domain.audit;

/**
 * 消息处理的结果（ADR-0037 票 04）。
 *
 * <p>与 {@link AuditStatus} 分开，不是重复：status 是通用生命周期（started / succeeded / failed …），
 * outcome 是这一层的具体结果——「成功了」还分「处理完了」与「发出去了」，「被挡住」还分「跳过了」
 * 与「抑制了」。两列在 OpenClaw 里也都在（{@code src/audit/audit-event-types.ts:141-215}）。
 *
 * <p>合成一列的话，「这个月送出去多少条」要按 kind + action + status 三列去猜，而猜错了不报错。
 */
public enum MessageOutcome {

    /** 入站处理完了（回了、转给子代理了、或者当场答了）。 */
    COMPLETED("completed"),

    /** 入站被跳过：重复投递、或者这段对话正有一条在处理。 */
    SKIPPED("skipped"),

    /** 出站真的送到平台了。 */
    SENT("sent"),

    /** 出站被抑制：没有可见内容、或者被策略挡了。它不是失败——没有人期待它出现。 */
    SUPPRESSED("suppressed"),

    /** 处理或投递失败。 */
    FAILED("failed"),

    /** 跑了，但结果不可知：平台没回执、进程在写回之前没了。 */
    UNKNOWN("unknown");

    private final String column;

    MessageOutcome(String column) {
        this.column = column;
    }

    /**
     * 落库的写法，与 {@code V12} 的 check 约束逐字对应。
     *
     * @return 结果名
     */
    public String column() {
        return column;
    }
}
