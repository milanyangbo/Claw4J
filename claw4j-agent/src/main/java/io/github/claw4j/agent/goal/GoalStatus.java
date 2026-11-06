package io.github.claw4j.agent.goal;

/**
 * 目标的状态。六态照 OpenClaw 的词表，一个字都不改名。
 *
 * <p><b>{@link #BUDGET_LIMITED} 与 {@link #USAGE_LIMITED} 是"停下来但没做完"</b>：
 * 它们与 {@link #COMPLETE} 的区别是恢复路径不同——前者 {@code /goal resume} 会开一个新的
 * 预算窗口从同一目标继续，后者是终态，要开始别的事得先 {@code /goal clear}。
 * 混成一个状态时没人知道该继续还是该换目标。
 *
 * <p><b>{@link #USAGE_LIMITED} 我们现在进不去</b>：OpenClaw 自己也标着"为未来的用量限制
 * 状态保留"。词表照抄是因为少一个状态将来要同时改约束、命令与注入逻辑三处；
 * 而多一个用不到的值只多一行。
 *
 * <p>列值用小写下划线，与 {@code V20__conversation_goal.sql} 的 check 约束逐字对应。
 * 改这里的名字必须同时改迁移：约束会拒未知值，写错的表现是插入直接报错，
 * 而不是静默存一个没人认得的状态。
 */
public enum GoalStatus {

    /** 会话正在推进这个目标。只有它会被注入到提示词里。 */
    ACTIVE("active", "活跃"),

    /** 操作员暂停了它。{@code /goal resume} 让它回到活跃。 */
    PAUSED("paused", "已暂停"),

    /** 真实阻塞。有新信息之后 {@code /goal resume} 让它回到活跃。 */
    BLOCKED("blocked", "已阻塞"),

    /** 撞上了配置的 token 预算。resume 会开一个新的预算窗口。 */
    BUDGET_LIMITED("budget_limited", "预算受限"),

    /** 为将来的用量限制状态保留，本仓库暂时进不去。 */
    USAGE_LIMITED("usage_limited", "用量受限"),

    /** 达成。终态：要开始别的目标得先 {@code /goal clear}。 */
    COMPLETE("complete", "已达成");

    private final String column;

    private final String label;

    GoalStatus(String column, String label) {
        this.column = column;
        this.label = label;
    }

    /** 落库的写法。 */
    public String column() {
        return column;
    }

    /** 给人看的写法（{@code /goal} 摘要用）。 */
    public String label() {
        return label;
    }

    /**
     * 能不能被 {@code /goal resume} 拉回活跃。
     *
     * <p>OpenClaw 的原话是"恢复已暂停、已阻塞、用量受限或预算受限的目标"——
     * 也就是<b>除 {@link #COMPLETE} 之外都能</b>。已完成是终态，恢复它等于把一件
     * 已经收尾的事重新打开，那不是恢复、是新建。
     */
    public boolean resumable() {
        return this != COMPLETE;
    }

    /**
     * 模型能不能自己把目标改成这个状态。
     *
     * <p>只有 {@link #COMPLETE} 与 {@link #BLOCKED}。OpenClaw 的原话：
     * "模型不能在不作说明的情况下暂停、恢复、清除或替换目标。这些操作仍然是
     * 操作员/会话通过 {@code /goal} 和重置命令执行的控制"。所以暂停与恢复是人的按钮，
     * 不是模型的工具参数——模型能做的只有"我做到了"与"我被卡住了"。
     */
    public boolean modelSettable() {
        return this == COMPLETE || this == BLOCKED;
    }

    /** @throws IllegalArgumentException 库里出现了枚举不认识的值时 */
    public static GoalStatus fromColumn(String column) {
        for (GoalStatus status : values()) {
            if (status.column.equals(column)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的目标状态：" + column);
    }
}
