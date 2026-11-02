package io.github.claw4j.agent.run;

/**
 * run 的状态机（ADR-0016）：{@code running ⇄ suspended}，二者后可进
 * {@code done / failed / budget_exhausted}。
 *
 * <p><b>{@link #SUSPENDED} 只能由服务端事件唤醒</b>（审批放行、定时到点）：它只在"停下来等人"
 * 时出现，别的路径不该把它拉起来。<b>{@link #RUNNING} 则允许被用户的"继续"接上</b>——
 * {@code RunStore#resume} 的谓词就是"除 suspended 之外都接"。理由不是"想接就接"：
 * 进程被杀之后库里这一行仍然写着 running，而本仓库没有租约，从库里分不出
 * "还在跑"与"已经死了"，A02 的"杀进程后继续"只能走这条路。
 *
 * <p>要付的代价写在 {@code JdbcRunStore#resume} 上：同一件真在跑的事被两个人同时"继续"时，
 * 两条执行流都会往下走，谁的 step 先落谁占号，另一条撞 {@code (run_id, seq)} 唯一键
 * 之后被 {@code guard} 吞掉、只记一条 {@code claw4j.run.errors}——也就是悄悄少一行。
 * 要根治得给 run 加租约（谁在跑、什么时候心跳），那是另一次改动，不在 P07 的口径里。
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
