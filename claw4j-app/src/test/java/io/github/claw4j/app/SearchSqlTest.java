package io.github.claw4j.app;

import io.github.claw4j.agent.cache.CacheProperties;
import io.github.claw4j.agent.inbound.JdbcInboundStore;
import io.github.claw4j.agent.cache.PgVectorSemanticCache;
import io.github.claw4j.agent.cache.SemanticCache;
import io.github.claw4j.agent.model.EmbeddingProperties;
import io.github.claw4j.agent.rag.KnowledgeBase;
import io.github.claw4j.agent.rag.PgVectorKnowledgeBase;
import io.github.claw4j.agent.rag.RagProperties;
import io.github.claw4j.domain.inbound.ClaimedInbound;
import io.github.claw4j.domain.inbound.EnqueueOutcome;
import io.github.claw4j.domain.inbound.QueuedInbound;
import io.github.claw4j.text.ChineseText;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两个检索能力的 SQL 真跑在 pgvector 上：语义缓存（"这个问题问过没有"）与知识库（"哪段文档相关"）。
 *
 * <p><b>为什么这两件事写在一份里</b>：它们共用同一个实例、同一个扩展、同一批迁移，差别只在语义。
 * 分开写意味着两份容器启动与两份迁移，而两边验的都是"这段 SQL 真的能跑"。
 * 更重要的是它们必须**看得见彼此**：知识库这一票的一半判据是"文档块不许出现在缓存表里"，
 * 那条断言只有在一份文件里才能一眼看出它指的是哪两张表。
 *
 * <p><b>为什么这些必须是真库</b>：{@code ?::vector}、生成列 {@code to_tsvector('simple', …)}、
 * {@code @@ to_tsquery}、{@code on conflict}——全部只在 Postgres 里成立。生成列尤其：
 * 它要求函数 immutable，不满足时**建表就失败**，而那件事没有任何单测能提前告诉你。
 *
 * <p>类名从 {@code SemanticCacheSqlTest} 改成现在这个，是因为它现在管两件事；不改的话，
 * 下一个要查"知识库的 SQL 测过吗"的人会去翻第二个文件，然后发现没有。
 */
class SearchSqlTest {

    /**
     * 实体签名是 SHA-256 十六进制，正好 64 个字符，把 {@code entities varchar(64)} 卡满。
     * 拿 {@code "sig-a"} 这种五个字符的串去写，列宽被改小了照样绿——那是这条用例本来要防的事。
     */
    private static final String SIG_LAST_WEEK = "0f".repeat(32);

    private static final String SIG_THIS_WEEK = "1e".repeat(32);

    private static final String FP_READ_SALES = "fp-1";

    private static final String QUESTION = "上周华东成交额多少";

    /** 入站队列的队列名：与生产同口径，就是通道名。 */
    private static final String QUEUE = "feishu";

    /** 一个含"退货政策"字样的文档段：关键词通道该命中它。 */
    private static final String RETURNS_TEXT = "华东区自签收之日起 7 天内可无理由退货，这是本区的退货政策。";

    /** 一段语义接近但字面完全不重合的文字：只有向量通道能把它找回来。 */
    private static final String FRESH_TEXT = "生鲜与定制类商品不适用上述条款，签收后不再受理。";

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

    private PgVectorSemanticCache cache;

    @BeforeAll
    static void dataSource() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void freshTables() {
        jdbc.update("truncate claw4j_cache.semantic");
        // cascade：块随文档一起走，不需要第二句 truncate。
        jdbc.update("truncate claw4j_rag.document cascade");
        // 入站队列（票 04）：它的行是 tombstone，不清的话上一条用例的"这条处理过"会带到这里
        jdbc.update("truncate claw4j_channel.inbound_event");
        cache = new PgVectorSemanticCache(jdbc, CacheProperties.defaults());
    }

    // ---- 语义缓存 ----

    @Test
    @DisplayName("写进去的能原样取回来：类型转换与向量维度都对得上")
    void storedAnswerComesBack() {
        cache.store(FP_READ_SALES, SIG_LAST_WEEK, QUESTION, vector(1), "上周华东成交 128400 元");

        Optional<SemanticCache.Candidate> found = cache.nearest(FP_READ_SALES, SIG_LAST_WEEK, vector(1));

        assertThat(found).as("同一个上下文里刚写的一条，取不回来就是表没建成").isPresent();
        assertThat(found.get().answer()).isEqualTo("上周华东成交 128400 元");
        assertThat(found.get().question()).isEqualTo(QUESTION);
        assertThat(found.get().similarity()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("同一个上下文写两遍只留一行，换个实体各留一行：唯一键必须含 entities")
    void sameQuestionOverwritesWithinOneContextOnly() {
        cache.store(FP_READ_SALES, SIG_LAST_WEEK, QUESTION, vector(1), "旧的");
        cache.store(FP_READ_SALES, SIG_LAST_WEEK, QUESTION, vector(1), "新的");

        assertThat(cache.nearest(FP_READ_SALES, SIG_LAST_WEEK, vector(1)).orElseThrow().answer())
                .as("同一个上下文里应当覆盖，不是新增")
                .isEqualTo("新的");

        cache.store(FP_READ_SALES, SIG_THIS_WEEK, QUESTION, vector(1), "另一段时间");

        assertThat(rowCount())
                .as("同一个问题落在两个实体上下文里就是两行：唯一键少了 entities，"
                        + "后写的会静默覆盖先写的，两个时间窗从此共用一个答案")
                .isEqualTo(2);
        assertThat(cache.nearest(FP_READ_SALES, SIG_LAST_WEEK, vector(1)).orElseThrow().answer())
                .as("另一段时间写进来，不该动到这段时间的答案")
                .isEqualTo("新的");
    }

    @Test
    @DisplayName("上下文不同就不互相检索，但诊断两条路看得见它")
    void otherContextIsNotAHit() {
        cache.store(FP_READ_SALES, SIG_LAST_WEEK, QUESTION, vector(1), "答案");

        assertThat(cache.nearest(FP_READ_SALES, SIG_THIS_WEEK, vector(1)))
                .as("换个实体就是另一个上下文，这是命中判定的第一条")
                .isEmpty();
        assertThat(cache.nearest("fp-2", SIG_LAST_WEEK, vector(1)))
                .as("换个权限也不再同一个上下文里")
                .isEmpty();

        assertThat(cache.nearestInFingerprint(FP_READ_SALES, vector(1)))
                .as("同权限不限实体：用来把 miss 说成 different-entities")
                .isPresent();
        assertThat(cache.nearestAnyFingerprint(vector(1)))
                .as("全库：用来把 miss 说成 different-permission")
                .isPresent();
    }

    @Test
    @DisplayName("过期的行不参与命中，清理任务真删得掉")
    void expiredRowIsInvisibleAndThenDeleted() {
        cache.store(FP_READ_SALES, SIG_LAST_WEEK, QUESTION, vector(1), "答案");
        jdbc.update("update claw4j_cache.semantic set expires_at = now() - interval '1 minute'");

        assertThat(cache.nearest(FP_READ_SALES, SIG_LAST_WEEK, vector(1)))
                .as("过期判定写在 where 里而不是取出来再过滤：一条过期的老问题不该参与竞争")
                .isEmpty();
        assertThat(cache.deleteExpired()).isEqualTo(1);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("列型对得上：embedding 的维度来自配置，entities 的宽度要装得下一个签名")
    void columnTypesMatchWhatTheCodeWrites() {
        assertThat(columnType("claw4j_cache.semantic", "embedding"))
                .as("两边各改一半的话，插入时才报错——而那个异常在生产里被 remember 吞掉了")
                .isEqualTo("vector(" + EmbeddingProperties.DEFAULT_DIMENSIONS + ")");

        assertThat(columnType("claw4j_cache.semantic", "entities"))
                .as("签名是 64 个十六进制字符，正好卡满这一列。列宽被改小之后 store 抛错、"
                        + "被 remember 吞掉，表现是缓存永远命不中")
                .isEqualTo("character varying(64)");
    }

    // ---- 知识库（P10） ----

    @Test
    @DisplayName("两路各自都在起作用：字面命中的与语义接近的都要回来")
    void eachChannelContributes() {
        // A 段：文字里有"退货"，但向量离问题很远（正交，相似度 0 → 低于 0.5 的下限）。
        insertChunk("after-sales.md", 1, RETURNS_TEXT, vector(5));
        // B 段：字面与"退货政策"完全不重合，向量与问题同向 → 只有向量通道能捞到它。
        insertChunk("exceptions.md", 1, FRESH_TEXT, vector(0));

        List<KnowledgeBase.Hit> hits = knowledgeReturningQueryVector().search("退货政策");

        assertThat(hits)
                .as("两路各贡献一条；只做成一路的话这里会少一条，而『能查到』那种断言照样绿")
                .extracting(KnowledgeBase.Hit::source)
                .containsExactlyInAnyOrder("after-sales.md", "exceptions.md");
        assertThat(hits).allSatisfy(hit -> assertThat(hit.citation())
                .as("出处是这一层的存在理由，任何一条都不能缺")
                .contains(hit.source())
                .contains("第 " + hit.ordinal() + " 段"));
    }

    @Test
    @DisplayName("中文关键词真能命中：整句当 token 的那种实现在这里会红")
    void chineseKeywordActuallyMatches() {
        insertChunk("after-sales.md", 1, RETURNS_TEXT, vector(5));
        insertChunk("pricing.md", 1, "折扣按实付金额退款，不按标价退款。", vector(5));

        // 只让关键词通道工作：mock 的 embed 返回 null（算不出向量）。
        List<KnowledgeBase.Hit> hits = knowledgeWithoutVectors().search("退货政策");

        assertThat(hits)
                .as("bigrams 列与生成列 tsv 是这条用例的全部；把原文直接交给 to_tsvector 的话，"
                        + "整句是一个 token，这里会是空")
                .extracting(KnowledgeBase.Hit::source)
                .containsExactly("after-sales.md");
    }

    @Test
    @DisplayName("单字查询走前缀：不是空，也不是全库")
    void singleCharacterQueryUsesPrefix() {
        insertChunk("after-sales.md", 1, RETURNS_TEXT, vector(5));
        insertChunk("pricing.md", 1, "折扣按实付金额退款，不按标价退款。", vector(5));

        List<KnowledgeBase.Hit> hits = knowledgeWithoutVectors().search("退");

        assertThat(hits)
                .as("两段里都含『退』（退货 / 退款），所以两条都要在——空说明前缀没生效，"
                        + "两条以上说明 tsquery 退化成别的语义")
                .hasSize(2);
    }

    @Test
    @DisplayName("出处指得回原文：source + ordinal 能取回同一段文字")
    void citationPointsBackToTheSource() {
        insertChunk("after-sales.md", 2, RETURNS_TEXT, vector(0));

        KnowledgeBase.Hit hit = knowledgeReturningQueryVector().search("退货政策").getFirst();

        assertThat(jdbc.queryForObject("""
                select c.text from claw4j_rag.chunk c
                join claw4j_rag.document d on d.id = c.document_id
                where d.source = ? and c.ordinal = ?
                """, String.class, hit.source(), hit.ordinal()))
                .as("出处必须能指回原文，否则它只是一行好看的字符串")
                .isEqualTo(hit.text());
    }

    @Test
    @DisplayName("不是缓存：文档块不进缓存表，同一段检索两次都带出处")
    void documentsNeverLandInTheSemanticCache() {
        insertChunk("after-sales.md", 1, RETURNS_TEXT, vector(0));

        KnowledgeBase knowledge = knowledgeReturningQueryVector();
        List<KnowledgeBase.Hit> first = knowledge.search("退货政策");
        List<KnowledgeBase.Hit> second = knowledge.search("退货政策");

        assertThat(second).as("第二次照样真的去查，不像缓存那样返回上一次的回答")
                .hasSize(first.size())
                .allSatisfy(hit -> assertThat(hit.citation()).isNotBlank());
        assertThat(rowCount())
                .as("缓存表里出现文档块，就是这套设计最坏的形态：一段文档被当成『有人问过』的答案")
                .isZero();
    }

    @Test
    @DisplayName("tsv 是生成列、索引是 GIN：这两件事只有建表时才知道成不成立")
    void tsvIsAGeneratedColumnWithAGinIndex() {
        assertThat(jdbc.queryForObject("""
                select attgenerated from pg_attribute
                where attrelid = 'claw4j_rag.chunk'::regclass and attname = 'tsv'
                """, String.class))
                .as("生成列要求 to_tsvector('simple', …) 是 immutable；换成依赖配置的写法就建不出来")
                .isEqualTo("s");

        assertThat(jdbc.queryForList("""
                select indexdef from pg_indexes
                where tablename = 'chunk' and schemaname = 'claw4j_rag'
                """, String.class))
                .anySatisfy(definition -> assertThat(definition).containsIgnoringCase("gin").contains("tsv"));

        assertThat(columnType("claw4j_rag.chunk", "embedding"))
                .as("与缓存那张表同一个维度：两张表用的是同一个模型")
                .isEqualTo("vector(" + EmbeddingProperties.DEFAULT_DIMENSIONS + ")");
    }

    // ---- 夹具 ----

    /**
     * 插一份文档的一段。
     *
     * <p>{@code bigrams} 走生产代码里的同一个切分（{@link ChineseText#bigrams}），不在这里另写一份——
     * 另写一份的话，切错了这条用例也跟着错，两边一起绿。
     */
    private static void insertChunk(String source, int ordinal, String text, float[] embedding) {
        Long documentId = jdbc.queryForObject("""
                insert into claw4j_rag.document (source, content_hash) values (?, ?)
                on conflict (source) do update set content_hash = excluded.content_hash
                returning id
                """, Long.class, source, "hash-" + source);
        jdbc.update("""
                insert into claw4j_rag.chunk (document_id, ordinal, text, bigrams, embedding)
                values (?, ?, ?, ?, ?::vector)
                """, documentId, ordinal, text, ChineseText.bigrams(text), literal(embedding));
    }

    /** 向量通道可用：问题被映射到第 0 维，与"语义接近"的那一段同向。 */
    private static KnowledgeBase knowledgeReturningQueryVector() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed("退货政策")).thenReturn(vector(0));
        return new PgVectorKnowledgeBase(jdbc, embeddings, RagProperties.defaults());
    }

    /** 向量通道不可用（embed 返回 null）：剩下的结果全部来自关键词那一路。 */
    private static KnowledgeBase knowledgeWithoutVectors() {
        return new PgVectorKnowledgeBase(jdbc, mock(EmbeddingModel.class), RagProperties.defaults());
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

    private static int rowCount() {
        return jdbc.queryForObject("select count(*) from claw4j_cache.semantic", Integer.class);
    }

    private static String columnType(String table, String column) {
        return jdbc.queryForObject("""
                select format_type(atttypid, atttypmod)
                from pg_attribute
                where attrelid = ?::regclass and attname = ?
                """, String.class, table, column);
    }
}
