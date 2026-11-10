package io.github.claw4j.agent.audit;

/**
 * 审计事件的结局，闭合词表（ADR-0027 决定二）。
 *
 * <p>为什么必须闭合：按 status 过滤是审计最主要的读法（「这个月拒了几次」），而自由文本里多一个
 * 拼法，那批记录就会被安静地漏掉——漏掉的那些看起来像没有发生过。
 *
 * <p>{@link #UNKNOWN} 是一个真实的结果，不是兜底：上游运行时没有给出权威结局时，记「不知道」比记
 * 「成功」诚实，也比记「失败」准确。OpenClaw 的状态词表同样保留这个值
 * （{@code packages/gateway-protocol/src/schema/audit.ts:17}）。
 */
public enum AuditStatus {

    STARTED("started"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
    BLOCKED("blocked"),
    UNKNOWN("unknown");

    private final String column;

    AuditStatus(String column) {
        this.column = column;
    }

    /**
     * 落库的写法，与 {@code V6__run.sql} 的 check 约束逐字对应。
     *
     * @return 状态名，如 {@code timed_out}
     */
    public String column() {
        return column;
    }
}
