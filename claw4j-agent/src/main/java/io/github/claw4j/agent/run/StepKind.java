package io.github.claw4j.agent.run;

/**
 * step 是哪一类动作。
 *
 * <p><b>为什么要有 kind，而不是让 result_summary 自己说清楚</b>：回读进度是按 kind 分组的——
 * A03 的计划 step 要单独取出来（"还剩几步没做"），而模型轮与工具调用汇总是"已经发生了什么"。
 * 靠读文本去区分这三类，等于把结构信息交给自然语言，压一次缩就认不出来了。
 *
 * <p><b>{@link #TOOL_CALLS} 有两类行，别当成一回事</b>：一类是一轮工具循环的<b>汇总</b>
 * （调了哪些工具、结果摘要），一类是<b>一次工具调用一行</b>的审计（P07）。后者正是
 * ADR-0016"工具循环内部不落 step"被松开的那一格——ADR-0023 换了条件而不是取消它：
 * 只落参数摘要与指纹、不落原文，用一行换"这次调用是哪次批准的"。检索、候选项、
 * 失败的自纠错仍然不落，所以这张表还能同时当执行记录与审计投影用。
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
