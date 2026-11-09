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
 * 全文那一路：**子串匹配**，实现框架的 {@link DocumentRetriever}。
 *
 * <p><b>为什么不是分词、也不是 `similarity()`</b>：在 `pgvector/pgvector:pg16` 上实测
 * （2026-11-09）：
 *
 * <pre>
 * similarity('退货政策', '华东区…7 天内可无理由退货，这是本区的退货政策。')  → 0
 * word_similarity(同上)                                                  → 0
 * 同上 % '退货政策'                                                       → false
 * 同上 ilike '%退货政策%'                                                 → true
 * </pre>
 *
 * 前两个都是"交集 trigram ÷ 并集 trigram"，块越长分母越大；`word_similarity` 还依赖词边界，
 * 中文没有空格。能用的只有子串匹配——而这正是 OpenClaw 的 FTS5 trigram 的形态（它也是子串）。
 *
 * <p><b>整句匹配不够，所以抽"字面锚点"</b>：用户问「SKU-2024 还剩多少」时，文档里没有这七个
 * 连续的字——整句 `ilike` 一条都查不到，而那正是这一路存在的理由。所以除了整句，还把
 * **纯 ASCII 的连续片段**（`SKU-2024`、`A-07`、`MB-3`、`P1`）单独当锚点查。
 *
 * <p><b>中文不做切分</b>：切中文就要自己定分词规则，那是自研检索，不是"照 OpenClaw 的形态"。
 * 中文那半靠向量；这一路只负责"编号、型号、代码"这类向量认不出的东西。
 *
 * <p><b>通配符必须转义</b>：`ILIKE` 的 `%` 与 `_` 是通配符。用户问「折扣率 5% 怎么算」时，
 * 不转义就变成 `ilike '%5%%'`——匹配所有含 5 的块，而且不报错。
 */
public class KeywordDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordDocumentRetriever.class);

    /**
     * 字面锚点：以字母或数字开头、长度至少 3 的连续 ASCII 片段。
     *
     * <p>长度 3 是为了放过 `a`、`1` 这类噪声，同时保住 `P1`？——`P1` 只有 2 个字符，会被漏掉。
     * 但它同时也会命中所有含 `p1` 的块，噪声大于收益。所以下限 3，`P1` 那种级别靠整句匹配
     * 或向量。这条权衡写在这里，免得下次有人"顺手改成 2"。
     *
     * <p>允许的字符是字母、数字、`_`、`.`、`-`：覆盖 `SKU-2024`、`A-07`、`v1.2`、`mb_3`。
     */
    private static final Pattern ASCII_ANCHOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,}");

    /** 一条查询最多带几个锚点。再多就是整句被切碎了，命中率上升而精度下降。 */
    private static final int MAX_ANCHORS = 4;

    private final JdbcTemplate jdbc;

    private final String sourceKind;

    private final int candidates;

    public KeywordDocumentRetriever(JdbcTemplate jdbc, String sourceKind, int candidates) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.candidates = candidates;
    }

    /**
     * 检索。异常**不在这里吞**：这一路挂了要不要影响整体，是混合检索那层的判断
     * （一路挂了还有另一路；两路都挂才该抛）。
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
     * 这条查询要查哪些子串：整句 + 纯 ASCII 锚点，去重，保序。
     *
     * <p>整句排第一：短查询（「退货政策」）只有它，而它的命中次数也是主要的排序依据。
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
     * <p><b>命中次数求和</b>：整句命中一次与锚点命中一次都算一分，这正是"这段提到了多少"的
     * 粗估。分母用 `length(term)` 是为了把"一次命中"归一成 1 分——不除的话，长词命中一次会
     * 等于短词命中三次，那是拿词长当相关度。**所以长短词每次命中的权重相同**。
     *
     * <p>**命中次数既是排序依据、也是 `Document#getScore()`**：融合那一步要按它对两路做加权
     * （{@link WeightedScoreJoiner} 会把它按"本次查询的最强命中"归一）。之前这里只排序不给分，
     * 于是融合只能看名次——那正是 RRF 的由来；现在有了分，就照 OpenClaw 的加权走。
     *
     * <p>`lower()` 两侧都套：`ilike` 不区分大小写，但 `replace` 区分——不统一会把
     * `SKU-2024` 与 `sku-2024` 算成两种命中。
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
     * 把 `\` `%` `_` 转成字面量。
     *
     * <p>顺序是先 `\`：反过来会把新加进去的转义符再转一次，`\%` 变成 `\\%`——
     * 那在 `escape '\'` 下会被当成"一个反斜杠 + 通配符"。
     */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 列在 SQL 里就取成文本，不解析 `metadata` 这个 json 列。
     *
     * <p>解析 JSON 要处理 `PGobject`、类型转换与缺键三种情况，而这里只需要四个键。
     * `->>'key'` 在库里就做完，取回来直接是字符串。
     *
     * <p><b>分数是命中次数</b>（`hits` 那一列）：融合要按它对两路加权。不在这里归一，
     * 归一属于融合（"按本次查询的最强命中"度量，见 {@link WeightedScoreJoiner}）。
     */
    private static final RowMapper<Document> MAPPER = (rs, rowNum) -> Document.builder()
            .id(rs.getString("id"))
            .text(rs.getString("content"))
            .metadata(RagMetadata.of(rs.getString("path"), rs.getString("source"),
                    rs.getString("ordinal"), rs.getString("hash")))
            .score(rs.getDouble("hits"))
            .build();
}
