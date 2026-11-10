package io.github.claw4j.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义切块那套纯函数：切句、分组、断点、成块。
 *
 * <p>这里不带 Spring、不调 embedding：向量是直接给的 {@code float[]}。这样"断点算得对不对"与
 * "向量怎么来"是两件可以分开失败的事——后者在 {@code KnowledgeRetrievalTest} 里验。
 *
 * <p>参数口径与 LangChain 的 {@code SemanticChunker} 对齐（{@code buffer_size=1}、
 * {@code percentile}=95、{@code min_chunk_size} 缺省不合并），所以下面每条用例的名字
 * 都能在 ADR-0028 里找到对应的判据。
 *
 * <p>用 JUnit 自带的断言而不是 AssertJ：{@code claw4j-core} 的测试依赖只有 JUnit 与 ArchUnit，
 * 为一份纯函数用例往这个模块加一个断言库不值得。
 */
class ChunkPlannerTest {

    private static final float[] A = {1, 0};
    private static final float[] B = {0, 1};

    @Test
    @DisplayName("中文句末标点能切句：LangChain 的默认正则要求标点后有空格，中文切不动")
    void chineseSentencesSplitOnFullStop() {
        List<String> sentences = ChunkPlanner.sentences("华东区七天无理由退货。生鲜商品不适用。");

        assertEquals(List.of("华东区七天无理由退货。", "生鲜商品不适用。"), sentences);
    }

    @Test
    @DisplayName("数字与版本号里的点不断句：v2.0、3.5% 被切开的话，句向量就没有意义了")
    void dotsInsideNumbersDoNotSplit() {
        List<String> sentences = ChunkPlanner.sentences("版本 v2.0 的折扣是 3.5%。下一句还在这里。");

        assertEquals(2, sentences.size());
        assertTrue(sentences.getFirst().contains("v2.0"));
        assertTrue(sentences.getFirst().contains("3.5%"));
    }

    @Test
    @DisplayName("短碎片并回前一句：否则「然而。」这种碎片会单独占一个待断点位置")
    void shortFragmentMergesIntoPrevious() {
        List<String> sentences = ChunkPlanner.sentences("华东区七天无理由退货。然而。");

        assertEquals(1, sentences.size());
        assertTrue(sentences.getFirst().endsWith("然而。"));
    }

    @Test
    @DisplayName("句子组是「前后各一句」的窗口：组数与句数相同，距离才能一一对应到第几句")
    void groupsAreWindowsOfThree() {
        List<String> sentences = List.of("第一句写得足够长。", "第二句也足够长了。", "第三句同样足够长。",
                "第四句也不短啊。");

        List<String> groups = ChunkPlanner.groups(sentences, ChunkPlanner.BUFFER_SIZE);

        assertEquals(4, groups.size());
        assertTrue(groups.get(1).contains("第一句"), "中间那组带上前一句");
        assertTrue(groups.get(1).contains("第二句"));
        assertTrue(groups.get(1).contains("第三句"), "以及后一句");
        assertFalse(groups.getFirst().contains("第四句"), "第一组没有前一句可带");
    }

    @Test
    @DisplayName("断点落在距离严格大于第 95 百分位处：话题变了才断")
    void breakpointsFollowPercentile() {
        List<Integer> breaks = ChunkPlanner.distanceBreakpoints(List.of(A, A, B, B),
                ChunkPlanner.BREAKPOINT_PERCENTILE);

        assertEquals(List.of(2), breaks, "第 2 与第 3 组之间方向变了");
    }

    @Test
    @DisplayName("距离全都一样时一个断点都没有：同主题文档不该被硬切一刀")
    void uniformDistancesProduceNoBreak() {
        List<Integer> breaks = ChunkPlanner.distanceBreakpoints(List.of(A, A, A),
                ChunkPlanner.BREAKPOINT_PERCENTILE);

        assertTrue(breaks.isEmpty(), "阈值等于距离本身，而判据是严格大于");
    }

    @Test
    @DisplayName("句首「然而」起新块：用户举的就是这个例子")
    void transitionAtSentenceStartStartsNewBlock() {
        List<Integer> breaks = ChunkPlanner.withTransitionBreaks(
                List.of("华东区七天无理由退货。", "然而生鲜商品不适用。"), List.of());

        assertEquals(List.of(1), breaks);
    }

    @Test
    @DisplayName("句中引用「然而」不触发：把转折词当子串匹配会把否定句与举例一起切碎")
    void transitionInsideSentenceDoesNotCut() {
        List<Integer> breaks = ChunkPlanner.withTransitionBreaks(
                List.of("华东区七天无理由退货。", "政策里提到的“然而”并不存在。"), List.of());

        assertTrue(breaks.isEmpty());
    }

    @Test
    @DisplayName("按断点切片：区间是左闭右开，首块从 0 开始，末块到句数为止")
    void blocksSliceByBreaks() {
        List<String> sentences = List.of("一。", "二。", "三。", "四。");

        List<int[]> blocks = ChunkPlanner.blocks(sentences, List.of(2));

        assertEquals(2, blocks.size());
        assertArrayEquals(new int[] {0, 2}, blocks.get(0));
        assertArrayEquals(new int[] {2, 4}, blocks.get(1));
    }

    @Test
    @DisplayName("没有断点就是一整块：不需要额外规则来「兜住」这种情况")
    void blocksWithoutBreaksAreOne() {
        List<int[]> blocks = ChunkPlanner.blocks(List.of("一。", "二。", "三。"), List.of());

        assertEquals(1, blocks.size());
        assertArrayEquals(new int[] {0, 3}, blocks.getFirst());
    }

    @Test
    @DisplayName("空文本切不出句子，也切不出块")
    void emptyTextHasNothing() {
        assertTrue(ChunkPlanner.sentences("   ").isEmpty());
        assertTrue(ChunkPlanner.blocks(List.of(), List.of()).isEmpty());
    }

    @Test
    @DisplayName("分位数用线性插值：与 numpy 的 percentile 同口径，换实现不会换断点")
    void percentileIsLinearlyInterpolated() {
        assertEquals(0.5, ChunkPlanner.percentile(new double[] {0, 1}, 50), 1e-9);
    }
}
