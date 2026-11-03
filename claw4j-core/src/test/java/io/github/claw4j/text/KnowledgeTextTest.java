package io.github.claw4j.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑：切词、切块、融合。
 *
 * <p><b>为什么这些值得单独测</b>：它们是中文检索能不能工作的全部。SQL 那一段（{@code @@} 与
 * {@code ts_rank}）在真库上跑一次就够，但"切出来的 bigram 对不对"没有任何数据库会告诉你——
 * 切错了，{@code to_tsvector} 收到的是合法的 token，查询也正常返回空，全程不报错。
 *
 * <p>第一条用例的数字来自实测：同一句话在 {@code pgvector/pgvector:pg16} 上
 * {@code to_tsvector('simple', …)} 得到 12 个 token。这里断言的是同一批。
 */
class KnowledgeTextTest {

    @Test
    @DisplayName("中文按 2-gram 切：与 PG 上实测的那 12 个 token 一致")
    void chineseSplitsIntoBigrams() {
        assertEquals(
                List.of("华东", "东区", "区的", "的退", "退货", "货政",
                        "政策", "策怎", "怎么", "么规", "规定", "定的"),
                ChineseText.tokens("华东区的退货政策怎么规定的"));
    }

    @Test
    @DisplayName("单个汉字保留自己：它切不出 bigram，但至少能被前缀查询找到")
    void singleCharacterSurvives() {
        assertEquals(List.of("退"), ChineseText.tokens("退"));
    }

    @Test
    @DisplayName("两字词就是一个 bigram：不多切也不少切")
    void twoCharacterWordIsOneBigram() {
        assertEquals(List.of("退货"), ChineseText.tokens("退货"));
    }

    @Test
    @DisplayName("中英混排：英文是整段一个 token，按字符切会把它和噪声混在一起")
    void latinRunsStayWhole() {
        // 切成 sk / ku 的话，"SKU" 会与"思考"(sk) 这类噪声共享 token。
        assertEquals(List.of("sku", "2024", "退货", "货政", "政策"),
                ChineseText.tokens("SKU-2024 退货政策"));
    }

    @Test
    @DisplayName("标点与空白都是分隔符，不进入 token")
    void punctuationSeparates() {
        assertEquals(List.of("华东", "退货"), ChineseText.tokens("华东，退货。"));
    }

    @Test
    @DisplayName("空输入得到空列表，不是含空串的列表")
    void blankInputHasNoTokens() {
        assertTrue(ChineseText.tokens("").isEmpty());
        assertTrue(ChineseText.tokens("   ").isEmpty());
        assertTrue(ChineseText.tokens(null).isEmpty());
    }

    @Test
    @DisplayName("bigrams 是空格分隔：那个空格就是 simple 分词器的接缝")
    void bigramsAreSpaceSeparated() {
        assertEquals("退货 货政 政策", ChineseText.bigrams("退货政策"));
    }

    @Test
    @DisplayName("查询串用 & 连接：多词必须同时出现，不是任意一个")
    void queryRequiresEveryTerm() {
        assertEquals("退货 & 货政 & 政策", ChineseText.tsQuery("退货政策"));
    }

    @Test
    @DisplayName("单字查询带 :* 前缀：不带它的话空 tsquery 一条都命中不了")
    void singleCharacterQueryUsesPrefix() {
        assertEquals("退:*", ChineseText.tsQuery("退"));
        // 中英混排时两边的规则各自生效。
        assertEquals("sku & 退:*", ChineseText.tsQuery("SKU 退"));
    }

    @Test
    @DisplayName("没有可检索内容的查询返回空串：调用方必须据此跳过这一路，而不是把它交给 SQL")
    void emptyQueryIsEmptyString() {
        assertEquals("", ChineseText.tsQuery("，。！"));
        // 空 tsquery 在 Postgres 里的行为是「一条都不命中」（实测 @@ 为 false），
        // 交出去不会报错，只会安静地返回空——那正是"搜不到"与"没有这段"分不开的地方。
    }

    @Test
    @DisplayName("短段落合并成一块：不是一段一块，块太碎会让出处变成噪音")
    void shortParagraphsAreMerged() {
        List<TextChunker.Chunk> chunks = TextChunker.split("第一段。\n\n第二段。\n\n第三段。", 800);
        assertEquals(1, chunks.size());
        assertEquals("第一段。\n第二段。\n第三段。", chunks.getFirst().text());
    }

    @Test
    @DisplayName("到上限就断开，序号从 1 开始")
    void chunksBreakAtLimit() {
        List<TextChunker.Chunk> chunks = TextChunker.split("第一段。\n\n第二段。\n\n第三段。", 5);
        assertEquals(3, chunks.size());
        assertEquals(1, chunks.getFirst().ordinal());
        assertEquals("第一段。", chunks.getFirst().text());
        assertEquals(3, chunks.getLast().ordinal());
    }

    @Test
    @DisplayName("超过块长的段落会断开，但不会断在半个字符上")
    void longParagraphIsSplitOnCodePoints() {
        // 每个 emoji 占两个 char、一个 code point。按 char 切会劈成半个代理对，
        // 落库之后是一个静默的问号。
        String emoji = "🙂";
        String text = emoji.repeat(10);
        List<TextChunker.Chunk> chunks = TextChunker.split(text, 3);
        assertEquals(4, chunks.size());
        assertEquals(emoji.repeat(3), chunks.getFirst().text());
        // 拼回去必须与原文一致：拼不回来说明有字符被切掉了。
        assertEquals(text, String.join("", chunks.stream().map(TextChunker.Chunk::text).toList()));
    }

    @Test
    @DisplayName("空白文档切出零块，而不是一块空文本")
    void blankDocumentHasNoChunks() {
        assertTrue(TextChunker.split("   \n\n  ").isEmpty());
        assertTrue(TextChunker.split(null).isEmpty());
    }

    @Test
    @DisplayName("块长为 0 直接拒绝：它不是「配错了一点」，是「这个功能不成立」")
    void zeroMaxCharsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("文本", 0));
    }

    @Test
    @DisplayName("融合：两路都排第二胜过只有一路排第一")
    void fusionRewardsAgreement() {
        // 这一条正是 RRF 存在的理由：单看任何一路，B 都不如 A / C 靠前。
        List<String> vector = List.of("A", "B");
        List<String> keyword = List.of("C", "B");
        assertEquals(List.of("B", "A", "C"), RankFusion.fuse(List.of(vector, keyword), s -> s));
    }

    @Test
    @DisplayName("融合按 key 合并：同一条从两路取回来只出现一次")
    void fusionMergesByKey() {
        List<List<String>> channels = List.of(List.of("x", "y"), List.of("y", "x"));
        List<String> fused = RankFusion.fuse(channels, s -> s);
        assertEquals(2, fused.size());
        // 两路里 x 都是第 1、y 都是第 2，所以 x 在前。
        assertEquals("x", fused.getFirst());
    }

    @Test
    @DisplayName("同分时保持首次出现的顺序：两次调用不能给出不同的上下文")
    void fusionIsStableOnTies() {
        List<List<String>> channels = List.of(List.of("a", "b"), List.of("b", "a"));
        assertEquals(List.of("a", "b"), RankFusion.fuse(channels, s -> s));
        assertEquals(List.of("a", "b"), RankFusion.fuse(channels, s -> s));
    }

    @Test
    @DisplayName("k 必须为正：0 会让 1/(k+1) 变成 1，等于只数出现次数")
    void kMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> RankFusion.fuse(List.of(List.of("a")), s -> s, 0));
        assertFalse(RankFusion.fuse(List.of(List.of("a")), s -> s, 1).isEmpty());
    }
}
