package io.github.claw4j.agent.cache;

import io.github.claw4j.agent.PermissionFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 把语义缓存落在 pgvector 上（ADR-0005：一个 Postgres 三种存储）。
 *
 * <p><b>用全限定表名，不另开连接池</b>：这里和会话存储不一样。会话那套要独立连接池是因为
 * {@code spring-ai-session} 的 SQL 里表名不带 schema，落点由 {@code search_path} 决定；
 * 这里的 SQL 是我们自己写的，`claw4j_cache.semantic` 写死就行，主连接池照常借用。
 * 少一个池就少一处"连接数被谁吃掉了"的疑问。
 *
 * <p><b>相似度用余弦距离 {@code <=>}</b>：{@code text-embedding-v3} 的输出还没归一化，
 * 余弦与内积不等价；用 `<=>`（cosine distance）得到 `1 - distance` 就是余弦相似度，
 * 与 {@link CacheProperties} 里那个阈值的口径一致。
 */
public class PgVectorSemanticCache implements SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSemanticCache.class);

    /**
     * 找最像的一条。
     *
     * <p>{@code order by embedding <=> ?::vector} 走的是全表精确扫描（没有建向量索引，
     * 理由见迁移文件末尾）。{@code expires_at > now()} 写在 where 里而不是取出后再过滤：
     * 过期行不应该参与"最相似"的竞争，否则一条过期的老问题会把新问题挡在外面。
     */
    private static final String NEAREST = """
            select question, answer, 1 - (embedding <=> ?::vector) as similarity
            from claw4j_cache.semantic
            where fingerprint = ? and expires_at > now()
            order by embedding <=> ?::vector
            limit 1
            """;

    /**
     * 存一条，同一个 {@code fingerprint + question_key} 覆盖。
     *
     * <p>{@code make_interval(secs => ?)} 而不是把秒数拼进字符串：拼字符串是 SQL 注入的形状，
     * 而且 `now() + '3600'` 这种写法在 Postgres 里会直接报类型错。
     */
    private static final String STORE = """
            insert into claw4j_cache.semantic
                (fingerprint, question, question_key, embedding, answer, expires_at)
            values (?, ?, ?, ?::vector, ?, now() + make_interval(secs => ?))
            on conflict (fingerprint, question_key) do update
                set embedding = excluded.embedding,
                    answer = excluded.answer,
                    created_at = now(),
                    expires_at = excluded.expires_at
            """;

    /**
     * 不分权限范围的最相似一条。**只给诊断用**（区分 "no-candidate" 与 "different-permission"），
     * 结果不参与命中判定——那等于绕过授权。
     */
    private static final String NEAREST_ANY_FINGERPRINT = """
            select question, answer, 1 - (embedding <=> ?::vector) as similarity
            from claw4j_cache.semantic
            where expires_at > now()
            order by embedding <=> ?::vector
            limit 1
            """;

    private static final String DELETE_EXPIRED =
            "delete from claw4j_cache.semantic where expires_at <= now()";

    /** 连续空白（含全角空格）折成一个空格。 */
    private static final Pattern NORMALIZE = Pattern.compile("[\\s\\u3000]+");

    private final JdbcTemplate jdbc;

    private final long timeToLiveSeconds;

    public PgVectorSemanticCache(JdbcTemplate jdbc, CacheProperties properties) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.timeToLiveSeconds = properties.timeToLive().toSeconds();
    }

    @Override
    public Optional<Candidate> nearest(String fingerprint, String question, float[] embedding) {
        String vector = toVectorLiteral(embedding);
        List<Candidate> found = jdbc.query(NEAREST,
                (rs, rowNum) -> new Candidate(
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getDouble("similarity")),
                vector, fingerprint, vector);
        return found.stream().findFirst();
    }

    @Override
    public Optional<Candidate> nearestAnyFingerprint(float[] embedding) {
        String vector = toVectorLiteral(embedding);
        return jdbc.query(NEAREST_ANY_FINGERPRINT,
                        (rs, rowNum) -> new Candidate(
                                rs.getString("question"),
                                rs.getString("answer"),
                                rs.getDouble("similarity")),
                        vector, vector)
                .stream()
                .findFirst();
    }

    @Override
    public void store(String fingerprint, String question, float[] embedding, String answer) {
        jdbc.update(STORE, fingerprint, question, questionKey(question),
                toVectorLiteral(embedding), answer, timeToLiveSeconds);
    }

    @Override
    public int deleteExpired() {
        int deleted = jdbc.update(DELETE_EXPIRED);
        if (deleted > 0) {
            log.info("清理了 {} 条过期语义缓存", deleted);
        }
        return deleted;
    }

    /**
     * 归一化后再哈希：多一个空格、换行、大小写都算同一个问题。
     *
     * <p>用 SHA-256 而不是 {@code hashCode()}——后者是 32 位且可碰撞，
     * 两个不同问题撞上就是"命中了一条答非所问的缓存"，而唯一索引会把它们当成同一行覆盖掉。
     */
    static String questionKey(String question) {
        String normalized = NORMALIZE.matcher(question == null ? "" : question).replaceAll(" ").trim();
        return sha256(normalized);
    }

    /**
     * 指纹的计算与判定的口径在 {@link CacheAdvisor} 里定，这里只要求"它是个短字符串"。
     *
     * <p>实现挪到了 {@link PermissionFingerprint}：权限指纹（缓存用）与会话 id（记忆用）
     * 必须是同一套口径，两份 SHA-256 各自演化迟早会出现"同一个人两处不是一个人"。
     */
    static String sha256(String value) {
        return PermissionFingerprint.sha256(value);
    }

    /**
     * 拼成 pgvector 的字面量 {@code [0.1,0.2,...]}。
     *
     * <p>维度写死在表的 {@code vector(1024)} 上，传短了 Postgres 会直接报错——
     * 这正是想要的行为：静默补零或截断会让两个完全不同的问题看起来很相似。
     */
    static String toVectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder(embedding.length * 12 + 2).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(embedding[i]);
        }
        return literal.append(']').toString();
    }
}
