package io.github.claw4j.agent.rag;

import io.github.claw4j.agent.PermissionFingerprint;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 知识库这一层：**目录怎么进库**、**一个问题怎么变成几段带出处的文本**、**工具怎么说话**。
 *
 * <p><b>SQL 那一半不在这里</b>：生成列能不能建、{@code to_tsvector('simple', bigrams)}
 * 到底切出几个 token、两路各取一次 topK 的结果对不对，都在 app 的 {@code SearchSqlTest}
 * 里用真 Postgres 验——那几条只有真库答得了（生成列要求函数 immutable，不满足时建表就失败）。
 * 这里对 {@link JdbcTemplate} 用替身，只断言"这个类发了什么、拿回来之后怎么处理"。
 *
 * <p><b>为什么替身要真的调用 {@code RowMapper}</b>：{@link PgVectorKnowledgeBase} 里那两段
 * 行映射（哪一列映射到 {@code Hit} 的哪个字段）是它自己的代码，替身直接返回 {@code Hit}
 * 就把这半边跳过去了——而"列名写错"正是这种代码最常见的错法。所以这里的假库把
 * {@link ResultSet} 也做了替身，让真正的映射逻辑跑一遍。
 *
 * <p><b>两个"没有"必须分得开</b>：库里一段都没有（索引没建起来 / 目录配错）与库里有 N 段
 * 但没有一段相关，是两个不同的部署状态。见 {@link #anEmptyLibraryIsSaidAwayFromNoMatch}。
 */
class KnowledgeRetrievalTest {

    private static final float[] VECTOR = {0.1f, 0.2f, 0.3f};

    // ---------------------------------------------------------------- 索引

    @Test
    @DisplayName("目录里每份文档都进库：报告写清重建几份、多少块，块里存的是切好的 bigram")
    void indexesEveryDocumentAndSaysWhatItDid(@TempDir Path root) throws Exception {
        write(root, "after-sales.md", "华东区退货七天内可退换。\n\n退货运费由买家承担。");
        write(root, "pricing.txt", "华东区满 199 包邮。");

        JdbcTemplate jdbc = jdbc();
        when(jdbc.queryForObject(contains("insert into claw4j_rag.document"), eq(Long.class), any(), any()))
                .thenReturn(1L);
        when(jdbc.queryForObject(contains("count(*) from claw4j_rag.chunk"), eq(Integer.class)))
                .thenReturn(2);

        KnowledgeIndexer.Stats stats = indexer(jdbc, directory(root)).index();

        assertThat(stats.indexed()).as("两份文档都该重建").isEqualTo(2);
        assertThat(stats.skipped()).isZero();
        assertThat(stats.removed()).isZero();
        assertThat(stats.chunks()).as("块的数目取自库，不是自己数的").isEqualTo(2);
        verify(jdbc, times(2)).update(contains("insert into claw4j_rag.chunk"), any(), any(), any(),
                argThat(bigrams -> bigrams.toString().contains(" ")
                        && !bigrams.toString().isBlank()),
                anyString());
        verify(jdbc, times(2)).update(contains("delete from claw4j_rag.chunk"), any(Long.class));
    }

    @Test
    @DisplayName("内容没变就跳过：按文件内容的 SHA-256 比，不是按修改时间")
    void unchangedFilesAreSkippedByContentHash(@TempDir Path root) throws Exception {
        String content = "华东区退货七天内可退换。";
        write(root, "after-sales.md", content);

        JdbcTemplate jdbc = jdbc();
        when(jdbc.queryForList(contains("select content_hash"), eq(String.class), any()))
                .thenReturn(List.of(PermissionFingerprint.sha256(content)));
        when(jdbc.queryForObject(contains("count(*) from claw4j_rag.chunk"), eq(Integer.class)))
                .thenReturn(2);

        KnowledgeIndexer.Stats stats = indexer(jdbc, directory(root)).index();

        assertThat(stats.skipped()).as("哈希一样就是没变").isEqualTo(1);
        assertThat(stats.indexed()).isZero();
        verify(jdbc, never()).queryForObject(contains("insert into claw4j_rag.document"),
                eq(Long.class), any(), any());
        verify(jdbc, never()).update(contains("insert into claw4j_rag.chunk"), any(), any(), any(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("目录里没有的，库里也删掉：否则出处指向一个已经被删掉的文件")
    void filesGoneFromTheDirectoryAreRemoved(@TempDir Path root) throws Exception {
        write(root, "after-sales.md", "华东区退货七天内可退换。");

        JdbcTemplate jdbc = jdbc();
        when(jdbc.queryForObject(contains("insert into claw4j_rag.document"), eq(Long.class), any(), any()))
                .thenReturn(1L);
        when(jdbc.queryForList(contains("select source from claw4j_rag.document"), eq(String.class)))
                .thenReturn(List.of("pricing.md"));

        KnowledgeIndexer.Stats stats = indexer(jdbc, directory(root)).index();

        assertThat(stats.removed()).isEqualTo(1);
        verify(jdbc).update(contains("delete from claw4j_rag.document"), eq("pricing.md"));
    }

    @Test
    @DisplayName("只有空白的文件不算一块：一个还没写内容的 placeholder 不该让启动失败")
    void aBlankFileLeavesNoChunk(@TempDir Path root) throws Exception {
        write(root, "todo.md", "   \n\n  ");

        JdbcTemplate jdbc = jdbc();
        when(jdbc.queryForObject(contains("count(*) from claw4j_rag.chunk"), eq(Integer.class)))
                .thenReturn(0);

        KnowledgeIndexer.Stats stats = indexer(jdbc, directory(root)).index();

        assertThat(stats.chunks()).isZero();
        verify(jdbc, never()).update(contains("insert into claw4j_rag.chunk"), any(), any(), any(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("目录里一个可索引文件都没有：失败并点名，不是「建起来了但库是空的」")
    void anEmptyDirectoryFailsLoudly(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("notes.json"), "{}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> indexer(jdbc(), directory(root)).index())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可索引");
    }

    @Test
    @DisplayName("路径不是目录（写错了）：同样当场失败，不安静地建出一个空库")
    void aMissingDirectoryFailsLoudly(@TempDir Path root) {
        assertThatThrownBy(() -> indexer(jdbc(), directory(root.resolve("nope"))).index())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是一个目录");
    }

    @Test
    @DisplayName("压根没配目录：索引器自己也不接受，装配期就该拦住")
    void withoutADirectoryItRefusesToIndex() {
        assertThatThrownBy(() -> indexer(jdbc(), RagProperties.defaults()).index())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claw4j.rag.directory");
    }

    @Test
    @DisplayName("向量算不出来就失败：索引没建起来，不能假装建起来了")
    void anEmbeddingThatComesBackNullFailsTheIndex(@TempDir Path root) throws Exception {
        write(root, "after-sales.md", "华东区退货七天内可退换。");

        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        KnowledgeIndexer indexer = indexer(jdbc(), embeddings, directory(root));
        when(embeddings.embed(anyString())).thenReturn(null);

        assertThatThrownBy(indexer::index)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("返回空");
    }

    // ---------------------------------------------------------------- 检索

    @Test
    @DisplayName("两路各出一半：都排过的浮到最前，只有一路命中的也都在")
    void bothChannelsAreFusedIntoOneAnswer() {
        List<String> statements = new ArrayList<>();
        JdbcTemplate jdbc = jdbc();
        doAnswer(answer -> {
            String sql = answer.getArgument(0);
            statements.add(sql);
            RowMapper<?> mapper = answer.getArgument(1);
            return sql.contains("with scored as")
                    ? rows(mapper, hit("after-sales.md", 1, "华东区退货七天内可退换", 0.91),
                    hit("pricing.md", 1, "华东区满 199 包邮", 0.62))
                    : rows(mapper, hit("pricing.md", 1, "华东区满 199 包邮", 0.30),
                    hit("logistics.md", 2, "华南区次日达", 0.21));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(), any(), any());

        List<KnowledgeBase.Hit> hits = knowledgeBase(jdbc).search("华东区怎么退货");

        assertThat(hits).as("RRF 只数排名：两路都排到的排在最前，其余按各自的排名接在后面")
                .extracting(KnowledgeBase.Hit::source)
                .containsExactly("pricing.md", "after-sales.md", "logistics.md");
        assertThat(statements).as("两路都要发出去：删掉一路这条会红").hasSize(2);
    }

    @Test
    @DisplayName("空问题一条 SQL 都不发：省一次往返，也免得「这条 SQL 为什么永远返回空」")
    void aBlankQueryTouchesNeitherChannel() {
        JdbcTemplate jdbc = jdbc();
        EmbeddingModel embeddings = mock(EmbeddingModel.class);

        assertThat(knowledgeBase(jdbc, embeddings).search("   ")).isEmpty();
        verifyNoInteractions(jdbc);
        verifyNoInteractions(embeddings);
    }

    @Test
    @DisplayName("向量服务抖了：只丢那一路，关键词那一路照样给答案")
    void aBrokenEmbeddingLeavesTheKeywordChannelAlone() {
        List<String> statements = new ArrayList<>();
        JdbcTemplate jdbc = jdbc();
        doAnswer(answer -> {
            statements.add(answer.getArgument(0));
            RowMapper<?> mapper = answer.getArgument(1);
            return rows(mapper, hit("after-sales.md", 1, "华东区退货七天内可退换", 0.30));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(), any(), any());
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        PgVectorKnowledgeBase knowledge = knowledgeBase(jdbc, embeddings);
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("通义 503"));

        List<KnowledgeBase.Hit> hits = knowledge.search("退货政策");

        assertThat(hits).extracting(KnowledgeBase.Hit::source).containsExactly("after-sales.md");
        assertThat(statements).as("向量那一路没发出去，关键词那一路发了")
                .hasSize(1).allSatisfy(sql -> assertThat(sql).doesNotContain("with scored as"));
    }

    @Test
    @DisplayName("问题里全是标点：关键词那一路跳过，不是发一条永远为空的查询")
    void aQueryWithNothingToTokeniseSkipsTheKeywordChannel() {
        List<String> statements = new ArrayList<>();
        JdbcTemplate jdbc = jdbc();
        doAnswer(answer -> {
            statements.add(answer.getArgument(0));
            return rows(answer.getArgument(1), hit("after-sales.md", 1, "华东区退货七天内可退换", 0.8));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(), any(), any());

        List<KnowledgeBase.Hit> hits = knowledgeBase(jdbc).search("？！！");

        assertThat(hits).hasSize(1);
        assertThat(statements).hasSize(1).allSatisfy(sql -> assertThat(sql).doesNotContain("ts_rank"));
    }

    @Test
    @DisplayName("库里有几块：count 拿不到数就当 0，不抛")
    void chunkCountFallsBackToZero() {
        JdbcTemplate jdbc = jdbc();
        when(jdbc.queryForObject(contains("count(*) from claw4j_rag.chunk"), eq(Integer.class)))
                .thenReturn(7, (Integer) null);

        KnowledgeBase knowledge = knowledgeBase(jdbc);

        assertThat(knowledge.chunkCount()).isEqualTo(7);
        assertThat(knowledge.chunkCount()).as("null 不是「未知」，是「一段都没有」").isZero();
    }

    // ---------------------------------------------------------------- 工具

    @Test
    @DisplayName("工具的回答里每段都带出处：这就是「回答带出处」与「把文档当答案吐出去」的分界")
    void theAnswerCarriesTheCitationOfEveryPassage() {
        KnowledgeTools tools = new KnowledgeTools(new FixedBase(List.of(
                hit("after-sales.md", 2, "退货运费由买家承担。", 0.5),
                hit("pricing.txt", 1, "华东区满 199 包邮。", 0.3)), 12));

        String answer = tools.searchDocs("退货谁出运费");

        assertThat(answer).contains("检索到 2 段")
                .contains("[1] after-sales.md 第 2 段").contains("退货运费由买家承担。")
                .contains("[2] pricing.txt 第 1 段");
    }

    @Test
    @DisplayName("「库是空的」与「没有这段」说两句不同的话：前者是部署问题，后者是知识没写")
    void anEmptyLibraryIsSaidAwayFromNoMatch() {
        String empty = new KnowledgeTools(new FixedBase(List.of(), 0)).searchDocs("退货政策");
        String noMatch = new KnowledgeTools(new FixedBase(List.of(), 12)).searchDocs("退货政策");

        assertThat(empty).contains("一段文档都没有").contains("部署");
        assertThat(noMatch).as("有 12 段却一条没命中：问题问得不对，或这段确实没写")
                .contains("12 段").contains("没有匹配到");
        assertThat(empty).isNotEqualTo(noMatch);
    }

    // ---------------------------------------------------------------- 装配与授权

    @Test
    @DisplayName("知识库默认关：不配 claw4j.rag.enabled 时没有检索 bean，也没有工具")
    void knowledgeBaseIsOffByDefault() {
        ragRunner().run(context -> assertThat(context)
                .as("默认关是有意的：它要一份文档目录与一次 embedding 调用，绝大多数部署没有这两样")
                .doesNotHaveBean(KnowledgeBase.class)
                .doesNotHaveBean(KnowledgeTools.class));
    }

    @Test
    @DisplayName("开着却没配目录：启动失败并点名，不是「启动成功但库是空的」")
    void knowledgeBaseWithoutDirectoryFailsLoudly() {
        ragRunner().withPropertyValues("claw4j.rag.enabled=true").run(context -> {
            assertThat(context)
                    .as("空知识库的表现是每次检索都答「没有相关资料」，"
                            + "而那与「这段确实没写」在用户看来完全一样")
                    .hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("claw4j.rag.directory");
        });
    }

    /**
     * 授权：{@code read} 动作 + 伪表名 {@code doc}。
     *
     * <p>这一条只断言"登记对了、判据对得上"。**"被拒之后检索一次都没执行"不在这里**：
     * 那是鉴权 Advisor 的通用行为（它把工具调用请求整个拦下，不让它进入工具循环），
     * 已由 {@code ObservabilityTest#deniedCallsAreCountedByTheAuthorizationAdvisor}
     * 用探针把 {@code tool.calls()} 断言成 0。这里再验一遍就是验 Advisor，不是验知识库。
     */
    @Test
    @DisplayName("searchDocs 登记成 read + 伪表 doc：只给 region 的身份看不到知识库")
    void searchDocsIsOneReadActionOnTheDocTable() {
        RagAutoConfiguration config = new RagAutoConfiguration();
        ToolPermissionRegistry permissions = config.knowledgeToolPermissionRegistry();
        ToolPermission permission = permissions.permissionFor(KnowledgeTools.TOOL_NAME).orElseThrow();

        assertThat(permission.action()).isEqualTo(ToolPermission.READ);
        assertThat(permission.tables()).containsExactly(RagAutoConfiguration.DOC_TABLE);
        assertThat(Principal.of("u-1", "read-only", Set.of("*"), Set.of(ToolPermission.READ))
                .canUse(permission)).isTrue();
        assertThat(Principal.of("u-2", "guest", Set.of("region"), Set.of(ToolPermission.READ))
                .canUse(permission))
                .as("guest 能读 region 不等于能读知识库——这是想让他看不到，不是漏配")
                .isFalse();
        assertThat(config.knowledgeToolTierRegistry().tierFor(KnowledgeTools.TOOL_NAME))
                .as("与查表同一档：只会聊天的部署也该答得出政策，那是它的本职")
                .contains(ToolTier.MESSAGING);
    }

    // ---------------------------------------------------------------- 零件

    /** 起一个能走到"目录校验"的上下文：库、向量模型、事务管理器都给，失败只可能来自配置。 */
    private static ApplicationContextRunner ragRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RagAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(JdbcTemplate.class, KnowledgeRetrievalTest::jdbc)
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .withBean(PlatformTransactionManager.class, KnowledgeRetrievalTest::transactions);
    }

    private static KnowledgeIndexer indexer(JdbcTemplate jdbc, RagProperties properties) {
        return indexer(jdbc, mock(EmbeddingModel.class), properties);
    }

    /** 索引器：向量一律给同一份，测试关心的是"发了几条 SQL、块长怎么算"。 */
    private static KnowledgeIndexer indexer(JdbcTemplate jdbc, EmbeddingModel embeddings,
                                           RagProperties properties) {
        if (embeddings != null) {
            when(embeddings.embed(anyString())).thenReturn(VECTOR);
        }
        return new KnowledgeIndexer(jdbc, embeddings, properties, transactions());
    }

    private static PgVectorKnowledgeBase knowledgeBase(JdbcTemplate jdbc) {
        return knowledgeBase(jdbc, mock(EmbeddingModel.class));
    }

    private static PgVectorKnowledgeBase knowledgeBase(JdbcTemplate jdbc, EmbeddingModel embeddings) {
        when(embeddings.embed(anyString())).thenReturn(VECTOR);
        return new PgVectorKnowledgeBase(jdbc, embeddings, RagProperties.defaults());
    }

    private static RagProperties directory(Path root) {
        return new RagProperties(root.toString(), 5, 0.5, 800);
    }

    private static JdbcTemplate jdbc() {
        return mock(JdbcTemplate.class);
    }

    private static PlatformTransactionManager transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return manager;
    }

    private static void write(Path root, String name, String content) throws Exception {
        Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** 让真正的行映射跑一遍：列名写错就取不到值，而不是被替身蒙过去。 */
    private static List<KnowledgeBase.Hit> rows(RowMapper<?> mapper, KnowledgeBase.Hit... hits)
            throws SQLException {
        List<KnowledgeBase.Hit> mapped = new ArrayList<>(hits.length);
        for (int i = 0; i < hits.length; i++) {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("source")).thenReturn(hits[i].source());
            when(rs.getInt("ordinal")).thenReturn(hits[i].ordinal());
            when(rs.getString("text")).thenReturn(hits[i].text());
            when(rs.getDouble("score")).thenReturn(hits[i].score());
            mapped.add((KnowledgeBase.Hit) mapper.mapRow(rs, i));
        }
        return mapped;
    }

    private static KnowledgeBase.Hit hit(String source, int ordinal, String text, double score) {
        return new KnowledgeBase.Hit(source, ordinal, text, score);
    }

    /** 只回答"有哪几段"的检索：工具那一半只关心怎么说话，不关心怎么查。 */
    private record FixedBase(List<KnowledgeBase.Hit> hits, int chunks) implements KnowledgeBase {

        @Override
        public List<Hit> search(String query) {
            return hits;
        }

        @Override
        public int chunkCount() {
            return chunks;
        }
    }
}
