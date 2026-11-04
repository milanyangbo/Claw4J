package io.github.claw4j.eval;

import java.util.List;
import java.util.Objects;

/**
 * 一条用例的判定结果：三件事各算各的，再加一句"为什么没过"。
 *
 * <p><b>为什么不合成一个分数</b>：三件事的失败含义完全不同。答案没命中是"答错了"，
 * 轨迹没命中是"工具没调对"，越权没守住是"该拒的没拒或者拒了还漏了数"。
 * 合成 0.67 之后，看报告的人还得回去逐条查是哪一类——而这三类的下一步动作不一样
 * （改提示词 / 改工具描述 / 改角色配置）。
 *
 * <p>{@link #failures()} 里放的是人能读的一句话，不是错误码：报告的第一读者是
 * 决定"这版能不能发"的人，他需要知道"缺 128400"而不是 {@code ANSWER_MISS}。
 *
 * @param caseId   哪条用例
 * @param answerHit  答案命中：该出现的都出现、不该出现的都没出现
 * @param traceHit   轨迹命中：该调的工具都在调过的集合里
 * @param authzHeld  越权守住：该拒的真拒了、审计里查得到、且没顺手把数漏出去
 * @param failures   没过的原因，逐条
 */
public record EvalVerdict(String caseId,
                          boolean answerHit,
                          boolean traceHit,
                          boolean authzHeld,
                          List<String> failures) {

    public EvalVerdict {
        Objects.requireNonNull(caseId, "caseId");
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** 这条用例是不是全过了。报告里的 {@code totals} 就是按它数的。 */
    public boolean passed() {
        return answerHit && traceHit && authzHeld;
    }

    /** 报告与控制台共用的一行：{@code sales-weekly-east  命中 轨迹 越权}。 */
    public String summary() {
        return String.format("%-32s %s %s %s%s", caseId,
                mark(answerHit, "命中"), mark(traceHit, "轨迹"), mark(authzHeld, "越权"),
                failures.isEmpty() ? "" : "  ← " + String.join("；", failures));
    }

    private static String mark(boolean ok, String label) {
        return (ok ? "✓" : "✗") + label;
    }
}
