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
 *
 * <p>这类错最坏的姿势是静默的：`on conflict` 的目标与唯一键对不上时 Postgres 直接报错，
 * 而 `CacheAdvisor.remember` 会把这个异常吞掉——表现是"缓存永远命不中"，日志里只有一行 warn。
 * 没有这条用例，改一次表结构就可能悄悄把整个缓存关掉，而所有单测照样绿。
 *
 * <p>迁移也由这里跑：表与唯一键是 `V4` / `V5` 建的，用 {@link Flyway} 而不是手写 DDL，
 * 验的是"真的会落成那样"，不是"我们认为会落成那样"。
 */
class SemanticCacheSqlTest {

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
        String question = "上周华东成交额多少";
        cache.store("fp-1", "sig-a", question, vector(1), "上周华东成交 128400 元");

        Optional<SemanticCache.Candidate> found = cache.nearest("fp-1", "sig-a", vector(1));

        assertThat(found).as("同一个上下文里刚写的一条，取不回来就是表没建成").isPresent();
        assertThat(found.get().answer()).isEqualTo("上周华东成交 128400 元");
        assertThat(found.get().question()).isEqualTo(question);
        assertThat(found.get().similarity()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("同一句话写两遍只留一行：唯一键与 on conflict 的目标必须是同一组列")
    void sameQuestionOverwritesInsteadOfDuplicating() {
        cache.store("fp-1", "sig-a", "上周华东成交额多少", vector(1), "旧的");
        cache.store("fp-1", "sig-a", "上周华东成交额多少", vector(1), "新的");

        assertThat(jdbc.queryForObject("select count(*) from claw4j_cache.semantic", Integer.class))
                .as("目标列与唯一索引对不上时 Postgres 会直接报错，而生产里那个异常被吞掉了")
                .isEqualTo(1);
        assertThat(cache.nearest("fp-1", "sig-a", vector(1)).orElseThrow().answer()).isEqualTo("新的");
    }

    @Test
    @DisplayName("上下文不同就不互相检索，但诊断两条路看得见它")
    void otherContextIsNotAHit() {
        cache.store("fp-1", "sig-a", "上周华东成交额多少", vector(1), "答案");

        assertThat(cache.nearest("fp-1", "sig-b", vector(1)))
                .as("换个实体就是另一个上下文，这是命中判定的第一条")
                .isEmpty();
        assertThat(cache.nearest("fp-2", "sig-a", vector(1)))
                .as("换个权限也不再同一个上下文里")
                .isEmpty();

        assertThat(cache.nearestInFingerprint("fp-1", vector(1)))
                .as("同权限不限实体：用来把 miss 说成 different-entities")
                .isPresent();
        assertThat(cache.nearestAnyFingerprint(vector(1)))
                .as("全库：用来把 miss 说成 different-permission")
                .isPresent();
    }

    @Test
    @DisplayName("过期的行不参与命中，清理任务真删得掉")
    void expiredRowIsInvisibleAndThenDeleted() {
        cache.store("fp-1", "sig-a", "上周华东成交额多少", vector(1), "答案");
        jdbc.update("update claw4j_cache.semantic set expires_at = now() - interval '1 minute'");

        assertThat(cache.nearest("fp-1", "sig-a", vector(1)))
                .as("过期判定写在 where 里而不是取出来再过滤：一条过期的老问题不该参与竞争")
                .isEmpty();
        assertThat(cache.deleteExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from claw4j_cache.semantic", Integer.class)).isZero();
    }

    /** 1024 维、只有第一位是 1：同一条向量之间余弦 1.0，不同维度上的偏移会把它拉低。 */
    private static float[] vector(int hotIndex) {
        float[] embedding = new float[CacheProperties.DEFAULT_EMBEDDING_DIMENSIONS];
        embedding[hotIndex] = 1f;
        return embedding;
    }

    /** 维度由配置与迁移共同决定，这里把两处对一次。 */
    @Test
    @DisplayName("配置里的维度与迁移建出来的列是同一个数")
    void dimensionsMatchTheColumn() {
        String type = jdbc.queryForObject("""
                select format_type(atttypid, atttypmod)
                from pg_attribute
                where attrelid = 'claw4j_cache.semantic'::regclass and attname = 'embedding'
                """, String.class);

        assertThat(type)
                .as("两边各改一半的话，插入时才报错——而那个异常在生产里被 remember 吞掉了")
                .isEqualTo("vector(" + CacheProperties.DEFAULT_EMBEDDING_DIMENSIONS + ")");
    }
}
