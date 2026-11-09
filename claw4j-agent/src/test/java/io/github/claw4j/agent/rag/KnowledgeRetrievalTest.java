package io.github.claw4j.agent.rag;

import com.sun.net.httpserver.HttpServer;
import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库这一层：**目录怎么进库**、**两路怎么融合**、**重排失败时结果还在不在**。
 *
 * <p><b>这里是替身，不是真库</b>：真库那几条（表结构、编号命中、通配符真跑）在
 * {@code claw4j-app} 的 {@code SearchSqlTest}。分开的理由是这里要构造"向量那一路抛异常"
 * 这类真库上做不到的场景。
 *
 * <p><b>2026-11-09 之后被测对象变了</b>：曾经测的是自有的 {@code KnowledgeBase} 与
 * {@code searchDocs} 工具；现在检索由 {@code RetrievalAugmentationAdvisor} 在每轮请求里
 * 自动完成，工具已删除。所以这里测的是三个"实现官方接口"的类 + 装配条件。
 */
class KnowledgeRetrievalTest {

    private static final String DIGEST = "a".repeat(64);

    // ---- 索引器：目录是事实来源 ----

    @Test
    @DisplayName("内容没变就跳过：靠 claw4j_rag.source 的 hash，不重算 embedding")
    void unchangedFileIsSkipped(@TempDir Path docs) throws IOException {
        String body = "# 退货\n\n华东区 7 天无理由退货。";
        Files.writeString(docs.resolve("a.md"), body);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("content_hash"), eq(String.class), any(), any()))
                .thenReturn(List.of(PermissionFingerprint.sha256(body)));
        // 切块身份也对得上，才轮到"比 hash"
        when(jdbc.queryForList(contains("index_meta"), eq(String.class), any()))
                .thenReturn(List.of(KnowledgeIndexer.identityOf(200)));
        VectorStore store = mock(VectorStore.class);

        KnowledgeIndexer.Stats stats = new KnowledgeIndexer(jdbc, store, mock(EmbeddingModel.class),
                props(docs)).index();

        assertThat(stats.skipped()).as("hash 相等就是没变").isEqualTo(1);
        assertThat(stats.indexed()).isZero();
        verify(store, never()).add(any());
    }

    @Test
    @DisplayName("重建一份文档先删后加：块的编号会整体前移，旧尾巴留着就是返回不存在的原文")
    void reindexDeletesBeforeAdding(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("a.md"), "# 新版本\n\n改过的内容。");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("content_hash"), eq(String.class), any(), any()))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        VectorStore store = mock(VectorStore.class);

        new KnowledgeIndexer(jdbc, store, mock(EmbeddingModel.class), props(docs)).index();

        var order = inOrder(store);
        order.verify(store).delete(any(Filter.Expression.class));
        order.verify(store).add(any());
    }

    @Test
    @DisplayName("文件删了块也删：目录里没有的东西留在库里，出处就指回一个不存在的文件")
    void removedFileLeavesNoChunks(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("b.md"), "# 还在的文件\n\n内容。");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("content_hash"), eq(String.class), any(), any()))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("select path"), eq(String.class), any()))
                .thenReturn(List.of("a.md"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        VectorStore store = mock(VectorStore.class);

        KnowledgeIndexer.Stats stats = new KnowledgeIndexer(jdbc, store, mock(EmbeddingModel.class),
                props(docs)).index();

        assertThat(stats.removed()).as("目录里已经没有它了").isEqualTo(1);
        // 断言的是"文件账被删"，不是"块被删"：块由同一句 deleteChunksOf 带走，
        // 而 b.md 是新文件也会走一次删除——数 delete 次数会随着夹具变化而抖。
        verify(jdbc).update(anyString(), eq("a.md"), eq(KnowledgeIndexer.SOURCE_KIND));
    }

    @Test
    @DisplayName("目录里一个可索引文件都没有：启动失败，不是空知识库")
    void emptyDirectoryFailsLoudly(@TempDir Path docs) {
        KnowledgeIndexer indexer = new KnowledgeIndexer(mock(JdbcTemplate.class),
                mock(VectorStore.class), mock(EmbeddingModel.class), props(docs));

        assertThatThrownBy(indexer::index)
                .as("空知识库让每次检索都答「没有相关资料」，与「这段确实没写」分不开")
                .hasMessageContaining("没有可索引");
    }

    // ---- 融合：加权 0.7 / 0.3，与 OpenClaw 同权重 ----

    @Test
    @DisplayName("两路都命中加分、同一路重复不加分、只有全文命中的封顶 0.3")
    void weightedFusionAddsLanesAndCapsTextOnly() {
        Document both = scored("both", "returns/a.md", 1, 0.9);
        Document vectorOnly = scored("v2", "returns/b.md", 1, 0.9);
        Document keywordOnly = doc("k1", "warehouse/sku.md", 1).mutate().score(4.0).build();
        Document keywordBoth = doc("both", "returns/a.md", 1).mutate().score(4.0).build();

        List<Document> merged = joiner().join(List.of(vectorOnly, both),
                List.of(keywordBoth, keywordOnly));

        assertThat(merged).extracting(Document::getId)
                .as("both 两路都命中：0.7*0.9 + 0.3*1 = 0.93，压过只有向量的 0.63")
                .containsExactly("both", "v2", "k1");
        assertThat(scoreOf(merged, "both")).isCloseTo(0.93, within(1e-9));
        assertThat(scoreOf(merged, "v2")).as("向量只有一半权重").isCloseTo(0.63, within(1e-9));
        assertThat(scoreOf(merged, "k1"))
                .as("只有全文命中的块封顶 0.3（share=1 时），排序上输给余弦 > 0.43 的向量命中")
                .isCloseTo(0.3, within(1e-9));

        // 同一路里重复出现只算一次：重复拿分等于把这一路的分量偷偷加倍。
        List<Document> repeated = joiner().join(List.of(vectorOnly, both),
                List.of(keywordBoth, keywordBoth, keywordOnly));
        assertThat(scoreOf(repeated, "both")).as("重复不该让分数超过 1.0")
                .isCloseTo(0.93, within(1e-9));

        assertThat(joiner().join(List.of(), List.of(keywordOnly)))
                .as("向量那一路空的时候，全文那一路的结果必须还在——融合不是交集")
                .extracting(Document::getId).containsExactly("k1");
    }

    // ---- 混合检索：一路挂了还有另一路 ----

    @Test
    @DisplayName("两路都调用，结果按 id 去重")
    void bothRoutesAreCalledAndMerged() {
        Document shared = doc("d1", "returns/a.md", 1);
        AtomicReference<Query> vectorSaw = new AtomicReference<>();
        AtomicReference<Query> keywordSaw = new AtomicReference<>();

        DocumentRetriever hybrid = new HybridDocumentRetriever(
                q -> {
                    vectorSaw.set(q);
                    return List.of(shared);
                },
                q -> {
                    keywordSaw.set(q);
                    return List.of(shared);
                },
                joiner());

        List<Document> found = hybrid.retrieve(query("退货"));

        assertThat(found).extracting(Document::getId)
                .as("同一个块两路都命中时只留一条").containsExactly("d1");
        assertThat(vectorSaw.get()).as("两路都必须被调用").isNotNull();
        assertThat(keywordSaw.get().text()).isEqualTo("退货");
    }

    @Test
    @DisplayName("向量那一路抛异常：全文那一路照返，不把这一路的问题算成整轮失败")
    void vectorFailureFallsBackToKeyword() {
        Document keywordHit = doc("k1", "warehouse/sku-2024.md", 2);

        DocumentRetriever hybrid = new HybridDocumentRetriever(
                q -> {
                    throw new IllegalStateException("向量服务超时");
                },
                q -> List.of(keywordHit),
                joiner());

        assertThat(hybrid.retrieve(query("SKU-2024")))
                .as("一路挂了不该让「问编号」这件事直接失败")
                .extracting(Document::getId).containsExactly("k1");
    }

    @Test
    @DisplayName("两路都抛：抛出去。检索是回答的依据，「没查」不能伪装成「没写」")
    void bothRoutesFailingThrows() {
        DocumentRetriever hybrid = new HybridDocumentRetriever(
                q -> {
                    throw new IllegalStateException("向量挂了");
                },
                q -> {
                    throw new IllegalStateException("库挂了");
                },
                joiner());

        assertThatThrownBy(() -> hybrid.retrieve(query("退货")))
                .as("两路都不可用时这一轮必须失败——否则用户会以为文档里没写")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("向量挂了");
    }

    @Test
    @DisplayName("没有身份、或没有 doc 读权限：都不检索，也都不抛")
    void nothingIsRetrievedWithoutIdentityOrDocPermission() {
        DocumentRetriever hybrid = new HybridDocumentRetriever(
                q -> {
                    throw new AssertionError("不该调用向量那一路");
                },
                q -> {
                    throw new AssertionError("不该调用全文那一路");
                },
                joiner());

        assertThat(hybrid.retrieve(new Query("退货")))
                .as("没有身份时不该检索").isEmpty();

        Principal guest = Principal.of("u-1", "guest", Set.of("region"), Set.of(ToolPermission.READ));
        assertThat(hybrid.retrieve(withPrincipal("退货", guest)))
                .as("判据与工具时代同一个 ToolPermission：没有 doc 就不查").isEmpty();
    }

    // ---- 全文那一路：通配符 ----

    @Test
    @DisplayName("通配符转义：问「5%」不会变成「匹配所有含 5 的块」")
    void likeWildcardsAreEscaped() {
        assertThat(KeywordDocumentRetriever.escapeLike("折扣率 5%")).isEqualTo("折扣率 5\\%");
        assertThat(KeywordDocumentRetriever.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(KeywordDocumentRetriever.escapeLike("c:\\x")).isEqualTo("c:\\\\x");
    }

    @Test
    @DisplayName("全文那一路的 SQL 带 escape 与 limit，参数按 ? 在 SQL 里出现的顺序给")
    void keywordSqlEscapesAndBounds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        new KeywordDocumentRetriever(jdbc, "docs", 7).retrieve(query("5% 折扣"));

        // 顺序 = select 里的命中次数（term、term）→ where（source、转义后的 pattern）→ limit。
        verify(jdbc).query(contains("escape"), any(RowMapper.class),
                eq("5% 折扣"), eq("5% 折扣"), eq("docs"), eq("%5\\% 折扣%"), eq(7));
    }

    // ---- 语义切块：句子之间判语义，token 只做上限（ADR-0028） ----

    @Test
    @DisplayName("块超 token 上限才硬切：字数只在超限时出现，且切开不丢字")
    void oversizedBlockIsSplitByTokenLimit() {
        String longText = "退货".repeat(200);
        SemanticTextSplitter splitter = new SemanticTextSplitter(mock(EmbeddingModel.class), 20);

        List<String> chunks = texts(splitter.apply(List.of(new Document(longText))));

        assertThat(chunks).as("不切的话 embedding 那边输入超限").hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }

    @Test
    @DisplayName("embedding 挂了只丢向量断点：句首「然而」照切，不整篇退回纯 token")
    void embeddingFailureKeepsTransitionBreaks() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenThrow(new IllegalStateException("向量服务超时"));
        SemanticTextSplitter splitter = new SemanticTextSplitter(embeddings, 200);

        List<String> chunks = texts(splitter.apply(List.of(new Document(
                "华东区七天无理由退货。生鲜商品不适用。然而跨境商品另按海关规定处理。"))));

        assertThat(chunks).as("断点来自句首「然而」，不是向量").hasSize(2);
        assertThat(chunks.get(1)).startsWith("然而");
    }

    @Test
    @DisplayName("向量条数与句子组数不符：只丢向量断点，不按下标配对")
    void mismatchedEmbeddingCountIgnoresVectors() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[] {1, 0}));
        SemanticTextSplitter splitter = new SemanticTextSplitter(embeddings, 200);

        List<String> chunks = texts(splitter.apply(List.of(new Document(
                "华东区七天无理由退货。生鲜商品不适用。然而跨境商品另按海关规定处理。"))));

        assertThat(chunks).as("配不上就当作没有向量，转折词那条照常生效").hasSize(2);
        verify(embeddings).embed(anyList());
    }

    @Test
    @DisplayName("句子数少于 3 时一次 embedding 都不调：没有「相邻组」可言")
    void fewSentencesSkipEmbedding() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        SemanticTextSplitter splitter = new SemanticTextSplitter(embeddings, 200);

        List<String> chunks = texts(splitter.apply(List.of(new Document(
                "华东区七天无理由退货。生鲜商品不适用。"))));

        assertThat(chunks).hasSize(1);
        verify(embeddings, never()).embed(anyList());
    }

    // ---- 重排：失败必须原样返回 ----

    @Test
    @DisplayName("重排只换顺序不截断：送进去的是候选池前 N 条，回来还是这些条")
    void rerankReordersAndCapsInput() throws Exception {
        try (Stub endpoint = new Stub("""
                {"results":[{"index":2,"relevance_score":0.9},
                            {"index":0,"relevance_score":0.5},
                            {"index":1,"relevance_score":0.2}]}
                """)) {
            List<Document> pool = List.of(doc("d1", "a.md", 1), doc("d2", "b.md", 1),
                    doc("d3", "c.md", 1), doc("d4", "d.md", 1));

            List<Document> ranked = endpoint.reranker(3).process(query("退货"), pool);

            assertThat(ranked).extracting(Document::getId)
                    .as("下标 2 分最高应排第一；三条全留着——截断是 TopK 那一步的事")
                    .containsExactly("d3", "d1", "d2");
            assertThat(ranked).extracting(Document::getId)
                    .as("第 4 条在候选上限之外，根本不该送进重排")
                    .doesNotContain("d4");
        }
    }

    @Test
    @DisplayName("组合起来仍然对：融合分进 score、官方 joiner 不动顺序、截断只留 top-k（含重排关掉时）")
    void fusedOrderSurvivesOfficialJoinerAndTopKCut() {
        // 真库上两路给的分本来就不是一个量纲：向量那一路是余弦，全文那一路是命中次数。
        Document a = scored("a", "returns/a.md", 1, 0.90);
        Document b = scored("b", "returns/b.md", 1, 0.80);
        Document bByText = doc("b", "returns/b.md", 1).mutate().score(2.0).build();
        Document cByText = doc("c", "warehouse/sku.md", 1).mutate().score(4.0).build();

        List<Document> fused = joiner().join(List.of(a, b), List.of(bByText, cByText));

        assertThat(fused).extracting(Document::getId)
                .as("b 两路都命中：0.7*0.8 + 0.3*(2/4) = 0.71，压过余弦 0.90 的 a（0.63）")
                .containsExactly("b", "a", "c");
        assertThat(fused).allSatisfy(document -> assertThat(document.getScore())
                .as("融合分必须写进 score，否则下游按 0.0 把它甩到末尾")
                .isNotNull());

        // advisor 里紧接着跑的就是这个官方 joiner，它按 score 倒序。
        List<Document> joined = new ConcatenationDocumentJoiner()
                .join(Map.of(query("退货"), List.of(fused)));

        assertThat(joined).extracting(Document::getId)
                .as("余弦 0.90 的 a 不该压过「两路都认可」的 b，也不该把全文独有的 c 甩到最前")
                .containsExactly("b", "a", "c");
        assertThat(new TopKDocumentPostProcessor(2).process(query("退货"), joined))
                .extracting(Document::getId)
                .as("最终只留 top-k，顺序沿用上面那一个")
                .containsExactly("b", "a");

        // 重排关掉时截断必须还在：否则 30 段候选池会整个进提示词，而这只在关掉开关时出现。
        List<Document> pool = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            pool.add(doc("d" + i, "returns/x.md", i));
        }
        List<Document> afterChain = pool;
        for (DocumentPostProcessor step : RagAutoConfiguration.postProcessors(null, 5)) {
            afterChain = step.process(query("退货"), afterChain);
        }
        assertThat(afterChain).as("没有重排时，链上只剩截断，且它照 top-k 生效").hasSize(5);
    }

    @Test
    @DisplayName("重排的三种失败姿势都不丢结果：越界下标、端点 500、超时")
    void rerankFailuresReturnCandidatesAsIs() throws Exception {
        List<Document> candidates = List.of(doc("d1", "a.md", 1), doc("d2", "b.md", 1),
                doc("d3", "c.md", 1));

        try (Stub endpoint = new Stub("""
                {"results":[{"index":9,"relevance_score":0.9},
                            {"index":1,"relevance_score":0.8},
                            {"index":1,"relevance_score":0.7}]}
                """)) {
            assertThat(endpoint.reranker(3).process(query("退货"), candidates))
                    .as("9 越界丢弃、重复的 1 只算一次；剩下的按原顺序补")
                    .extracting(Document::getId).containsExactly("d2", "d1", "d3");
        }

        try (Stub endpoint = new Stub("boom", 500)) {
            assertThat(endpoint.reranker(3).process(query("退货"), candidates))
                    .as("重排是排序优化，不是检索：端点挂了结果必须还在")
                    .extracting(Document::getId).containsExactly("d1", "d2", "d3");
        }

        try (Stub endpoint = new Stub("{}", 200, 400)) {
            assertThat(endpoint.reranker(Duration.ofMillis(80), 3).process(query("退货"), candidates))
                    .as("超时同样原样返回").extracting(Document::getId)
                    .containsExactly("d1", "d2", "d3");
            assertThat(Thread.currentThread().isInterrupted())
                    .as("超时/中断之后不能把中断标志留给下一次调用")
                    .isFalse();
        }
    }

    // ---- 装配 ----

    @Test
    @DisplayName("配了目录才装 advisor，且 order 排在记忆之后")
    void advisorIsRegisteredAfterMemory(@TempDir Path docs) throws IOException {
        Files.writeString(docs.resolve("a.md"), "# 退货\n\n华东区 7 天无理由退货。");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RagAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .withBean(VectorStore.class, () -> mock(VectorStore.class))
                .withPropertyValues("claw4j.rag.enabled=true", "claw4j.rag.directory=" + docs,
                        "claw4j.rag.rerank.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Advisor advisor = context.getBean(Advisor.class);
                    assertThat(advisor.getOrder())
                            .as("排在记忆之后，否则检索看到的是没有历史的一轮")
                            .isGreaterThan(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER);
                });
    }

    @Test
    @DisplayName("开着却没配目录：启动失败并点名，不是空知识库")
    void missingDirectoryFailsAtStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RagAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withPropertyValues("claw4j.rag.enabled=true")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).as("没配目录时装配阶段就必须失败").isNotNull();
                    Throwable root = failure;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).hasMessageContaining("claw4j.rag.directory");
                });
    }

    // ---- 夹具 ----

    private static List<String> texts(List<Document> documents) {
        return documents.stream().map(Document::getText).toList();
    }

    private static RagProperties props(Path docs) {
        return new RagProperties(docs.toString(), 5, 0.5, 200, null, null);
    }

    private static WeightedScoreJoiner joiner() {
        return new WeightedScoreJoiner();
    }

    private static Document scored(String id, String path, int index, double score) {
        return doc(id, path, index).mutate().score(score).build();
    }

    private static double scoreOf(List<Document> documents, String id) {
        return documents.stream().filter(document -> id.equals(document.getId()))
                .findFirst().orElseThrow().getScore();
    }

    private static Document doc(String id, String path, int index) {
        return new Document(id, "内容 " + id, RagMetadata.of(path, KnowledgeIndexer.SOURCE_KIND,
                String.valueOf(index), DIGEST));
    }

    private static Query query(String text) {
        return new Query(text, List.of(), Map.of(ContextKeys.PRINCIPAL, Principal.readOnly("u-1")));
    }

    private static Query withPrincipal(String text, Principal principal) {
        return new Query(text, List.of(), Map.of(ContextKeys.PRINCIPAL, principal));
    }

    /**
     * 一个最小的 rerank 端点。
     *
     * <p>用 JDK 自带的 {@link HttpServer}，不引 WireMock：这里要验的是"客户端怎么处理
     * 一个不听话的响应"，不是 HTTP 协议本身。
     */
    private static final class Stub implements AutoCloseable {

        private final HttpServer server;

        Stub(String body) throws IOException {
            this(body, 200, 0);
        }

        Stub(String body, int status) throws IOException {
            this(body, status, 0);
        }

        Stub(String body, int status, long delayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/reranks", exchange -> {
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        RerankDocumentPostProcessor reranker(int topK) {
            return reranker(Duration.ofSeconds(2), topK);
        }

        RerankDocumentPostProcessor reranker(Duration timeout, int topK) {
            return new RerankDocumentPostProcessor(
                    HttpClient.newHttpClient(),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/reranks",
                    "qwen3-rerank", timeout, topK, () -> "test-key");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
