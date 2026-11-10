package io.github.claw4j.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全文那一路：子串匹配，实现框架的 {@link DocumentRetriever}。
 *
 * <p>不用分词，也不用 {@code similarity()}。在 {@code pgvector/pgvector:pg16} 上实测：
 *
 * <pre>
 * similarity('退货政策', '华东区…7 天内可无理由退货，这是本区的退货政策。')  → 0
 * word_similarity(同上)                                                  → 0
 * 同上 % '退货政策'                                                       → false
 * 同上 ilike '%退货政策%'                                                 → true
 * </pre>
 *
 * <p>前两个都是"交集 trigram ÷ 并集 trigram"，块越长分母越大；{@code word_similarity} 还依赖词
 * 边界，中文没有空格。能用的只有子串匹配，这也正是 OpenClaw 的 FTS5 trigram 的形态
 * （{@code packages/memory-host-sdk/src/host/memory-schema.ts:441}）。
 *
 * <p>整句匹配不够，所以还抽字面锚点：用户问「SKU-2024 还剩多少」时，文档里没有这七个连续的字，
 * 整句 {@code ilike} 一条都查不到。除整句外，把纯 ASCII 的连续片段（{@code SKU-2024}、
 * {@code A-07}、{@code MB-3}）单独当锚点查。
 *
 * <p>中文不切分：切中文要自己定分词规则，那是自研检索。中文那半靠向量，这一路只负责编号、型号、
 * 代码这类向量认不出的东西。
 *
 * <p>通配符必须转义：{@code ILIKE} 的 {@code %} 与 {@code _} 是通配符。用户问「折扣率 5% 怎么算」
 * 时，不转义就变成 {@code ilike '%5%%'}，匹配所有含 5 的块，而且不报错。
 */
public class KeywordDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordDocumentRetriever.class);

    /**
     * 字面锚点：以字母或数字开头、长度至少 3 的连续 ASCII 片段。
     *
     * <p>下限 3 是为了放过 {@code a}、{@code 1} 这类噪声。{@code P1} 只有 2 个字符，会被漏掉，
     * 但放到 2 也会命中所有含 {@code p1} 的块，噪声大于收益——那种级别靠整句匹配或向量。
     *
     * <p>允许的字符是字母、数字、下划线、点、连字符：覆盖 {@code SKU-2024}、{@code A-07}、
     * {@code v1.2}、{@code mb_3}。
     */
    private static final Pattern ASCII_ANCHOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,}");

    /** 一条查询最多带几个锚点。再多就是整句被切碎了，命中率上升而精度下降。 */
    private static final int MAX_ANCHORS = 4;

    private final JdbcTemplate jdbc;

    private final String sourceKind;

    private final int candidates;

    /**
     * @param jdbc       JDBC 句柄
     * @param sourceKind 只查这个来源的块
     * @param candidates 候选上限，交给 SQL 的 {@code limit}
     */
    public KeywordDocumentRetriever(JdbcTemplate jdbc, String sourceKind, int candidates) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.candidates = candidates;
    }

    /**
     * 检索。异常不在这里吞：这一路挂了要不要影响整体，是混合检索那层的判断
     * （一路挂了还有另一路，两路都挂才抛）。
     *
     * @param query 本次检索
     * @return 命中块，分是命中次数；查询为空时是空列表
     */
    @Override
    public List<Document> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        String raw = query.text();
        List<String> terms = searchTerms(raw);
        List<Object> args = new ArrayList<>(terms.size() * 3 + 2);
        // 参数顺序 = SQL 文本里 ? 出现的顺序：先 select 里那组命中次数，再 where，最后 limit。
        for (String term : terms) {
            args.add(term);
            args.add(term);
        }
        args.add(sourceKind);
        for (String term : terms) {
            args.add("%" + escapeLike(term) + "%");
        }
        args.add(candidates);

        List<Document> found = jdbc.query(sqlFor(terms.size()), MAPPER, args.toArray());
        if (log.isDebugEnabled()) {
            log.debug("全文那一路「{}」（锚点 {}）：{} 条（候选上限 {}）",
                    raw, terms, found.size(), candidates);
        }
        return found;
    }

    /**
     * 这条查询要查哪些子串：整句加纯 ASCII 锚点，去重，保序。
     *
     * <p>整句排第一：短查询（「退货政策」）只有它，而它的命中次数也是主要的排序依据。
     *
     * @param query 查询原文，不能为 null
     * @return 子串列表，第一个是整句
     */
    static List<String> searchTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String trimmed = query.trim();
        if (!trimmed.isEmpty()) {
            terms.add(trimmed);
        }
        Matcher matcher = ASCII_ANCHOR.matcher(trimmed);
        while (matcher.find() && terms.size() < MAX_ANCHORS + 1) {
            terms.add(matcher.group());
        }
        return List.copyOf(terms);
    }

    /**
     * 按 terms 个数拼 SQL。
     *
     * <p>命中次数求和：整句命中一次与锚点命中一次都算一分，这是"这段提到了多少"的粗估。分母用
     * {@code length(term)} 是为了把一次命中归一成 1 分——不除的话，长词命中一次会等于短词命中
     * 三次，那是拿词长当相关度。
     *
     * <p>命中次数既是排序依据，也是 {@link Document#getScore()}，融合那一步要按它加权（见
     * {@link WeightedScoreJoiner}）。
     *
     * <p>{@code lower()} 两侧都套：{@code ilike} 不区分大小写，但 {@code replace} 区分——不统一
     * 会把 {@code SKU-2024} 与 {@code sku-2024} 算成两种命中。
     *
     * @param terms 子串个数
     * @return 参数按出现顺序留给调用方填的 SQL
     */
    private static String sqlFor(int terms) {
        StringBuilder where = new StringBuilder();
        StringBuilder hits = new StringBuilder();
        for (int i = 0; i < terms; i++) {
            where.append(i == 0 ? "" : " or ").append("content ilike ? escape '\\'");
            hits.append(i == 0 ? "" : " + ")
                    .append("(length(content) - length(replace(lower(content), lower(?), ''))) / length(?)");
        }
        return "select id, content, metadata->>'path' as path, metadata->>'source' as source,"
                + " metadata->>'index' as ordinal, metadata->>'content_hash' as hash,"
                + " (" + hits + ") as hits"
                + " from claw4j_rag.vector_store"
                + " where metadata->>'source' = ? and (" + where + ")"
                // 按输出列名排序（Postgres 允许），这样命中表达式的 ? 只出现一次。
                + " order by hits desc, (metadata->>'index')::int"
                + " limit ?";
    }

    /**
     * 把反斜杠、{@code %}、{@code _} 转成字面量。
     *
     * <p>先转反斜杠：反过来会把新加的转义符再转一次，{@code \%} 变成 {@code \\%}，
     * 在 {@code escape '\'} 下会被当成"一个反斜杠加一个通配符"。
     *
     * @param text 子串原文
     * @return 可直接放进 {@code ilike} 模式串的转义结果
     */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 列在 SQL 里就取成文本，不解析 {@code metadata} 这个 json 列。
     *
     * <p>解析 JSON 要处理 {@code PGobject}、类型转换与缺键三种情况，而这里只需要四个键。
     * {@code ->>'key'} 在库里就做完，取回来直接是字符串。
     *
     * <p>分数是命中次数（{@code hits} 那一列）：融合要按它加权，归一不在这里做，
     * 归一属于融合（见 {@link WeightedScoreJoiner}）。
     */
    private static final RowMapper<Document> MAPPER = (rs, rowNum) -> Document.builder()
            .id(rs.getString("id"))
            .text(rs.getString("content"))
            .metadata(RagMetadata.of(rs.getString("path"), rs.getString("source"),
                    rs.getString("ordinal"), rs.getString("hash")))
            .score(rs.getDouble("hits"))
            .build();
}
