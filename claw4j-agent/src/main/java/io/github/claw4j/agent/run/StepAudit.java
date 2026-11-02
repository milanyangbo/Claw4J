package io.github.claw4j.agent.run;

/**
 * 一步上"审计要的那几样"（P07，ADR-0023）。
 *
 * <p><b>为什么挂在 step 上而不是另外一张表</b>：step 是执行真相，审计只补发起人、
 * 决定、参数摘要与那条批准的编号。另建一张表能跑，但同一件事从此有两个答案，
 * 对不上的那天没人知道该信哪个——一张记着"跑了两次"、另一张记着"跑了三次"。
 *
 * <p><b>为什么单独一个类型，而不是给 {@link StepInput} 再加六个参数</b>：
 * 这六样只在"工具调用"那一类 step 上有值，其余几类（模型轮、计划）都是空的。
 * 并进去的话，每个调用点都要多传六个 {@code null}，而 {@code null} 传错位置编译器一个字都不会说。
 *
 * <p><b>没有任何字段是"为了排查方便"加的</b>：参数原文与产出原文都不在这里，
 * 也不在库里那一列上。审计回答的是"谁在什么时候让谁做了什么、谁批的"，
 * 不是"当时参数长什么样"——那件事去问会话，而会话有它自己的留存。
 *
 * @param actor       发起这次调用的人（{@code Principal#userId}）。可以为空：没带身份的执行
 *                    仍然要留一行（"有个工具跑了"本身就是审计要的事实）
 * @param role        发起时的角色。收权之后第一个被问的就是这一格
 * @param outcome     这一次的决定，只能是三个常量之一
 * @param argsDigest  参数指纹，与审批那条同一个函数（{@code CallFingerprint}）。
 *                    为空表示这一轮连参数都没收全（流式下被拦在授权那一道时就是这样）
 * @param argsSummary 给人看的参数摘要，脱敏且截断。不存原文
 * @param approvalId  这次调用是哪条批准放行的。为空表示不需要审批，或者这一轮没有审批设施
 */
public record StepAudit(String actor,
                        String role,
                        String outcome,
                        String argsDigest,
                        String argsSummary,
                        String approvalId) {

    /** 真跑了（成没成看 step 的 status，这里说的是"它被执行过"）。 */
    public static final String EXECUTED = "executed";

    /** 没跑，而且不是因为等谁：鉴权或权限登记挡住了。 */
    public static final String REJECTED = "rejected";

    /** 没跑，停在等人点头。这三条里的这一条，"下一步该谁动"与其他两条完全不同。 */
    public static final String AWAITING_APPROVAL = "awaiting_approval";

    public StepAudit {
        if (!EXECUTED.equals(outcome) && !REJECTED.equals(outcome) && !AWAITING_APPROVAL.equals(outcome)) {
            // 与 V14 的 check 约束同一个口径：库里写不进去的东西，在构造时就说清楚是哪一步错了。
            throw new IllegalArgumentException("审计行必须说清这一次的决定：" + outcome);
        }
    }

    /** 真跑了一次。 */
    public static StepAudit executed(String actor, String role, String argsDigest,
                                     String argsSummary, String approvalId) {
        return new StepAudit(actor, role, EXECUTED, argsDigest, argsSummary, approvalId);
    }

    /** 被拦下来了，而且再等也不会通过（要改配置，不是要找谁点头）。 */
    public static StepAudit rejected(String actor, String role, String argsDigest, String argsSummary) {
        return new StepAudit(actor, role, REJECTED, argsDigest, argsSummary, null);
    }

    /** 停在等审批：这一行带着审批编号，批准之后它会与执行那一行对起来。 */
    public static StepAudit awaiting(String actor, String role, String argsDigest,
                                     String argsSummary, String approvalId) {
        return new StepAudit(actor, role, AWAITING_APPROVAL, argsDigest, argsSummary, approvalId);
    }
}
