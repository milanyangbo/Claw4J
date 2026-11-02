package io.github.claw4j.agent.run;

/**
 * 追加一步时的入参。
 *
 * <p>单独一个类型而不是六个参数，是因为这六样总是一起出现、一起传下去——
 * 也因为 {@code seq} 刻意<b>不在</b>这里：序号由存储分配（{@code max(seq) + 1}），
 * 调用方算的并发下会撞号，而撞号的表现是"做到第几步"给出两个答案。
 *
 * <p><b>审计那几样是可选的第七个字段</b>（P07）：只有工具调用那一类 step 有值。
 * 六个参数的构造器留着，因为模型轮与计划那两类 step 与审计无关——
 * 让它们也传一个 {@code null}，只会让调用点多出六个字的噪声。
 *
 * @param toolNames     逗号分隔的工具名；没有工具传 null，不要传空串——
 *                       空串会被当成"这一步没有调工具"还是"没填"，读的人要猜
 * @param audit         审计要的几样；null 表示这一步不是工具调用（或这一轮没有 run 存储）
 */
public record StepInput(String runId,
                        StepKind kind,
                        String input,
                        String toolNames,
                        String resultSummary,
                        StepStatus status,
                        StepAudit audit) {

    public StepInput {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("step 必须属于某个 run");
        }
    }

    /** 没有审计的那几类 step：模型轮、计划、等审批。 */
    public StepInput(String runId, StepKind kind, String input, String toolNames,
                     String resultSummary, StepStatus status) {
        this(runId, kind, input, toolNames, resultSummary, status, null);
    }
}
