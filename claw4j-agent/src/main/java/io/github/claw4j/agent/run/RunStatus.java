package io.github.claw4j.agent.run;

/**
 * run 的状态机（ADR-0016）：{@code running ⇄ suspended}，二者后可进
 * {@code done / failed / budget_exhausted}。
 *
 * <p><b>只有 {@link #SUSPENDED} 能被外部事件唤醒</b>：审批通过、定时到点、用户说的下一句。
 * {@link #RUNNING} 不能被外部唤醒——它在跑，唤醒它会变成"同一件事跑两遍"。
 *
 * <p><b>{@link #BUDGET_EXHAUSTED} 是终态但允许重开</b>：追加预算后重进 running（A04 那张票的事）。
 * 它与 {@link #FAILED} 的区别是"停下来是因为额度用完了，不是因为做错了"——
 * 恢复的路径不同，混成一个状态时没人知道该重试还是该换做法。
 *
 * <p>列值用小写下划线，与 {@code V6__run_schema.sql} 里的 check 约束逐字对应。
 * 改这里的名字必须同时改迁移：约束会拒绝未知值，写错的表现是插入直接报错，
 * 而不是静默存一个没人认得的状态。
 */
public enum RunStatus {

    RUNNING("running"),
    SUSPENDED("suspended"),
    DONE("done"),
    FAILED("failed"),
    BUDGET_EXHAUSTED("budget_exhausted");

    private final String column;

    RunStatus(String column) {
        this.column = column;
    }

    /** 落库的写法。 */
    public String column() {
        return column;
    }

    /**
     * 回读时把列值翻回枚举。
     *
     * @throws IllegalArgumentException 库里出现了枚举不认识的值时。
     *                                  宁可抛也不返回默认值：一个认不出的状态被当成
     *                                  "done" 会让一件没做完的事看起来做完了，而那正是 run 表存在的理由。
     */
    public static RunStatus fromColumn(String column) {
        for (RunStatus status : values()) {
            if (status.column.equals(column)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的 run 状态：" + column);
    }
}
