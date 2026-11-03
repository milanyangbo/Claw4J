package io.github.claw4j.agent.rag;

import io.github.claw4j.text.ChineseText;
import io.github.claw4j.text.RankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/**
 * 两路检索 + RRF 融合，落在 pgvector 上。
 *
 * <p><b>两路各自的价值</b>：向量那一路认的是"意思像"，关键词那一路认的是"字面有"。
 * 只有向量的话，问 {@code SKU-2024} 会把语义相近但编号不同的段落排上来；只有关键词的话，
 * "上周的退货规则"与"七天无理由"永远对不上。融合之后，任一路排得足够前的都能浮上来。
 *
 * <p><b>为什么融合在 Java 而不在 SQL</b>：要看到两路的完整排名才能算 RRF，而那是两个查询。
 * 放在应用侧的另一个好处是这段规则可以被单测（{@code RankFusion} 那几条），
 * 写在 SQL 里就只能靠真库观察"这一次的结果"，测不到规则本身。
 *
 * <p><b>空查询不交给 SQL</b>：{@code to_tsquery('simple', '')} 得到空 tsquery，
 * 而它 {@code @@} 任何文档都是 {@code false}（实测）。也就是说交出去不会报错，
 * 只是安静地一条都不命中。这里直接跳过那一整路——省一次查询，也免得"为什么这条 SQL
 * 永远返回空"变成下次要查的问题。
 */
public class PgVectorKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeBase.class);

    /**
     * 向量通道。
     *
     * <p>用 CTE 先把分数算出来，再在同一个表达式上过滤：重复写 {@code 1 - (embedding <=> ?)}
     * 三遍（select、where、order by）意味着三个 {@code ?}，传参时少传一个就是运行时错误，
     * 而那种错误只在第一次提问时出现。
     *
     * <p>走全表精确扫描（没有向量索引，理由见迁移文件末尾）：知识库更在意召回，
     * 近似召回漏掉的那一条不会以任何方式被看见。
     */
    private static final String VECTOR_CHANNEL = """
            with scored as (
                select d.source, c.ordinal, c.text,
                       1 - (c.embedding <=> ?::vector) as score
                from claw4j_rag.chunk c
                join claw4j_rag.document d on d.id = c.document_id
            )
            select source, ordinal, text, score
            from scored
            where score >= ?
            order by score desc
            limit ?
            """;

    /**
     * 关键词通道。
     *
     * <p>{@code to_tsquery('simple', ?)} 的入参是 {@link ChineseText#tsQuery} 切好的
     * bigram 串（用 {@code &} 连接）。{@code ts_rank} 对它给出 0~0.3 量级的分数——
     * 与向量那一路的余弦不是同一个量纲，所以两者不直接相加，交给 RRF 按排名算。
     */
    private static final String KEYWORD_CHANNEL = """
            select d.source, c.ordinal, c.text,
                   ts_rank(c.tsv, to_tsquery('simple', ?)) as score
            from claw4j_rag.chunk c
            join claw4j_rag.document d on d.id = c.document_id
            where c.tsv @@ to_tsquery('simple', ?)
            order by score desc
            limit ?
            """;

    private static final String COUNT = "select count(*) from claw4j_rag.chunk";

    private final JdbcTemplate jdbc;

    private final EmbeddingModel embeddings;

    private final RagProperties properties;

    public PgVectorKnowledgeBase(JdbcTemplate jdbc, EmbeddingModel embeddings, RagProperties properties) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public List<Hit> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int topK = properties.topK();
        List<Hit> semantic = semanticChannel(query, topK);

        String expression = ChineseText.tsQuery(query);
        // 空表达式说明这个问题里没有可检索的内容（全是标点），关键词那一路就没有意义。
        List<Hit> keyword = expression.isEmpty() ? List.of() : keywordChannel(expression, topK);

        List<Hit> fused = RankFusion.fuse(List.of(semantic, keyword),
                hit -> hit.source() + "#" + hit.ordinal());
        if (log.isDebugEnabled()) {
            log.debug("知识库检索「{}」：向量 {} 条、关键词 {} 条（表达式 {}），融合后 {} 条",
                    query, semantic.size(), keyword.size(), expression, fused.size());
        }
        return fused.stream().limit(topK).toList();
    }

    @Override
    public int chunkCount() {
        Integer count = jdbc.queryForObject(COUNT, Integer.class);
        return count == null ? 0 : count;
    }

    private List<Hit> semanticChannel(String query, int topK) {
        float[] embedding = embed(query);
        if (embedding == null) {
            return List.of();
        }
        return jdbc.query(VECTOR_CHANNEL,
                (rs, rowNum) -> new Hit(rs.getString("source"), rs.getInt("ordinal"),
                        rs.getString("text"), rs.getDouble("score")),
                vectorLiteral(embedding), properties.minSimilarity(), topK);
    }

    private List<Hit> keywordChannel(String expression, int topK) {
        return jdbc.query(KEYWORD_CHANNEL,
                (rs, rowNum) -> new Hit(rs.getString("source"), rs.getInt("ordinal"),
                        rs.getString("text"), rs.getDouble("score")),
                expression, expression, topK);
    }

    /**
     * 算向量。**失败当成"这一路没有结果"**，不抛：向量服务抖一下不该让一次提问失败，
     * 关键词那一路还在，融合照样能给出东西。与缓存那边同一个容错方向。
     */
    private float[] embed(String query) {
        try {
            return embeddings.embed(query);
        }
        catch (RuntimeException failure) {
            log.warn("算不出问题的向量，这次只用关键词那一路：{}", failure.toString());
            return null;
        }
    }

    /**
     * 拼成 pgvector 的字面量 {@code [0.1,0.2,...]}。
     *
     * <p>与 {@code PgVectorSemanticCache} 里那份同形，刻意各写一份：那一份是包内可见的，
     * 为了共用要把它改成 public，于是两个互不相干的能力（缓存、检索）在代码上互相引用。
     * 代价是"维度检查"有两处——两处都必须与 {@code vector(1024)} 对齐，
     * 而维度不符时 Postgres 会直接报错，不会静默截断，所以它不会安静地错。
     */
    private static String vectorLiteral(float[] embedding) {
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
