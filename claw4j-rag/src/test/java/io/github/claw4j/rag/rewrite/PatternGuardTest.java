package io.github.claw4j.rag.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保护层的单测。
 *
 * <p>要守的是"空配置是合法的"这条：两条正则都可以不给，此时 {@code PatternGuard} 什么都不拦。
 * 如果把空配置当成错误抛出去，那些不需要精确值保护的知识库（英文手册、纯代码库）就没法用它了。
 */
class PatternGuardTest {

    @Test
    @DisplayName("两条都空：什么都不拦——「不拦」是合法配置，不是漏配")
    void emptyGuardLetsEverythingThrough() {
        PatternGuard guard = new PatternGuard(null, null);

        assertThat(guard.shouldSkip("押金 500 元什么时候退")).isFalse();
        assertThat(guard.keepsAll("A2 桌面机多少钱", "桌面机价格")).isTrue();
    }

    @Test
    @DisplayName("只有跳过：命中就跳，不命中照改")
    void skipOnly() {
        PatternGuard guard = new PatternGuard(Pattern.compile("\\d+\\s*元"), null);

        assertThat(guard.shouldSkip("押金 500 元什么时候退")).isTrue();
        assertThat(guard.shouldSkip("押金什么时候退")).isFalse();
    }

    @Test
    @DisplayName("只有保留：丢了片段就拒，大小写不同不算丢")
    void keepOnly() {
        PatternGuard guard = new PatternGuard(null, Pattern.compile("[A-Za-z0-9]+"));

        assertThat(guard.keepsAll("A2 桌面机多少钱", "桌面机价格")).isFalse();
        assertThat(guard.keepsAll("a2 桌面机多少钱", "A2 桌面机报价")).isTrue();
    }

    @Test
    @DisplayName("保留正则在原问题里匹配不到东西：没有要求，就是通过")
    void keepPatternWithNothingToKeep() {
        PatternGuard guard = new PatternGuard(null, Pattern.compile("[A-Za-z0-9]+"));

        assertThat(guard.keepsAll("押金什么时候退", "押金退还时限")).isTrue();
    }
}
