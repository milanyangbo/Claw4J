package io.github.claw4j.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gold 解析的判据：证据能不能落到库里的段上，落不上时是不是当场说清楚。
 *
 * <p>这几条进回归（不带 {@code eval} 标签）：它们不碰库、不碰模型，纯字符串比对。
 * 导出那份要真库与真 embedding，在 {@code RetrievalExportTest} 里，默认不跑。
 */
class RetrievalGoldTest {

    /** 一处证据的简写：绝大多数用例只有一处，写全 List.of 只是噪音。 */
    private static RetrievalGold gold(String id, String question, String reference, String doc, String span) {
        return new RetrievalGold(id, question, reference, List.of(RetrievalGold.Span.of(doc, span)));
    }

    @Test
    @DisplayName("span 与文档原句只差一个 Markdown 强调，也能落到那一段")
    void goldBindsToChunkId() {
        RetrievalGold gold = gold("rag-east-return", "华东区退货几天？", "7 天",
                "after-sales.md",
                "华东区（上海、江苏、浙江、安徽）自签收之日起 7 天内可无理由退货");
        List<RetrievalGold.Chunk> chunks = List.of(RetrievalGold.Chunk.of("after-sales.md", 1,
                "华东区（上海、江苏、浙江、安徽）自签收之日起 **7 天内可无理由退货**，商品需保持完好、"));

        assertThat(gold.resolveIds(chunks))
                .as("语料里的关键句带 ** 强调，gold 里不写星号，归一化之后必须能对上")
                .containsExactly("after-sales.md#1");
    }

    @Test
    @DisplayName("同一句话在两份文档里出现时，按 doc 消歧，不许取第一个命中的")
    void docDisambiguatesSharedSpan() {
        String shared = "计息不滚入本金，是客户提得最多的一条";
        RetrievalGold gold = gold("rag-overdue", "利息滚入本金吗？", "不滚入",
                "settlement.md", shared);
        List<RetrievalGold.Chunk> chunks = List.of(
                RetrievalGold.Chunk.of("archive/settlement-2025.md", 2, shared),
                RetrievalGold.Chunk.of("settlement.md", 3, shared));

        assertThat(gold.resolveIds(chunks))
                .as("归档目录排在前面，只比句子的话现行版会被顶掉")
                .containsExactly("settlement.md#3");
    }

    @Test
    @DisplayName("span 跨两个块时两块都算 gold：答案被切块切断，不等于只标一半")
    void crossChunkSpanResolvesToBothChunks() {
        RetrievalGold gold = gold("rag-overdue", "怎么计息？", "万分之五且不滚入",
                "settlement.md",
                "从到期日的次日起按未回款金额每日万分之五计息，计息不滚入本金");
        List<RetrievalGold.Chunk> chunks = List.of(
                RetrievalGold.Chunk.of("settlement.md", 2,
                        "月结客户超过账期未回款的，从到期日的次日起按未回款金额每日万分之五计息，"),
                RetrievalGold.Chunk.of("settlement.md", 3, "计息不滚入本金。这一段还讲了别的。"));

        assertThat(gold.resolveIds(chunks))
                .as("第一片落 #2、第二片落 #3；只标一块的话 recall 显示满分，而模型拿不到完整答案")
                .containsExactly("settlement.md#2", "settlement.md#3");
    }

    @Test
    @DisplayName("块边界把词劈开时两块都算 gold：切块按 token 切，不保证词边界")
    void splitWordAcrossChunksStillResolves() {
        // 实测的切块结果：settlement.md 第一段停在「每日万」，第二段从「分之五计息」开始。
        RetrievalGold gold = gold("rag-overdue", "怎么计息？", "万分之五且不滚入",
                "settlement.md",
                "从到期日的次日起按未回款金额每日万分之五计息，计息不滚入本金");
        List<RetrievalGold.Chunk> chunks = List.of(
                RetrievalGold.Chunk.of("settlement.md", 1,
                        "月结客户超过账期未回款的，从到期日的次日起按未回款金额每日万"),
                RetrievalGold.Chunk.of("settlement.md", 2,
                        "分之五计息，计息不滚入本金。连续两个月逾期或单笔逾期超过 60 个自然日的，暂停"));

        assertThat(gold.resolveIds(chunks))
                .as("「万分之五」被切成「每日万」+「分之五」，整片 contains 在两段上都为假")
                .containsExactly("settlement.md#1", "settlement.md#2");
    }

    @Test
    @DisplayName("两处证据各落一段时返回并集：多跳题缺一处就是 0.5 分，不是满分")
    void everyEvidenceMustLand() {
        RetrievalGold gold = new RetrievalGold("rag-multihop-extend-a2", "A2 买延保多少钱？",
                "目录价 1680，延保按 8% 收",
                List.of(RetrievalGold.Span.of("price-list-2026.md", "SKU-2026-A2 桌面标签机（高精度）"),
                        RetrievalGold.Span.of("price-list-2026.md", "延保（整机）每台每年设备目录价的 8%")));
        List<RetrievalGold.Chunk> chunks = List.of(
                RetrievalGold.Chunk.of("price-list-2026.md", 1,
                        "| SKU-2026-A2 | 桌面标签机（高精度） | 108mm | 300dpi | 1680 元 |"),
                RetrievalGold.Chunk.of("price-list-2026.md", 3,
                        "| 延保（整机） | 每台每年 | 设备目录价的 8% |"));

        assertThat(gold.resolveIds(chunks))
                .as("两处证据分别落在 #1 与 #3，少任何一处都会让 recall 悄悄变成 0.5")
                .containsExactly("price-list-2026.md#1", "price-list-2026.md#3");
    }

    @Test
    @DisplayName("第二处证据落不到时抛错并指出是哪一句，不许静默返回只落上的那一处")
    void missingSecondEvidenceIsRejected() {
        RetrievalGold gold = new RetrievalGold("rag-multihop-a1", "A1 以旧换新抵多少？",
                "目录价 1280 的 30%",
                List.of(RetrievalGold.Span.of("price-list-2026.md", "SKU-2024-A1 桌面标签机"),
                        RetrievalGold.Span.of("price-list-2026.md", "这句在文档里不存在但长度够八")));
        List<RetrievalGold.Chunk> chunks = List.of(
                RetrievalGold.Chunk.of("price-list-2026.md", 1,
                        "| SKU-2024-A1 | 桌面标签机 | 108mm | 203dpi | 1280 元 |"));

        assertThatThrownBy(() -> gold.resolveIds(chunks))
                .as("只返回第一处，会让这条多跳题永远看起来「召到了」，而第二跳根本没验")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rag-multihop-a1")
                .hasMessageContaining("这句在文档里不存在但长度够八");
    }

    @Test
    @DisplayName("spans 是空列表当场拒：一条题没有证据，等于什么都不判")
    void emptySpansIsRejected() {
        assertThatThrownBy(() -> new RetrievalGold("rag-empty", "问", "答", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一处证据");
    }

    @Test
    @DisplayName("span 太短当场拒：一个「7 天」能命中几十段，那种 recall 是运气")
    void tooShortSpanIsRejected() {
        assertThatThrownBy(() -> gold("rag-short", "问", "答", "a.md", "7 天"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("区分度不够");
    }

    @Test
    @DisplayName("yaml 里多写一个键当场拒，免得敲错字变成什么都不判")
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> RetrievalGold.from(Map.of(
                "id", "rag-x",
                "question", "q",
                "reference", "r",
                "gold_spans", List.of(Map.of("doc", "a.md", "span", "一二三四五六七八九十")),
                "goldspan", "拼错了")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("认不出");
    }

    @Test
    @DisplayName("gold_spans 写成单个对象（不是列表）当场拒：两种形状都认，下一个人就会两种都用")
    void bareSpanObjectIsRejected() {
        assertThatThrownBy(() -> RetrievalGold.from(Map.of(
                "id", "rag-x",
                "question", "q",
                "reference", "r",
                "gold_spans", Map.of("doc", "a.md", "span", "一二三四五六七八九十"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("应当是非空列表");
    }

    @Test
    @DisplayName("span 落不到任何一段时抛错，并点出是哪条 gold、哪份文档、哪句话")
    void missingSpanNamesTheGold() {
        RetrievalGold gold = gold("rag-miss", "q", "r", "a.md", "一二三四五六七八九十");

        assertThatThrownBy(() -> gold.resolveIds(List.of(RetrievalGold.Chunk.of("a.md", 1, "完全不相干的一段"))))
                .as("落不到就抛，不返回空列表：空 id 会被算成 recall 0，看起来像检索没召回，实际是锚点抄错了")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rag-miss")
                .hasMessageContaining("a.md");
    }
}
