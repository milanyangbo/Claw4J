package io.github.claw4j.app;

import io.github.claw4j.agent.inbound.JdbcInboundStore;
import io.github.claw4j.agent.model.EmbeddingProperties;
import io.github.claw4j.agent.rag.HybridDocumentRetriever;
import io.github.claw4j.agent.rag.KeywordDocumentRetriever;
import io.github.claw4j.agent.rag.KnowledgeIndexer;
import io.github.claw4j.agent.rag.RagProperties;
import io.github.claw4j.agent.rag.WeightedScoreJoiner;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.agent.session.SessionEntryStore;
import io.github.claw4j.agent.session.CrossSessionEvents;
import io.github.claw4j.agent.session.JdbcSessionEntryStore;
import io.github.claw4j.agent.session.JdbcCrossSessionEvents;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.inbound.ClaimedInbound;
import io.github.claw4j.domain.inbound.EnqueueOutcome;
import io.github.claw4j.domain.inbound.QueuedInbound;
import io.github.claw4j.domain.inbound.Retention;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 几条只有 Postgres 才成立的 SQL 真跑一遍：知识库检索（向量 + 全文）、入站队列、跨会话召回。
 *
 * <p>合成一份是因为它们共用同一个实例、同一个扩展、同一批迁移，分开写就是几份容器启动与几份迁移，
 * 而验的是同一件事：这段 SQL 真的能跑。
 *
 * <p>必须真库：{@code ?::vector}、生成列 {@code to_tsvector('simple', …)}、{@code @@ to_tsquery}、
 * {@code on conflict} 全部只在 Postgres 里成立。生成列尤其，它要求函数 immutable，不满足时
 * 建表就失败，那件事没有任何单测能提前告诉你。
 */
class SearchSqlTest {

    /** 入站队列的队列名：与生产同口径，就是通道名。 */
    private static final String QUEUE = "feishu";

    /** 容器要在任何 @BeforeAll 之前起来：迁移要读它的端口。 */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    private static JdbcTemplate jdbc;

    /**
     * 框架的向量库。表由它建（迁移里不建），所以必须在这里建一次——不建的话
     * {@code truncate} 与检索都指向一个不存在的对象。
     */
    private static VectorStore STORE;

    /** 每个用例一份文档目录，互不串味。 */
    @TempDir
    Path docs;

    @BeforeAll
    static void dataSource() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        STORE = buildStore();
    }

    @BeforeEach
    void freshTables() {
        // 块与文件账都清：上一个用例的文档留在库里，会让"只回一条"变成"回两条"，
        // 而那种失败看起来像阈值失效。
        jdbc.update("truncate claw4j_rag.vector_store");
        jdbc.update("truncate claw4j_rag.source");
        // 索引身份也要清：留着上一个用例的身份会让"第一次索引"变成"跳过 40 份"
        jdbc.update("truncate claw4j_rag.index_meta");
        // 入站队列（票 04）：它的行是 tombstone，不清的话上一条用例的"这条处理过"会带到这里
        jdbc.update("truncate claw4j_channel.inbound_event");
        // 会话（票 05）：事件靠外键跟着会话走，一句 cascade 两表都干净
        jdbc.update("truncate claw4j_session.ai_session cascade");
    }

    // ---- 知识库（P10）：混合检索（向量 + 全文） ----

    @Test
    @DisplayName("编号类查询只有全文那一路找得到：SKU-2024 不会连 SKU-2025 一起捞回来")
    void keywordLaneFindsTheExactCode() {
        indexCorpus();
        List<Document> hits = new KeywordDocumentRetriever(jdbc, "docs", 15)
                .retrieve(question("SKU-2024 还剩多少"));
        List<String> citations = citations(hits);

        assertThat(citations)
                .as("整句「SKU-2024 还剩多少」在文档里并不存在，能命中说明抽了字面锚点")
                .isNotEmpty();
        assertThat(citations).allMatch(c -> c.startsWith("warehouse/sku-2024-stock.md"));
        assertThat(hits).allSatisfy(hit -> assertThat(hit.getScore())
                .as("命中次数要落进 score：融合按它加权，缺了就是全文那一路白召回")
                .isPositive());
    }

    @Test
    @DisplayName("混合之后的第 1 条就是那份文档：两路都命中，加权融合把它顶到最前")
    void hybridRanksTheExactDocumentFirst() {
        indexCorpus();

        assertThat(citations(search("SKU-2024 还剩多少", true)).getFirst())
                .as("向量那一路只认「warehouse 那一类」，精确到哪一份要靠全文 + 融合")
                .startsWith("warehouse/sku-2024-stock.md");
    }

    @Test
    @DisplayName("近义改写只有向量那一路接得住：字面完全不重合")
    void vectorLaneCatchesParaphrase() {
        indexCorpus();

        List<String> citations = citations(search("东西买了能退吗", true));

        assertThat(citations)
                .as("「东西买了能退吗」与「自签收之日起 7 天内可无理由退货」一个字都不重，"
                        + "能召回退货那组说明向量那一路接住了")
                .anyMatch(c -> c.startsWith("returns/"));
        assertThat(citations)
                .as("向量那一路不能只靠关键词：命中「退货」那几句时，不含它的段落不该排在前面")
                .allMatch(c -> !c.startsWith("warehouse/sku"));
    }

    @Test
    @DisplayName("出处指得回原文：path + index 能在库里取回同一段文字")
    void citationPointsBackToTheSource() {
        indexCorpus();

        Document hit = search("退货政策", true).getFirst();
        String path = String.valueOf(hit.getMetadata().get("path"));
        int index = ((Number) hit.getMetadata().get("index")).intValue();

        assertThat(jdbc.queryForObject("""
                select content from claw4j_rag.vector_store
                where metadata->>'path' = ? and (metadata->>'index')::int = ?
                """, String.class, path, index))
                .as("出处必须能指回原文，否则它只是一行好看的字符串")
                .isEqualTo(hit.getText());
    }

    @Test
    @DisplayName("内容没变就跳过：第二次同步不重算 embedding")
    void unchangedFileIsSkipped() {
        copyCorpus();
        KnowledgeIndexer indexer = new KnowledgeIndexer(jdbc, store(), embeddings(), properties());

        KnowledgeIndexer.Stats first = indexer.index();
        KnowledgeIndexer.Stats second = indexer.index();

        assertThat(first.indexed()).isEqualTo(40);
        assertThat(second.skipped()).as("靠的是 claw4j_rag.source 里那份 hash").isEqualTo(40);
        assertThat(second.indexed()).isZero();
        assertThat(second.chunks()).as("跳过的那 40 份不能被清空").isEqualTo(first.chunks());
    }

    @Test
    @DisplayName("切块身份变了：内容一个字节没改也要全量重建（照 OpenClaw 的 index identity）")
    void indexMetaChangeRebuildsEverything() {
        copyCorpus();
        KnowledgeIndexer indexer = new KnowledgeIndexer(jdbc, store(), embeddings(), properties());
        indexer.index();

        // 模拟"切块实现升了一版"：身份对不上。hash 全是好的，但边界已经换了。
        jdbc.update("update claw4j_rag.index_meta set value = ? where key = ?",
                "{\"chunkingVersion\":0,\"chunkTokens\":200}", KnowledgeIndexer.META_KEY);

        KnowledgeIndexer.Stats rebuilt = indexer.index();

        assertThat(rebuilt.indexed()).as("身份不符就不该再看 hash").isEqualTo(40);
        assertThat(rebuilt.skipped()).isZero();
        assertThat(jdbc.queryForObject("select value from claw4j_rag.index_meta where key = ?",
                String.class, KnowledgeIndexer.META_KEY))
                .as("重建完写回当前身份，第二次才会跳过")
                .isEqualTo(KnowledgeIndexer.identityOf(properties().chunkSize()));
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'claw4j_rag' and table_name = 'source' and column_name like '%chunk%'
                """, Integer.class))
                .as("身份在 index_meta 里，不给文件行加列——OpenClaw 的源头表也没有")
                .isZero();
    }

    @Test
    @DisplayName("表是框架建的：id 是 uuid、向量维度跟配置走、metadata 是 json")
    void theVectorTableIsTheFrameworkShape() {
        assertThat(columnType("claw4j_rag.vector_store", "id")).isEqualTo("uuid");
        assertThat(columnType("claw4j_rag.vector_store", "content")).isEqualTo("text");
        assertThat(columnType("claw4j_rag.vector_store", "metadata")).isIn("json", "jsonb");
        assertThat(columnType("claw4j_rag.vector_store", "embedding"))
                .as("维度来自 claw4j.model.embedding：不一致时插入直接报错，"
                        + "所以这里是「建表那一步对不对」的唯一证据")
                .isEqualTo("vector(" + EmbeddingProperties.DEFAULT_DIMENSIONS + ")");
    }

    @Test
    @DisplayName("通配符被转义：问「5%」回来的每一段都必须真的含「5%」这两个字")
    void percentSignIsNotAWildcard() {
        indexCorpus();

        List<Document> hits = new KeywordDocumentRetriever(jdbc, "docs", 15)
                .retrieve(question("5%"));

        assertThat(hits).as("语料里有两段含字面「5%」（价差、盘点差异率），该被找到").isNotEmpty();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.getText())
                .as("不转义时 pattern 是 %5%%，「95 折」「5 个点」也会回来——它们不含「5%」")
                .contains("5%"));
    }

    // ---- 知识库的夹具 ----

    private void indexCorpus() {
        copyCorpus();
        new KnowledgeIndexer(jdbc, store(), embeddings(), properties()).index();
    }

    /** 把 40 份语料复制到本用例的临时目录：有些用例要改文件（重建、删除），不能动源目录。 */
    private void copyCorpus() {
        Path source = corpusRoot();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = docs.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException("复制不了语料 " + source, failure);
        }
    }

    private static Path corpusRoot() {
        try {
            return Path.of(SearchSqlTest.class.getClassLoader().getResource("rag-corpus").toURI());
        }
        catch (URISyntaxException | NullPointerException missing) {
            throw new IllegalStateException("测试类路径上找不到 rag-corpus", missing);
        }
    }

    private List<Document> search(String text, boolean hybrid) {
        DocumentRetriever vector = VectorStoreDocumentRetriever.builder()
                .vectorStore(store())
                .topK(40)
                .similarityThreshold(0.5)
                .build();
        DocumentRetriever keyword = hybrid
                ? new KeywordDocumentRetriever(jdbc, "docs", 15)
                : null;
        DocumentRetriever retriever = new HybridDocumentRetriever(vector, keyword,
                new WeightedScoreJoiner());
        return retriever.retrieve(question(text));
    }

    private static Query question(String text) {
        return new Query(text, List.of(),
                Map.of(ContextKeys.PRINCIPAL, Principal.readOnly("u-1")));
    }

    private static List<String> citations(List<Document> documents) {
        return documents.stream().map(io.github.claw4j.agent.rag.RagMetadata::citation).toList();
    }

    private RagProperties properties() {
        // 块长 200 token：40 份短文档要切出足够多的块，"候选 15 条"才有区分度。
        return new RagProperties(docs.toString(), 5, 0.5, 200, null, null);
    }

    /** 检索与索引共用同一个 store：表由它建（迁移里不建），两个用例之间也不会重复启动。 */
    private static VectorStore store() {
        return STORE;
    }

    private static VectorStore buildStore() {
        PgVectorStore store = PgVectorStore.builder(jdbc, embeddings())
                .schemaName("claw4j_rag")
                .vectorTableName("vector_store")
                .dimensions(EmbeddingProperties.DEFAULT_DIMENSIONS)
                .idType(PgVectorStore.PgIdType.UUID)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(true)
                .build();
        try {
            // 不走 Spring 生命周期，所以手动触发一次：建表就在这一步。
            store.afterPropertiesSet();
        }
        catch (Exception failure) {
            throw new IllegalStateException("建不出 vector_store", failure);
        }
        return store;
    }

    /**
     * 一个按含不含退货给向量的假模型。
     *
     * <p>两个方法都要实现：索引时 {@code PgVectorStore.add} 走 {@code call(EmbeddingRequest)}，
     * 检索时走 {@code embed(String)}，默认实现会落到 {@code embed(Document)}。只实现一个的话，
     * 另一条路会在运行时报 {@code UnsupportedOperation}，而那条错只在真库跑起来之后才出现。
     */
    private static EmbeddingModel embeddings() {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> batch = new ArrayList<>();
                List<String> inputs = request.getInstructions();
                for (int i = 0; i < inputs.size(); i++) {
                    batch.add(new Embedding(vectorFor(inputs.get(i)), i));
                }
                return new EmbeddingResponse(batch);
            }

            @Override
            public float[] embed(Document document) {
                return vectorFor(document.getText());
            }
        };
    }

    /**
     * 假模型按业务域给方向：五个域各一个正交方向，其余落到第六个。
     *
     * <p>这样语义等于属于哪个域，正是混合检索要验证的东西：向量把问题路由到正确的域，
     * 全文那一路在域内精确到某一份。真 embedding 比这细，但测试要的是可复现的路由。
     */
    private static float[] vectorFor(String text) {
        if (text == null) {
            return vector(5);
        }
        if (containsAny(text, "退", "售后", "发票")) {
            return vector(0);
        }
        if (containsAny(text, "SKU", "仓", "冷链", "拣货", "盘点")) {
            return vector(1);
        }
        if (containsAny(text, "折扣", "返点", "促销", "价保", "价差")) {
            return vector(2);
        }
        if (containsAny(text, "会员", "积分", "权益", "账户")) {
            return vector(3);
        }
        if (containsAny(text, "工单", "SLA", "投诉", "值班", "复盘", "升级")) {
            return vector(4);
        }
        return vector(5);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ---- 入站队列（票 04）：这些判断只在真库上成立 ----

    @Test
    @DisplayName("入队幂等：同一个 (队列, eventId) 插两次只有一行，第二次报 DUPLICATE_PENDING")
    void enqueueIsIdempotentOnTheCompositeKey() {
        JdbcInboundStore store = new JdbcInboundStore(jdbc);
        QueuedInbound event = queued(QUEUE, "evt-1", "conv-a", "上周成交额");

        assertThat(store.enqueue(event, "conv-a")).isEqualTo(EnqueueOutcome.ACCEPTED);
        assertThat(store.enqueue(event, "conv-a"))
                .as("on conflict do nothing 之后要回读：四种重复对调用方是三件不同的事")
                .isEqualTo(EnqueueOutcome.DUPLICATE_PENDING);
        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_channel.inbound_event", Integer.class))
                .as("重复插不加行——这就是那个主键的活")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("主键是 (queue_name, event_id)：同一个 eventId 在两个队列里各留一行")
    void theSameEventIdInTwoQueuesIsTwoRows() {
        JdbcInboundStore store = new JdbcInboundStore(jdbc);
        QueuedInbound fromFeishu = queued("feishu", "evt-shared", "conv-a", "问题");
        QueuedInbound fromSlack = queued("slack", "evt-shared", "conv-b", "问题");

        assertThat(store.enqueue(fromFeishu, "conv-a")).isEqualTo(EnqueueOutcome.ACCEPTED);
        assertThat(store.enqueue(fromSlack, "conv-b"))
                .as("同一个平台的两个 bot 各自收到同一个事件时该各跑一次：单主键会把第二个吞掉")
                .isEqualTo(EnqueueOutcome.ACCEPTED);

        assertThat(jdbc.queryForList(
                "select queue_name from claw4j_channel.inbound_event order by queue_name", String.class))
                .containsExactly("feishu", "slack");
    }

    @Test
    @DisplayName("状态流转 pending → claimed → completed：完成是 tombstone，内容抹掉、行留着")
    void theRowGoesFromPendingToAContentFreeTombstone() {
        JdbcInboundStore store = new JdbcInboundStore(jdbc);
        QueuedInbound event = queued(QUEUE, "evt-2", "conv-a", "上周成交额");
        store.enqueue(event, "conv-a");

        ClaimedInbound claimed = store.claimNext(QUEUE, Set.of(), "worker-1").orElseThrow();
        assertThat(rowOf("evt-2").get("status")).isEqualTo("claimed");
        assertThat(rowOf("evt-2").get("claim_owner")).isEqualTo("worker-1");

        assertThat(store.complete(claimed.claim(), "{}")).isTrue();

        Map<String, Object> row = rowOf("evt-2");
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat(row.get("payload_json"))
                .as("完成之后不留内容副本：要追'说了什么'去看会话与审计")
                .isEqualTo("null");
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(store.enqueue(event, "conv-a"))
                .as("行还在，所以重投撞得上它——删掉行等于重投会再跑一遍")
                .isEqualTo(EnqueueOutcome.DUPLICATE_COMPLETED);
    }

    @Test
    @DisplayName("孤儿回收：claimed 久了回 pending 且 attempts 不变；只有 release 才涨")
    void aStaleClaimIsRecoveredWithoutBurningAnAttempt() {
        JdbcInboundStore store = new JdbcInboundStore(jdbc);
        store.enqueue(queued(QUEUE, "evt-3", "conv-a", "问题"), "conv-a");
        // 造一个"上一个进程认领了十分钟还没结算"的现场
        jdbc.update("update claw4j_channel.inbound_event set status = 'claimed', claim_token ="
                + " gen_random_uuid(), claim_owner = 'dead-process',"
                + " claimed_at = now() - interval '10 minutes' where event_id = 'evt-3'");

        assertThat(store.recoverStaleClaims(QUEUE, Duration.ofMinutes(5))).isEqualTo(1);

        Map<String, Object> row = rowOf("evt-3");
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("claim_owner")).isNull();
        assertThat(((Number) row.get("attempts")).intValue())
                .as("丢了一个认领人不算一次投递失败：涨 attempts 的话重启三次就把额度耗光")
                .isZero();

        ClaimedInbound again = store.claimNext(QUEUE, Set.of(), "worker-2").orElseThrow();
        assertThat(store.release(again.claim(), "模型超时")).isTrue();
        assertThat(((Number) rowOf("evt-3").get("attempts")).intValue())
                .as("真正算一次失败的是 release")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("按状态分档清理：claimed 一行都不动，completed / failed 过了各自的 TTL 才删")
    void pruneFollowsTheStatusLadder() {
        JdbcInboundStore store = new JdbcInboundStore(jdbc);
        store.enqueue(queued(QUEUE, "evt-claimed", "conv-a", "在跑的"), "conv-a");
        store.enqueue(queued(QUEUE, "evt-pending", "conv-b", "排着很久的"), "conv-b");
        store.enqueue(queued(QUEUE, "evt-done", "conv-c", "跑完很久的"), "conv-c");
        store.enqueue(queued(QUEUE, "evt-failed", "conv-d", "死信十天的"), "conv-d");

        store.claimNext(QUEUE, Set.of(), "worker-1");
        // 四条都改成"很久以前"，再按 1 天 / 7 天 / 30 天三档清
        jdbc.update("update claw4j_channel.inbound_event set updated_at = now() - interval '40 days'");
        jdbc.update("update claw4j_channel.inbound_event set status = 'completed',"
                + " completed_at = now() - interval '8 days', payload_json = 'null'"
                + " where event_id = 'evt-done'");
        jdbc.update("update claw4j_channel.inbound_event set status = 'failed',"
                + " failed_at = now() - interval '10 days' where event_id = 'evt-failed'");

        store.prune(new Retention(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30),
                50_000, 10_000));

        assertThat(jdbc.queryForList("select event_id from claw4j_channel.inbound_event order by event_id",
                String.class))
                .as("claimed 是正在跑的，删掉它等于那条消息永久消失；failed 还没到 30 天")
                .containsExactly("evt-claimed", "evt-failed");
    }

    // ---- 跨会话召回（票 05）：一条 SQL 要跨过 user_id，而 user_id 里带着权限指纹 ----

    @Test
    @DisplayName("跨会话召回：另一个用户的会话也查得到，synthetic 排除、归档保留")
    void crossSessionRecallCrossesUsersAndSkipsSynthetic() {
        String mine = "claw4j:user:u-1@fp-a:web:chat-a:seg-1";
        String other = "claw4j:user:u-2@fp-b:web:chat-z:seg-1";
        session(mine, "u-1@fp-a");
        session(other, "u-2@fp-b");
        event("evt-mine", mine, "华东区的成交口径按签约主体算", false, false);
        event("evt-other", other, "华东区口径按签约主体算——u-2 说的", false, false);
        event("evt-synthetic", mine, "华东区口径，这条是系统提示词", true, false);
        event("evt-archived", other, "华东区口径，这条被压缩归档过", false, true);

        List<SessionEvent> hits = new JdbcCrossSessionEvents(jdbc, SessionSchema.DEFAULT_SCHEMA)
                .find(EventFilter.builder()
                        .keywords(List.of("华东区口径", "华东区的成交口径"))
                        .matchMode(EventFilter.MatchMode.ANY)
                        .page(0)
                        .pageSize(5)
                        .excludeSynthetic(true)
                        .build());

        assertThat(hits).extracting(SessionEvent::getId)
                .as("跨用户是这条 SQL 存在的理由：按 user_id 查的话 u-2 那一条永远查不到，"
                        + "而「查不到」与「没说过」在结果上一模一样")
                .contains("evt-other", "evt-mine");
        assertThat(hits).extracting(SessionEvent::getId)
                .as("系统提示词与摘要不是「我说过的话」，每轮都有一条，召回它们只是白占上下文")
                .doesNotContain("evt-synthetic");
        assertThat(hits).extracting(SessionEvent::getId)
                .as("归档的要保留：压缩把原文折成摘要之后，它是这个工具唯一的价值来源")
                .contains("evt-archived");
        assertThat(hits).allSatisfy(hit -> assertThat(hit.getTimestamp()).isNotNull());
    }

    @Test
    @DisplayName("多关键词的括号与分页参数：ANY 不能漏出 synthetic，page/pageSize 也不许串位")
    void crossSessionKeywordParenthesesAndPagingLineUp() {
        String only = "claw4j:user:u-1@fp-a:web:chat-a:seg-1";
        session(only, "u-1@fp-a");
        event("evt-synthetic-south", only, "华南那边先按开票主体报", true, false);
        event("evt-real-east", only, "华东区的成交口径按签约主体算", false, false);
        event("evt-real-south", only, "华南那边的口径还是按开票主体", false, false);
        event("evt-real-east-again", only, "华东口径再核一次，按签约主体", false, false);

        CrossSessionEvents across = new JdbcCrossSessionEvents(jdbc, SessionSchema.DEFAULT_SCHEMA);
        List<SessionEvent> any = across.find(EventFilter.builder()
                .keywords(List.of("华东区", "华南那边"))
                .matchMode(EventFilter.MatchMode.ANY)
                .page(0)
                .pageSize(5)
                .excludeSynthetic(true)
                .build());

        assertThat(any).extracting(SessionEvent::getId)
                .as("关键词的那一段必须整体加括号：不加的话 `AND synthetic = false AND a OR b` "
                        + "会让 b 一命中就无视 synthetic 与时间范围，而它看起来只是「少了个括号」")
                .doesNotContain("evt-synthetic-south")
                .contains("evt-real-east", "evt-real-south");

        // 命中三条：evt-real-east / evt-real-south / evt-real-east-again。
        // page 必须不等于 1：page=1 时 offset 恰好等于 pageSize，两个参数互换后 SQL 一字不变，
        // 那条断言就永远是绿的。
        List<SessionEvent> thirdPage = across.find(EventFilter.builder()
                .keywords(List.of("口径", "成交"))
                .matchMode(EventFilter.MatchMode.ANY)
                .page(2)
                .pageSize(1)
                .excludeSynthetic(true)
                .build());

        assertThat(thirdPage).extracting(SessionEvent::getId)
                .as("LIMIT 与 OFFSET 的参数反了会变成 `limit 1 offset 1` 的邻条："
                        + "条数从 1 变 2，而不是空——所以断言看的是「恰好那一条」，不是「非空」")
                .containsExactly("evt-real-east-again");
    }

    @Test
    @DisplayName("schema 从配置来：换一个 schema 名字，SQL 跟着走——写死的话改配之后永远查不到")
    void crossSessionEventsHonoursTheConfiguredSchema() {
        jdbc.execute("drop schema if exists claw4j_alt cascade");
        jdbc.execute("create schema claw4j_alt");
        jdbc.execute("""
                create table claw4j_alt.ai_session_event (
                    seq bigint generated by default as identity,
                    id varchar(255) not null primary key,
                    session_id varchar(255) not null,
                    timestamp timestamp not null,
                    message_type varchar(20) not null,
                    message_content text,
                    message_data text,
                    synthetic boolean not null default false,
                    archived boolean not null default false,
                    branch varchar(500),
                    metadata text)
                """);
        jdbc.update("insert into claw4j_alt.ai_session_event"
                + " (id, session_id, timestamp, message_type, message_content)"
                + " values ('alt-1', 'claw4j:user:u-9@fp:web:chat:seg', now(), 'USER', '华东口径在另一个 schema 里')");

        List<SessionEvent> hits = new JdbcCrossSessionEvents(jdbc, "claw4j_alt")
                .find(EventFilter.builder()
                        .keywords(List.of("华东口径"))
                        .pageSize(5)
                        .excludeSynthetic(true)
                        .build());

        assertThat(hits).extracting(SessionEvent::getId)
                .as("表名写死成 claw4j_session 的话这里一条都没有，而那个空结果会被降级成「没找到」——"
                        + "配了 claw4j.session.schema 却不生效，是本仓库最讨厌的那种「配了看起来生效」")
                .containsExactly("alt-1");
    }

    /**
     * 会话指针也读 {@code claw4j.session.schema}，不是只让召回那一条读。
     *
     * <p>防的是修了一半：只改召回而不改指针、目标、清理，比全都写死更难发现，因为它看起来
     * 已经修过了。
     *
     * <p>断言必须看落库位置，不能只看返回值：写死时 {@code segmentOf} 照样返回 {@code seg-1}，
     * 因为它确实写成功了，只是写进了另一张同名表。所以这里查 {@code claw4j_alt.session_nodes}
     * 的行数，那一行才证明配了哪个 schema 就写哪个。
     */
    @Test
    @DisplayName("会话指针跟着 claw4j.session.schema 走：写死的话它悄悄写进另一张表")
    void conversationPointersHonourTheConfiguredSchema() {
        jdbc.execute("drop schema if exists claw4j_alt cascade");
        jdbc.execute("create schema claw4j_alt");
        jdbc.execute("""
                create table claw4j_alt.session_nodes (
                    session_key text not null primary key,
                    current_session_id text not null,
                    entry_json jsonb not null,
                    session_started_at timestamptz,
                    last_interaction_at timestamptz,
                    created_at timestamptz,
                    updated_at timestamptz not null,
                    entry_valid smallint not null default 0);
                create table claw4j_alt.session_windows (
                    session_id text not null primary key,
                    session_key text not null,
                    previous_session_id text,
                    reason text,
                    session_scope text not null default 'conversation',
                    created_at timestamptz not null,
                    updated_at timestamptz not null,
                    started_at timestamptz,
                    chat_type text,
                    channel text,
                    account_id text,
                    primary_conversation_id text);
                create table claw4j_alt.conversations (
                    conversation_id text not null primary key,
                    channel text not null,
                    account_id text not null,
                    kind text not null,
                    peer_id text not null,
                    delivery_target text not null,
                    created_at timestamptz not null,
                    updated_at timestamptz not null);
                create table claw4j_alt.session_conversations (
                    session_id text not null,
                    conversation_id text not null,
                    role text not null default 'primary',
                    first_seen_at timestamptz not null,
                    last_seen_at timestamptz not null,
                    primary key (session_id, conversation_id, role))
                """);

        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        Optional<String> segment = new JdbcSessionEntryStore(jdbc, "claw4j_alt")
                .segmentOf(new SessionEntryStore.Key(
                                SessionKeys.conversationKeyOf("u-1@fp", "web", "c-1"),
                                "u-1@fp", "web", "c-1", true),
                        new SessionEntryStore.Rotation("seg-1", now, now.minusSeconds(7200),
                                now.minusSeconds(86_400), true, false));

        assertThat(segment).contains("seg-1");
        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_alt.session_nodes", Long.class))
                .as("写死 claw4j_session 时这一行会落到那张表里：insert 不报错、返回值照常，"
                        + "只有这里能看出配的 schema 没生效")
                .isEqualTo(1L);
    }

    /** 会话行：事件有外键，不先建会话插不进去。 */
    private static void session(String id, String userId) {
        jdbc.update("insert into claw4j_session.ai_session (id, user_id, created_at)"
                + " values (?, ?, now())", id, userId);
    }

    private static void event(String id, String sessionId, String text, boolean synthetic, boolean archived) {
        jdbc.update("insert into claw4j_session.ai_session_event"
                + " (id, session_id, timestamp, message_type, message_content, synthetic, archived)"
                + " values (?, ?, now(), 'USER', ?, ?, ?)", id, sessionId, text, synthetic, archived);
    }

    private static QueuedInbound queued(String queueName, String eventId, String conversationId, String text) {
        return new QueuedInbound(queueName, eventId, conversationId, "u-1", text,
                Instant.parse("2026-11-08T10:00:00Z"));
    }

    private static Map<String, Object> rowOf(String eventId) {
        return jdbc.queryForMap("select status, payload_json, claim_token, claim_owner, completed_at,"
                + " attempts from claw4j_channel.inbound_event where event_id = ?", eventId);
    }

    /** 1024 维、只有一位是 1：同一条向量之间余弦 1.0，不同维度上的偏移会把它拉到 0。 */
    private static float[] vector(int hotIndex) {
        float[] embedding = new float[EmbeddingProperties.DEFAULT_DIMENSIONS];
        embedding[hotIndex] = 1f;
        return embedding;
    }

    private static String literal(float[] embedding) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(embedding[i]);
        }
        return literal.append(']').toString();
    }

    private static String columnType(String table, String column) {
        return jdbc.queryForObject("""
                select format_type(atttypid, atttypmod)
                from pg_attribute
                where attrelid = ?::regclass and attname = ?
                """, String.class, table, column);
    }
}
