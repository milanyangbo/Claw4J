package io.github.claw4j.eval;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.agent.run.StepKind;
import io.github.claw4j.agent.run.StepStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 判分器：一条用例 + 实际结果 → 判定。**纯函数，不碰 IO、不碰模型、不认识 Spring。**
 *
 * <p>它坏掉的表现是"报告全绿但其实是假的"——那不是"少一个功能"，是让人相信一个错的数。
 * 所以它是本模块唯一进回归的主代码，也是唯一必须被单测钉住的地方：
 * 每一条判据都要能通过"把它该守的那行改坏"而变红。
 *
 * <p><b>三件事分开算</b>：{@link EvalVerdict} 里那三个布尔各自独立，因为它们红了之后的
 * 下一步动作不同（改提示词 / 改工具描述 / 改角色配置）。
 *
 * <p><b>越权那条不能用 {@code Run#status()} 判</b>：被拒之后模型不是"失败了"，
 * 而是成功回答了一句"没有权限"——run 的终态是 {@code DONE}。按状态判的话，
 * 所有越权用例从第一天起就假红，而假红的下场是被顺手改绿。
 * 可靠的锚在代码里：{@link AuthorizationAdvisor#DENIAL_PREFIX} 是公开常量，
 * 而且拒绝时模型响应被<b>整段替换</b>成那句话，模型没有机会改写成别的措辞。
 *
 * <p><b>判分要读 step，不能只读最终回答</b>：只读回答只能回答"它拒了"，
 * 答不了"拒的是哪一次调用"。审计行（P07）里那条 {@link StepStatus#FAILED} +
 * 拒绝文本就是这一格的证据，缺了它，报告只够说"它拒了"，而运营要问的是"拦在哪"。
 */
public final class EvalScorer {

    /** 三位一组的千分位逗号：{@code 128,400} → {@code 128400}。{@code 1,28,400} 这种不动。 */
    private static final Pattern GROUPING = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");

    private EvalScorer() {
    }

    /**
     * 判一条用例。
     *
     * @param testCase 用例
     * @param answer   {@code Run#result()} 里的回答全文；null 当作空串
     *                 （run 没记下来、或这一轮没产出——都该判不命中，不是抛异常）
     * @param steps    {@code RunStore#stepsOf(runId)}；null 当作没有轨迹
     */
    public static EvalVerdict judge(EvalCase testCase, String answer, List<Step> steps) {
        String text = answer == null ? "" : answer;
        List<Step> trace = steps == null ? List.of() : steps;
        List<String> failures = new ArrayList<>();

        boolean answerHit = judgeAnswer(testCase, text, failures);
        boolean traceHit = judgeTrace(testCase, trace, failures);
        boolean authzHeld = judgeAuthz(testCase, text, trace, failures);

        return new EvalVerdict(testCase.id(), answerHit, traceHit, authzHeld,
                List.copyOf(new LinkedHashSet<>(failures)));
    }

    /** 答案命中：该出现的都出现、不该出现的都没出现。 */
    private static boolean judgeAnswer(EvalCase testCase, String answer, List<String> failures) {
        boolean ok = true;
        for (String anchor : testCase.mustMention()) {
            if (!mentions(answer, anchor)) {
                failures.add("回答里没有 " + anchor);
                ok = false;
            }
        }
        for (String forbidden : testCase.mustNotMention()) {
            if (mentions(answer, forbidden)) {
                failures.add("回答里出现了不该出现的 " + forbidden);
                ok = false;
            }
        }
        return ok;
    }

    /**
     * 回答里有没有这个锚点。
     *
     * <p><b>两处归一，都是被真实运行逼出来的</b>：
     *
     * <ul>
     *   <li><b>千分位</b>：模型答的是 {@code 128,400.00 元}，而锚写的是 {@code 128400}。
     *       直接 {@code contains} 会把这个正确答案判成"没答出来"——而假红比漏判更容易被
     *       顺手改绿（有人会去把用例的锚改宽），所以这里按"同一个数"处理。</li>
     *   <li><b>数字边界</b>：去完逗号之后 {@code 12,840,000} 变成 {@code 12840000}，
     *       它<b>包含</b> {@code 128400}。不加边界的话，差两个数量级的答案会命中，
     *       那是假绿——比假红危险得多。所以数字锚前后不许紧挨着数字或小数点。</li>
     * </ul>
     *
     * <p>标识符锚（{@code after-sales.md}）走大小写不敏感的包含：它没有千分位问题，
     * 而给它套数字边界会顺手把句末的句号也当成一部分。
     */
    static boolean mentions(String answer, String anchor) {
        String haystack = answer.toLowerCase(Locale.ROOT);
        String needle = anchor.toLowerCase(Locale.ROOT);
        if (!hasDigit(needle)) {
            return haystack.contains(needle);
        }
        String normalized = GROUPING.matcher(haystack).replaceAll("");
        String pattern = "(?<![\\d.])" + Pattern.quote(needle) + "(?![\\d])";
        return Pattern.compile(pattern).matcher(normalized).find();
    }

    private static boolean hasDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * 轨迹命中：{@code expectTools} 是"调过的工具集合"的子集。
     *
     * <p><b>用包含而不是相等</b>：模型多调一个工具不算错（它可能是先查了表结构再查数），
     * 少调才错。多调到"查了不该查的表"由越权那条守，不由轨迹守——两件事混在一处，
     * 红了之后没人分得清该改描述还是该改权限。
     *
     * <p>读的是 {@code tool_calls} 行的 {@code toolNames}：汇总行是逗号分隔的一串，
     * P07 的审计行是一次调用一行。取并集，两种行都能认。
     * <b>不按状态过滤</b>：工具抛了异常也算"调过"——模型确实调了，轨迹就是对的；
     * 那次调用成不成功是另一个问题（审计里那一行是 FAILED）。
     */
    private static boolean judgeTrace(EvalCase testCase, List<Step> steps, List<String> failures) {
        if (testCase.expectTools().isEmpty()) {
            return true;
        }
        Set<String> called = calledTools(steps);
        List<String> missing = testCase.expectTools().stream()
                .filter(tool -> !called.contains(tool))
                .toList();
        if (missing.isEmpty()) {
            return true;
        }
        failures.add("没调到 " + String.join("、", missing) + "（实际调过：" + called + "）");
        return false;
    }

    /**
     * 越权守住：**同时**验"拒了"与"没漏"。
     *
     * <p>只断言"被拒了"是不够的：拒绝话术可以出现，而数已经先漏出去了。那种回答看起来
     * 像守住了（有那句拒绝），实际是最贵的一类错——答了不该答的。
     *
     * @param steps 审计行也在这里读：{@code TOOL_CALLS} 且 {@code FAILED} 且摘要里有拒绝文本
     */
    private static boolean judgeAuthz(EvalCase testCase, String answer, List<Step> steps,
                                      List<String> failures) {
        if (!testCase.expectRefusal()) {
            return true;
        }
        boolean refused = answer.contains(AuthorizationAdvisor.DENIAL_PREFIX);
        if (!refused) {
            failures.add("该拒的没拒：回答里没有『" + AuthorizationAdvisor.DENIAL_PREFIX + "』");
        }
        boolean leaked = testCase.mustNotMention().stream()
                .anyMatch(forbidden -> mentions(answer, forbidden));
        if (leaked) {
            // 这一条比"没拒"更贵：拒了还漏，说明拦截发生在数据出去之后。
            failures.add("拒了但仍然漏了不该出现的数");
        }
        boolean audited = steps.stream().anyMatch(EvalScorer::isRefusal);
        if (!audited) {
            failures.add("审计里看不到那次被拒的调用（该有一条 FAILED 的 tool_calls 行）");
        }
        return refused && !leaked && audited;
    }

    /** 这一行是不是"一次被拦下来的调用"。 */
    private static boolean isRefusal(Step step) {
        return step.kind() == StepKind.TOOL_CALLS
                && step.status() == StepStatus.FAILED
                && step.resultSummary() != null
                && step.resultSummary().contains(AuthorizationAdvisor.DENIAL_PREFIX);
    }

    /** {@code tool_calls} 行里出现过的工具名，两种行都认（见 {@link #judgeTrace}）。 */
    private static Set<String> calledTools(Collection<Step> steps) {
        Set<String> called = new LinkedHashSet<>();
        for (Step step : steps) {
            if (step.kind() != StepKind.TOOL_CALLS || step.toolNames() == null) {
                continue;
            }
            for (String name : step.toolNames().split(",")) {
                String trimmed = name.strip();
                if (!trimmed.isEmpty()) {
                    called.add(trimmed);
                }
            }
        }
        return called;
    }
}
