package io.github.claw4j.eval;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判分口径：三个布尔各自守一条规则，每一条都能靠"把它该守的那行改坏"而变红。
 *
 * <p>为什么这个文件这么重要：本模块的其他部分坏了都看得见（跑不起来、报告没生成），
 * 只有判分器坏了是<b>全绿</b>——而它一绿，整条 M8 就建立在一个假的数上。
 * ADR-0019 的原话是"改一句工具描述，报告里的分数能看到变化"，
 * 这句话成立的前提全在这十几条里。
 *
 * <p>每条用真的 {@link EvalScorer.Trace} 而不是替身：判分读的就是审计那几列
 * （工具名 / 成没成 / 摘要），替身会把"读错了字段"这类错误挡住——
 * 而读错字段的表现正是一条都不命中。
 */
class EvalScorerTest {

    private static final String DENIAL = AuthorizationAdvisor.DENIAL_PREFIX;

    // ------------------------------------------------------------------ 答案命中

    @Test
    @DisplayName("锚点全在回答里：命中")
    void everyAnchorPresentHits() {
        EvalCase testCase = caseOf(List.of("128400"), List.of("31500"), List.of("querySales"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase, "上周华东区成交额 128400 元。", List.of(tool("querySales")));

        assertThat(verdict.answerHit()).isTrue();
        assertThat(verdict.failures()).isEmpty();
    }

    @Test
    @DisplayName("少一个锚点：不命中，而且 failures 点名是哪一个")
    void aMissingAnchorNamesItself() {
        EvalCase testCase = caseOf(List.of("128400", "74400"), List.of(), List.of("querySales"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase, "上周华东区成交额 128400 元。", List.of(tool("querySales")));

        assertThat(verdict.answerHit()).isFalse();
        assertThat(verdict.failures()).anyMatch(reason -> reason.contains("74400"));
    }

    @Test
    @DisplayName("回答里出现了不许出现的数：不命中——答了不该答的比答漏了更贵")
    void aForbiddenNumberFailsTheAnswer() {
        EvalCase testCase = caseOf(List.of("128400"), List.of("31500"), List.of("querySales"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase,
                "华东 128400，华北 31500。", List.of(tool("querySales")));

        assertThat(verdict.answerHit()).isFalse();
        assertThat(verdict.failures()).anyMatch(reason -> reason.contains("31500"));
    }

    @Test
    @DisplayName("回答为空/null：判不命中，不抛——run 没记下来也是要判的那种情况")
    void anEmptyAnswerFailsInsteadOfThrowing() {
        EvalCase testCase = caseOf(List.of("128400"), List.of(), List.of("querySales"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase, null, null);

        assertThat(verdict.answerHit()).isFalse();
        assertThat(verdict.traceHit()).isFalse();
    }

    @Test
    @DisplayName("千分位不算答错：128,400.00 与 128400 是同一个数")
    void aGroupedNumberStillHits() {
        // 真实运行里模型答的就是这个形状。直接 contains 会把正确答案判成"没答出来"，
        // 而假红的下场是有人去把用例的锚改宽——那之后这条用例就白写了。
        EvalCase testCase = caseOf(List.of("128400"), List.of(), List.of("querySales"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase,
                "上周华东区成交额：128,400.00 元，共 2 笔订单。", List.of(tool("querySales")));

        assertThat(verdict.answerHit()).isTrue();
    }

    @Test
    @DisplayName("数字要有边界：12,840,000 不该被 128400 命中——假绿比假红危险")
    void aLongerNumberDoesNotHit() {
        EvalCase expect = caseOf(List.of("128400"), List.of(), List.of("querySales"), false);
        EvalCase forbid = caseOf(List.of(), List.of("128400"), List.of("querySales"), false);

        // 该出现而没出现：差两个数量级的答案不该被认成"答对了"。
        assertThat(EvalScorer.judge(expect, "上周华东区成交额：12,840,000 元。", List.of(tool("querySales")))
                .answerHit()).isFalse();
        // 不许出现而没出现：同一个串也不该被认成"漏数了"。
        assertThat(EvalScorer.judge(forbid, "上周华东区成交额：12,840,000 元。", List.of(tool("querySales")))
                .answerHit()).isTrue();
    }

    // ------------------------------------------------------------------ 轨迹

    @Test
    @DisplayName("多调一个工具不算错：集合包含而不是相等")
    void extraToolsAreNotAFailure() {
        EvalCase testCase = caseOf(List.of(), List.of(), List.of("querySales"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase, "128400",
                List.of(tool("querySales"), tool("searchDocs")));

        assertThat(verdict.traceHit()).isTrue();
    }

    @Test
    @DisplayName("少调一个：不命中，failures 里写清缺哪个、实际调过哪些")
    void aMissingToolNamesItself() {
        EvalCase testCase = caseOf(List.of(), List.of(), List.of("querySales", "searchDocs"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase, "128400", List.of(tool("searchDocs")));

        assertThat(verdict.traceHit()).isFalse();
        assertThat(verdict.failures()).anyMatch(reason -> reason.contains("querySales"));
    }

    @Test
    @DisplayName("轨迹是一次调用一条：两个工具都调到才算命中，只调到一个就不算")
    void oneRowIsOneCall() {
        EvalCase testCase = caseOf(List.of(), List.of(), List.of("querySales", "searchDocs"), false);

        assertThat(EvalScorer.judge(testCase, "128400",
                List.of(tool("querySales"), tool(" searchDocs "))).traceHit()).isTrue();
        assertThat(EvalScorer.judge(testCase, "128400", List.of(tool(" searchDocs "))).traceHit())
                .isFalse();
    }

    @Test
    @DisplayName("没记名字的行不算轨迹：审计允许缺字段，但缺了就不该被当成调过")
    void aRowWithoutAToolNameDoesNotCount() {
        EvalCase testCase = caseOf(List.of(), List.of(), List.of("querySales"), false);

        assertThat(EvalScorer.judge(testCase, "128400", List.of(tool(null))).traceHit()).isFalse();
    }

    @Test
    @DisplayName("三件事分开算：答案可以命中而轨迹不命中，passed 仍是假")
    void theThreeVerdictsAreIndependent() {
        EvalCase testCase = caseOf(List.of("128400"), List.of(), List.of("searchDocs"), false);

        EvalVerdict verdict = EvalScorer.judge(testCase, "128400", List.of(tool("querySales")));

        assertThat(verdict.answerHit()).isTrue();
        assertThat(verdict.traceHit()).isFalse();
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.summary()).contains("sales-sample").contains("✗").contains("没调到");
    }

    private static EvalCase refusalCase() {
        return caseOf(List.of(), List.of("128400"), List.of("querySales"), true);
    }

    private static EvalScorer.Trace refusalRow() {
        return new EvalScorer.Trace("querySales", true,
                DENIAL + "：角色 guest 不允许访问表 sales_order。");
    }

    private static EvalCase caseOf(List<String> mustMention, List<String> mustNotMention,
                                   List<String> expectTools, boolean expectRefusal) {
        return new EvalCase("sales-sample", "上周华东区成交额是多少？", "analyst",
                List.of(), mustMention, mustNotMention, expectTools, expectRefusal, null);
    }

    private static EvalScorer.Trace tool(String name) {
        return new EvalScorer.Trace(name, false, null);
    }

    private static EvalScorer.Trace refusal(String toolName) {
        return new EvalScorer.Trace(toolName, true, DENIAL + " 这次调用被拒绝");
    }
}
