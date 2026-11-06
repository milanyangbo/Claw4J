package io.github.claw4j.agent.run;

/**
 * 一轮运行的结局（ADR-0027）。
 *
 * <p><b>没有 {@code suspended}</b>：run 的寿命是一轮。等审批不是"这件事停在这里"，
 * 而是"这一轮结束了，它回给用户的是「我需要你批准」"。批准之后发生的是<b>同一段会话里
 * 的下一轮</b>，不是把这一轮拉起来继续。以前那个 suspended 状态配着
 * {@code reopen} / {@code resume} 一整套，存在的理由都是"一件事能跨请求活着"，
 * 而那个前提在 OpenClaw 里不成立（它的连续性靠 sessionKey，不是靠 run）。
 *
 * <p>{@link #RUNNING} 是一个<b>在跑的标记</b>，不是可以"接着做"的状态：进程被杀之后
 * 库里这一行会永远停在 running，而本仓库没有租约，从库里分不出"还在跑"与"已经死了"。
 * 这不再是问题——没有人会拿它去恢复任何东西。
 *
 * <p>列值用小写下划线，与 {@code V21__approval_conversation.sql} 的 check 约束逐字对应。
 */
public enum RunStatus {

    RUNNING("running"),
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
