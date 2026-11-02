package io.github.claw4j.agent.run;

/**
 * step 是哪一类动作。
 *
 * <p><b>为什么要有 kind，而不是让 result_summary 自己说清楚</b>：回读进度是按 kind 分组的——
 * A03 的计划 step 要单独取出来（"还剩几步没做"），而模型轮与工具调用汇总是"已经发生了什么"。
 * 靠读文本去区分这三类，等于把结构信息交给自然语言，压一次缩就认不出来了。
 *
 * <p><b>{@link #TOOL_CALLS} 记的是一轮工具循环的汇总，不是一次工具调用</b>（ADR-0016）：
 * 一次循环里可能有零个或多个调用，逐条落库会让这张表涨到失去作为审计投影的价值，
 * 而那些行里绝大多数（检索、候选项、失败的自纠错）在任何场景下都没人读。
 */
public enum StepKind {

    /** 模型的一轮回答（可能带工具调用，也可能不带）。 */
    MODEL_TURN("model_turn"),

    /** 一轮工具循环的汇总：调了哪些工具、结果摘要。 */
    TOOL_CALLS("tool_calls"),

    /**
     * 一个卡在等人批准上的动作（P02）。
     *
     * <p><b>单开一类而不是塞进 {@link #MODEL_TURN}</b>：混进去之后，"这件事卡过几次审批、
     * 每次都卡在哪个工具上"只能靠读摘要文本回答，而审批次数与卡点是运维最常问的一句
     * （P07 的审计投影也要按它出数）。它的状态是 {@link StepStatus#AWAITING}，
     * 批准或拒绝之后被改成 {@link StepStatus#DONE} / {@link StepStatus#FAILED}——
     * 也就是说这一行记录的是"这一步曾经停过"，不是"这一步永远停着"。
     */
    APPROVAL("approval"),

    /** 计划的一步。带状态与序号，压缩之后仍能被读到（A03）。 */
    PLAN("plan");

    private final String column;

    StepKind(String column) {
        this.column = column;
    }

    /** 落库的写法。 */
    public String column() {
        return column;
    }

    /** @throws IllegalArgumentException 库里出现了枚举不认识的值时（同 {@link RunStatus#fromColumn}） */
    public static StepKind fromColumn(String column) {
        for (StepKind kind : values()) {
            if (kind.column.equals(column)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知的 step 类型：" + column);
    }
}
