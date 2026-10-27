package io.github.claw4j.app;

import io.github.claw4j.agent.cache.CacheProperties;
import io.github.claw4j.agent.cache.PgVectorSemanticCache;
import io.github.claw4j.agent.cache.SemanticCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 语义缓存那几条 SQL 真跑在 pgvector 上。
 *
 * <p><b>为什么单独一份</b>：{@code SemanticCacheTest} 用内存版缓存，验的是"拿到分数之后怎么判"。
 * 而 SQL 里的 `?::vector`、`on conflict (fingerprint, entities, question_key)`、`make_interval`、
 * 以及 `entities varchar(64)` 与 64 位十六进制正好卡满，**一条都没被执行过**。
 * 所以这里也真的用 64 个字符的签名去写：签名只有五个字符时，列宽被改小了照样绿。
 *
 * <p>这类错最坏的姿势是静默的：`on conflict` 的目标与唯一键对不上时 Postgres 直接报错，
 * 而 `CacheAdvisor.remember` 会把这个异常吞掉——表现是"缓存永远命不中"，日志里只有一行 warn。
 * 没有这条用例，改一次表结构就可能悄悄把整个缓存关掉，而所有单测照样绿。
 *
 * <p>迁移也由这里跑：表与唯一键是 `V4` / `V5` 建的，用 {@link Flyway} 而不是手写 DDL，
 * 验的是"真的会落成那样"，不是"我们认为会落成那样"。
 */
class SemanticCacheSqlTest {

    /**
     * 实体签名是 SHA-256 十六进制，正好 64 个字符，把 {@code entities varchar(64)} 卡满。
     * 拿 {@code "sig-a"} 这种五个字符的串去写，列宽被改小也不会红——那是这条用例本来要防的事。
     */
    private static final String SIG_LAST_WEEK = "0f".repeat(32);

    private static final String SIG_THIS_WEEK = "1e".repeat(32);

    private static final String FP_READ_SALES = "fp-1";

    private static final String QUESTION = "上周华东成交额多少";

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
    void freshTable() {
        jdbc.update("truncate claw4j_cache.semantic");
        cache = new PgVectorSemanticCache(jdbc, CacheProperties.defaults());
    }

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

    /** 1024 维、只有第一位是 1：同一条向量之间余弦 1.0，不同维度上的偏移会把它拉低。 */
    private static float[] vector(int hotIndex) {
        float[] embedding = new float[CacheProperties.DEFAULT_EMBEDDING_DIMENSIONS];
        embedding[hotIndex] = 1f;
        return embedding;
    }

    private static int rowCount() {
        return jdbc.queryForObject("select count(*) from claw4j_cache.semantic", Integer.class);
    }

    private static String columnType(String column) {
        return jdbc.queryForObject("""
                select format_type(atttypid, atttypmod)
                from pg_attribute
                where attrelid = 'claw4j_cache.semantic'::regclass and attname = ?
                """, String.class, column);
    }

    @Test
    @DisplayName("列型对得上：embedding 的维度来自配置，entities 的宽度要装得下一个签名")
    void columnTypesMatchWhatTheCodeWrites() {
        assertThat(columnType("embedding"))
                .as("两边各改一半的话，插入时才报错——而那个异常在生产里被 remember 吞掉了")
                .isEqualTo("vector(" + CacheProperties.DEFAULT_EMBEDDING_DIMENSIONS + ")");

        assertThat(columnType("entities"))
                .as("签名是 64 个十六进制字符，正好卡满这一列。列宽被改小之后 store 抛错、"
                        + "被 remember 吞掉，表现是缓存永远命不中")
                .isEqualTo("character varying(64)");
    }
}
