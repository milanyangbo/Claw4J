package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存在性过滤的语义：{@code include} 非空则必须命中，{@code exclude} 命中即排除，{@code *} 按整串匹配。
 *
 * <p>这几条都是"配置看起来对、效果不对"的形状，所以逐条钉：拿 {@code contains} 实现通配、
 * 让 {@code include} 盖过 {@code exclude}、把空 {@code include} 当成"什么都不留"，
 * 三种错都能让模型在一份看似正常的配置下少一个能力，而日志里一个字都不提。
 */
class ToolFilterTest {

    @Test
    @DisplayName("include 非空时只有命中的留下")
    void includeKeepsOnlyMatches() {
        ToolFilter filter = new ToolFilter(List.of("runtimeSnapshot", "cronNext"), List.of());

        assertTrue(filter.matches("runtimeSnapshot"));
        assertTrue(filter.matches("cronNext"));
        assertFalse(filter.matches("simulateSlowCall"), "没点名就是不要：include 是白名单，不是偏好");
    }

    @Test
    @DisplayName("include 为空时其余全留，只受 exclude 约束")
    void excludeWinsWhenNoInclude() {
        ToolFilter filter = new ToolFilter(List.of(), List.of("simulateSlowCall"));

        assertFalse(filter.matches("simulateSlowCall"));
        assertTrue(filter.matches("runtimeSnapshot"));
        assertTrue(filter.matches("什么都能过"));
    }

    @Test
    @DisplayName("两边都点到同一个名字时以排除为准：同一份配置自相矛盾，结果是不给")
    void excludeBeatsInclude() {
        ToolFilter filter = new ToolFilter(List.of("querySales"), List.of("querySales"));

        assertFalse(filter.matches("querySales"));
    }

    @Test
    @DisplayName("通配按整串匹配：runtime* 不匹配 xcronNext，也不匹配夹在中间的名字")
    void globMatchesWholeName() {
        ToolFilter filter = new ToolFilter(List.of("runtime*"), List.of());

        assertTrue(filter.matches("runtimeSnapshot"));
        assertTrue(filter.matches("runtime"), "* 可以匹配空串");
        assertFalse(filter.matches("xcronNext"));
        assertFalse(filter.matches("xcronNextruntimes"));
    }

    @Test
    @DisplayName("排除也认通配：*Snapshot 排掉 runtimeSnapshot，不连带 runtimeSnapshotX")
    void excludeGlobIsAnchored() {
        ToolFilter filter = new ToolFilter(List.of(), List.of("*Snapshot"));

        assertFalse(filter.matches("runtimeSnapshot"));
        assertTrue(filter.matches("runtimeSnapshotX"),
                "用 contains 实现的话这里会是 false，而配置里看不出区别");
        assertTrue(filter.matches("snapshot"), "大小写不是同一个名字，排掉这个不该连带排掉那个");
    }

    @Test
    @DisplayName("名字里的正则元字符按字面量处理：$ 不会被当成行尾")
    void regexMetacharactersAreLiteral() {
        ToolFilter filter = new ToolFilter(List.of("tool$"), List.of());

        assertTrue(filter.matches("tool$"));
        assertFalse(filter.matches("tool"));
    }

    @Test
    @DisplayName("空过滤什么都不排，filtersNothing 说得出来")
    void noFilterChangesNothing() {
        ToolFilter filter = ToolFilter.none();

        assertTrue(filter.filtersNothing());
        assertTrue(filter.matches("任意工具"));
        assertFalse(new ToolFilter(List.of("a"), List.of()).filtersNothing());
    }

    @Test
    @DisplayName("空白条目被丢掉：yml 里多写一个连字符不该排掉一切")
    void blankEntriesAreDropped() {
        ToolFilter filter = new ToolFilter(Arrays.asList(" ", null, "cronNext"), null);

        assertEquals(List.of("cronNext"), filter.include());
        assertTrue(filter.exclude().isEmpty());
        assertTrue(filter.matches("cronNext"));
    }

    @Test
    @DisplayName("没有名字就留不下：空名字不该被 include 的 * 放进来")
    void blankToolNameIsNeverKept() {
        ToolFilter filter = new ToolFilter(List.of("*"), List.of());

        assertFalse(filter.matches(null));
        assertFalse(filter.matches(" "));
    }
}
