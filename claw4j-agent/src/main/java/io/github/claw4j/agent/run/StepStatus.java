package io.github.claw4j.agent.run;

/**
 * 一步的状态。
 *
 * <p><b>五个值分两组</b>：{@link #DONE} / {@link #FAILED} / {@link #AWAITING} 属于
 * "已经走到这一步的事"，{@link #PENDING} / {@link #SKIPPED} 只属于计划（{@link StepKind#PLAN}，A03）。
 * 计划写下来的那一刻一步都还没做；而"这一步决定不做了"也是计划才有的结论——
 * 一件已经发生的事没法被标成待做。
 *
 * <p><b>{@link #AWAITING} 是"停在这一步"在 step 上的样子</b>（P02）。
 * 它与 run 的 {@link RunStatus#SUSPENDED} 说的是同一件事的两半：run 说"这件事停了"，
 * step 说"停在哪个动作上"。只有 run 那一半的话，"这件事卡过几次审批、每次都卡在什么动作上"
 * 就只能靠读文本猜。它也不等于 {@link #PENDING}：待做是计划里的东西，
 * 等审批是一个已经发起的动作在等人点头。
 *
 * <p>这层区分由库上的 {@code ck_agent_step_status} 按 {@code kind} 分支守着（V7、V11），
 * 枚举里<b>不重复判一次</b>：两处都判的话，改一处就够的那天要改两处，
 * 而漏改的表现是"写进去了、读的时候才炸"。
 */
public enum StepStatus {

    DONE("done"),
    FAILED("failed"),

    /** 这一步已经发起了，卡在等人批准。批准或拒绝之后由服务端接着走（P02）。 */
    AWAITING("awaiting"),

    /** 计划里还没做的那一步。 */
    PENDING("pending"),

    /** 计划里决定不做的那一步。不是"做失败了"——失败只会发生在已经动手之后。 */
    SKIPPED("skipped");

    private final String column;

    StepStatus(String column) {
        this.column = column;
    }

    /** 落库的写法。 */
    public String column() {
        return column;
    }

    /**
     * 给模型看的说法。recap 与工具回执用的是它，不是 {@link #column()}——
     * 两处各写一份 switch 的话，加一个值就得记得改两处。
     */
    public String label() {
        return switch (this) {
            case DONE -> "已做";
            case FAILED -> "失败";
            case AWAITING -> "等审批";
            case PENDING -> "待做";
            case SKIPPED -> "跳过";
        };
    }

    /** @throws IllegalArgumentException 库里出现了枚举不认识的值时 */
    public static StepStatus fromColumn(String column) {
        for (StepStatus status : values()) {
            if (status.column.equals(column)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的 step 状态：" + column);
    }
}
