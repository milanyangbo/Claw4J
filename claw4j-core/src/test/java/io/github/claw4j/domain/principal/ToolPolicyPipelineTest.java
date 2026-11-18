package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略管线：单层语义、逐层收窄、审计只在真删了工具时留痕（ADR-0030 裁决二）。
 *
 * <p>这套语义照 OpenClaw 的 {@code makeToolPolicyMatcher} 与 {@code filterToolsByPolicy}。
 * 三条最容易写错的在这里各钉一条：deny 先判、allow 空则全放、allow 非空则封闭其余。
 *
 * <p>断言用 JUnit 自带的：core 的依赖面只有 JUnit 与 ArchUnit（见 {@code CoreBoundariesTest}）。
 */
class ToolPolicyPipelineTest {

    private static final List<String> TOOLS = List.of("Bash", "Read", "Write", "querySales", "Skill");

    @Test
    @DisplayName("deny 先判：allow 里有它也没用，两个相反的意图同时写着时卡住而不是放行")
    void denyWinsOverAllow() {
        ToolPolicyLayer layer = new ToolPolicyLayer("global",
                Set.of("Bash", "Read"), Set.of("Bash"), Set.of());

        assertFalse(layer.permits("Bash"));
        assertTrue(layer.permits("Read"));
        assertEquals("deny", layer.reasonFor("Bash"));
    }

    @Test
    @DisplayName("allow 非空时其余全封：这一层是白名单，不是\"额外再加\"")
    void nonEmptyAllowClosesEverythingElse() {
        ToolPolicyPipeline.Outcome outcome =
                new ToolPolicyPipeline(List.of(ToolPolicyLayer.allowing("global", Set.of("Read", "Write"))))
                        .apply(TOOLS);

        assertEquals(List.of("Read", "Write"), outcome.kept());
        assertEquals(List.of("Bash", "querySales", "Skill"), outcome.steps().getFirst().notAllowed());
        assertTrue(outcome.steps().getFirst().denied().isEmpty());
    }

    @Test
    @DisplayName("allow 为空则全放：空不是\"什么都不放\"，那会让任何一层漏配变成全封")
    void emptyAllowMeansNoNarrowing() {
        ToolPolicyPipeline.Outcome outcome =
                new ToolPolicyPipeline(List.of(ToolPolicyLayer.denying("global", Set.of("Bash"))))
                        .apply(TOOLS);

        assertEquals(List.of("Read", "Write", "querySales", "Skill"), outcome.kept());
    }

    @Test
    @DisplayName("alsoAllow 与 allow 合并，不改\"allow 为空则不收窄\"：它只放宽，不收窄")
    void alsoAllowOnlyWidens() {
        // allow 非空：elseAllow 里的项能进。
        ToolPolicyLayer narrow = new ToolPolicyLayer("global",
                Set.of("Read"), Set.of(), Set.of("Skill"));
        assertTrue(narrow.permits("Skill"));
        assertFalse(narrow.permits("Bash"));

        // allow 空：alsoAllow 不制造一张"只放这几个"的白名单。
        ToolPolicyLayer loose = new ToolPolicyLayer("global", Set.of(), Set.of(), Set.of("Skill"));
        assertTrue(loose.permits("Bash"));
        assertTrue(loose.permits("Skill"));
    }

    @Test
    @DisplayName("逐层收窄：后一层只能在前一层的结果上减，加不回来")
    void layersOnlyNarrow() {
        ToolPolicyPipeline pipeline = new ToolPolicyPipeline(List.of(
                ToolPolicyLayer.allowing("profile", Set.of("Bash", "Read", "Write", "Skill")),
                ToolPolicyLayer.allowing("allow", Set.of("Bash", "Read", "Skill")),
                ToolPolicyLayer.denying("deny", Set.of("Skill"))));

        ToolPolicyPipeline.Outcome outcome = pipeline.apply(TOOLS);

        assertEquals(List.of("Bash", "Read"), outcome.kept());
        // 三层各留一条记录，名字与顺序能对上配置。
        assertEquals(List.of("profile", "allow", "deny"),
                outcome.steps().stream().map(ToolPolicyPipeline.Step::label).toList());
    }

    @Test
    @DisplayName("只在真删了工具时留审计：没删的层不产出记录，否则十层管线刷十条空行")
    void auditOnlyWhenToolsRemoved() {
        ToolPolicyPipeline.Outcome outcome = new ToolPolicyPipeline(List.of(
                // 这一层的 deny 一个都不在清单里：它什么也没删。
                ToolPolicyLayer.denying("stale", Set.of("SomeoneElsesTool")),
                ToolPolicyLayer.denying("real", Set.of("Bash"))))
                .apply(TOOLS);

        assertEquals(List.of("real"),
                outcome.steps().stream().map(ToolPolicyPipeline.Step::label).toList());
        assertEquals(List.of("Bash"), outcome.dropped());
    }

    @Test
    @DisplayName("三张表都空的层整层跳过：不占审计，也不参与\"有没有挡掉东西\"的判断")
    void emptyLayerIsSkippedEntirely() {
        ToolPolicyPipeline.Outcome outcome = new ToolPolicyPipeline(List.of(
                new ToolPolicyLayer("unconfigured", Set.of(), Set.of(), Set.of())))
                .apply(TOOLS);

        assertEquals(TOOLS, outcome.kept());
        assertTrue(outcome.steps().isEmpty());
        assertFalse(outcome.droppedAnything());
    }

    @Test
    @DisplayName("挡掉的原因分两个桶：该删 deny 还是该补 allow，看日志的人要能分开")
    void reasonsAreBucketed() {
        ToolPolicyPipeline.Outcome outcome = new ToolPolicyPipeline(List.of(
                new ToolPolicyLayer("mixed", Set.of("Read", "Write"), Set.of("Write"), Set.of())))
                .apply(TOOLS);

        ToolPolicyPipeline.Step step = outcome.steps().getFirst();
        assertEquals(List.of("Write"), step.denied());
        assertEquals(List.of("Bash", "querySales", "Skill"), step.notAllowed());
        assertEquals(4, step.count());
    }

    @Test
    @DisplayName("空管线：清单原样返回，顺序与重复都不动")
    void noneKeepsEverything() {
        List<String> withDuplicate = List.of("Bash", "Read", "Bash");

        ToolPolicyPipeline.Outcome outcome = ToolPolicyPipeline.none().apply(withDuplicate);

        assertEquals(withDuplicate, outcome.kept());
        assertFalse(outcome.droppedAnything());
    }

    @Test
    @DisplayName("层内的空白项被收掉：yml 里手写列表常见，不留一个永远匹配不到的空串")
    void blankEntriesAreNormalized() {
        ToolPolicyLayer layer = new ToolPolicyLayer("global",
                Set.of("  Read  ", "   "), Set.of(""), Set.of());

        assertEquals(Set.of("Read"), layer.allow());
        assertTrue(layer.deny().isEmpty());
    }
}
