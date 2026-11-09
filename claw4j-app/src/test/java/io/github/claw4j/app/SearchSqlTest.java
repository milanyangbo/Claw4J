package io.github.claw4j.app;

import io.github.claw4j.agent.inbound.JdbcInboundStore;
import io.github.claw4j.agent.model.EmbeddingProperties;
import io.github.claw4j.agent.rag.KnowledgeBase;
import io.github.claw4j.agent.rag.PgVectorKnowledgeBase;
import io.github.claw4j.agent.rag.RagProperties;
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
import io.github.claw4j.text.ChineseText;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
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

    @BeforeAll
    static void dataSource() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void freshTables() {
        // cascade：块随文档一起走，不需要第二句 truncate。
        jdbc.update("truncate claw4j_rag.document cascade");
        // 入站队列（票 04）：它的行是 tombstone，不清的话上一条用例的"这条处理过"会带到这里
        jdbc.update("truncate claw4j_channel.inbound_event");
        // 会话（票 05）：事件靠外键跟着会话走，一句 cascade 两表都干净
        jdbc.update("truncate claw4j_session.ai_session cascade");
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
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_class where oid = to_regclass('claw4j_cache.semantic')",
                Integer.class))
                .as("语义缓存改落 Redis 之后这张表应当被 V9 删掉；它还在就是还有东西往里写")
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
        // **page 必须不等于 1**：page=1 时 offset 恰好等于 pageSize，两个参数互换后 SQL
        // 一字不变，那条断言就永远是绿的（第一版写的就是 page=1，假绿）。
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
     * <p><b>这条防的是"修了一半"</b>：票 05 只把跨会话召回改成读配置，指针、目标、清理三处
     * 仍然写死 {@code claw4j_session}。那比"全都写死"更难发现——看起来已经修过了。
     *
     * <p><b>断言必须看落库位置，不能只看返回值</b>：写死时 {@code segmentOf} 照样返回
     * {@code seg-1}，因为它确实写成功了，只是写进了另一张同名表。所以这里查的是
     * {@code claw4j_alt.conversation_pointer} 的行数——那一行才证明"配了哪个 schema 就写哪个"。
     */
    @Test
    @DisplayName("会话指针跟着 claw4j.session.schema 走：写死的话它悄悄写进另一张表")
    void conversationPointersHonourTheConfiguredSchema() {
        jdbc.execute("drop schema if exists claw4j_alt cascade");
        jdbc.execute("create schema claw4j_alt");
        jdbc.execute("""
                create table claw4j_alt.session_entry (
                    session_key varchar(512) not null primary key,
                    owner varchar(255) not null,
                    channel varchar(64) not null,
                    chat_id varchar(255) not null,
                    segment varchar(64) not null,
                    session_started_at timestamptz not null,
                    last_interaction_at timestamptz not null,
                    created_at timestamptz not null,
                    previous_segment varchar(64),
                    total_tokens bigint not null default 0,
                    goal_schema_version smallint,
                    goal_id uuid,
                    goal_objective text,
                    goal_status varchar(24),
                    goal_token_start bigint,
                    goal_token_start_fresh boolean,
                    goal_tokens_used bigint,
                    goal_token_budget bigint,
                    goal_continuation_turns integer,
                    goal_last_status_note text,
                    goal_paused_at timestamptz,
                    goal_blocked_at timestamptz,
                    goal_completed_at timestamptz,
                    goal_usage_limited_at timestamptz,
                    goal_budget_limited_at timestamptz,
                    goal_created_at timestamptz,
                    goal_updated_at timestamptz)
                """);

        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        Optional<String> segment = new JdbcSessionEntryStore(jdbc, "claw4j_alt")
                .segmentOf(new SessionEntryStore.Key(
                                SessionKeys.conversationKeyOf("u-1@fp", "web", "c-1"),
                                "u-1@fp", "web", "c-1"),
                        new SessionEntryStore.Rotation("seg-1", now, now.minusSeconds(7200),
                                now.minusSeconds(86_400), true, false));

        assertThat(segment).contains("seg-1");
        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_alt.session_entry", Long.class))
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
