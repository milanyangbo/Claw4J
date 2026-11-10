package io.github.claw4j.agent.goal;

/**
 * 目标的状态。六态照 OpenClaw 的词表
 * （{@code packages/gateway-protocol/src/schema/sessions-goal.ts:11-18}），一个字都不改名。
 *
 * <p>{@link #BUDGET_LIMITED} 与 {@link #USAGE_LIMITED} 是「停下来但没做完」，与
 * {@link #COMPLETE} 的区别在恢复之后的样子：受限状态被 {@code /goal resume} 拉回来时开一个
 * 新的预算窗口、用量从 0 起算；达成状态 resume 只是重新推进同一件事，用量接着累计。
 * 混成一个状态时没人知道该继续还是该换目标。
 *
 * <p>{@link #USAGE_LIMITED} 进不去：OpenClaw 自己也标着「为将来的用量限制状态保留」
 * （{@code docs/tools/goal.md:136}）。词表照抄是因为少一个状态将来要同时改约束、命令与
 * 注入逻辑三处，而多一个用不到的值只多一行。
 *
 * <p>列值用小写下划线，与 {@code V3__session.sql} 的 check 约束逐字对应。改这里的名字必须
 * 同时改迁移：约束会拒未知值，写错的表现是插入直接报错，而不是静默存一个没人认得的状态。
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

    /**
     * 达成。它不是终态：{@code /goal resume} 能从它继续推进同一件事。
     *
     * <p>要开始别的目标才需要 {@code /goal clear}。原文：「它保持达成状态，
     * 直到你显式地用 {@code /goal resume} 或控制台里的 Resume 再次推进同一个目标」。
     */
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
     * 判断能不能被 {@code /goal resume} 拉回活跃。
     *
     * <p>每一个状态都能。原文的命令表是「{@code /goal resume [note]}：恢复已暂停、已阻塞、
     * 受限或已完成的目标」，状态那一节也写着 {@code complete}「保持达成状态，直到你显式地
     * 用 {@code /goal resume} 再次推进同一个目标」。
     *
     * <p>唯一没有动作的是 {@link #ACTIVE} 自己，它已经是活跃的。另一句话讲的是不能在达成之后
     * 直接 edit、pause、block，要先 resume，别读成「达成之后不能 resume」。
     *
     * @return 除 {@link #ACTIVE} 之外都为 true
     */
    public boolean resumable() {
        return this != ACTIVE;
    }

    /**
     * 判断模型能不能自己把目标改成这个状态。
     *
     * <p>只有 {@link #COMPLETE} 与 {@link #BLOCKED}。OpenClaw 的原话：「模型不能在不作说明的
     * 情况下暂停、恢复、清除或替换目标。这些操作仍然是操作员或会话通过 {@code /goal} 和
     * 重置命令执行的控制」。所以暂停与恢复是人的按钮，不是模型的工具参数，模型能做的只有
     * 「我做到了」与「我被卡住了」。
     *
     * @return 只有 {@link #COMPLETE} 与 {@link #BLOCKED} 为 true
     */
    public boolean modelSettable() {
        return this == COMPLETE || this == BLOCKED;
    }

    /**
     * 按库里的列值反查状态。
     *
     * @param column 库里的列值，例如 {@code budget_limited}
     * @return 对应的状态
     * @throws IllegalArgumentException 库里出现了枚举不认识的值
     */
    public static GoalStatus fromColumn(String column) {
        for (GoalStatus status : values()) {
            if (status.column.equals(column)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的目标状态：" + column);
    }
}
