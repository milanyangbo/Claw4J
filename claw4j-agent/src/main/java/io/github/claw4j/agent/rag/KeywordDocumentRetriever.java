package io.github.claw4j.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全文那一路：词项子串匹配，实现框架的 {@link DocumentRetriever}。
 *
 * <p>不用分词器，也不用 {@code similarity()}。在 {@code pgvector/pgvector:pg16} 上实测：
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
 * <p><b>为什么还要拆词项</b>：只拿整句去 {@code ilike}，对中文长问题几乎必然零命中——
 * 用户问「为什么隔壁那家店能退我不能退」，文档里不会有这十七个字连在一起。那样
 * {@code hybrid.enabled=true} 名义上是混合检索，实际只有向量一条腿在跑。
 *
 * <p>所以一条查询拆成三类词项（{@link #searchTerms}）：
 *
 * <ol>
 *   <li><b>整句</b>，只在不长于 {@value #WHOLE_QUERY_MAX_CHARS} 个字时用——短查询本身就是最好的词项，
 *       长句加进去只是一条必然不命中的 {@code or}；
 *   <li><b>ASCII 锚点</b>：{@code SKU-2024}、{@code A-07}、{@code v1.2} 这类向量认不出的编号；
 *   <li><b>中文 bigram</b>：连续汉字串内部两两切分（{@code 退货政策} → {@code 退货 / 货政 / 政策}）。
 *       这是 pg_bigm 的思路，用现有 {@code ilike} 就能做，不需要装扩展、也不需要自研分词。
 * </ol>
 *
 * <p>打分是<b>命中的不同词项数</b>，不是某个词出现了多少次：一个高频词出现十次会压过命中五个
 * 不同词项的块，那是拿词频当相关度。
 *
 * <p><b>它不是 BM25</b>，别在文档里写成 BM25：没有 IDF（{@code 的} 与 {@code 押金} 同权）、
 * 没有长度归一、也没有倒排索引。真正的 BM25 要 {@code pg_bigm} / {@code zhparser} /
 * ParadeDB 的 {@code pg_search}，三者都要换数据库镜像。
 *
 * <p><b>索引退化的已知代价</b>：{@code pg_trgm} 的 GIN 索引只对 ≥3 字符的模式有效，2 字 bigram
 * 会走全表扫描。当前库是几百段，毫秒级；段数上万时必须换 {@code pg_bigm}，那条记在
 * {@code docs/design/knowledge-retrieval.md}。
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

    /**
     * 短型号：一个字母加一到两位数字，两侧不接字母数字（{@code A1}、{@code A2}、{@code B3}、
     * {@code K4}）。
     *
     * <p>{@link #ASCII_ANCHOR} 要求 ≥3 字符，把这类型号全漏了。而产品型号恰好是两位：
     * 「A2 买一年延保要多少钱」里唯一能匹配价格表那块的字面就是 {@code A2}——漏了它，
     * 价格表第 1 块（整机表格）永远进不了候选，四条多跳题全卡在这里。
     *
     * <p>不收 1 位数字（{@code A1} 之外的 {@code P1} 是收的，但 {@code v2} 这种版本号也收）：
     * 再短就是噪声。两侧不接字母数字是为了不把 {@code SKU2024A2} 切出一个假的 {@code 4A}。
     */
    private static final Pattern SHORT_MODEL = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]\\d{1,2}(?![A-Za-z0-9])");

    /** 一条查询最多带几个锚点。再多就是整句被切碎了，命中率上升而精度下降。 */
    private static final int MAX_ANCHORS = 4;

    /**
     * 连续汉字（含日文假名）串。bigram 只在这些串内部切：跨标点、跨空格拼出来的两字组合
     * （{@code 退，我}）在文档里不会出现。
     */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff]+");

    /**
     * 一条查询最多几个词项。SQL 里每个词项是一个 {@code or} 加一个 {@code case}，两个都会随词项数
     * 线性增长；一条长提问能切出几十个 bigram，不封顶就变成一条上千字符的语句。
     *
     * <p>12 的依据是"够覆盖一句 13 字左右的中文提问的实义部分"：再长的提问里，前 12 个词项
     * （主语 + 关键名词 + 动作）通常已经足够把相关块区分开。
     */
    private static final int MAX_TERMS = 12;

    /**
     * 整句只在不超过这么多字时作为一个词项。
     *
     * <p>12 是经验值：短查询（{@code 退货政策}、{@code 5%}）整句就是最好的词项，而 20 字的提问
     * 整句几乎不可能在文档里原样出现，加进去只是一条必然不命中的 {@code or}。
     */
    private static final int WHOLE_QUERY_MAX_CHARS = 12;

    /** 整句与 ASCII 锚点的权重：命中一次就足以把它和"只沾了个虚词"的块分开。 */
    private static final int WHOLE_WEIGHT = 3;

    /** 中文 bigram 的权重：它是覆盖度信号，单个 bigram 命中不足以说明相关。 */
    private static final int BIGRAM_WEIGHT = 1;

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
     * @return 命中块，分是命中的不同词项数；查询为空时是空列表
     */
    @Override
    public List<Document> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        String raw = query.text();
        List<Term> terms = terms(raw);
        // 每个词项在 SQL 里出现两次（select 的 case 与 where 的 ilike），**两处都要 pattern**：
        // select 那一处是 `content ilike ?`，给裸词项（不带 %）会让它退化成精确相等，
        // 于是 where 命中了、score 还是 0——融合按 score 加权，全文那一路等于白召回。
        // 容量 2n + 2（source 与 limit）。
        List<Object> args = new ArrayList<>(terms.size() * 2 + 2);
        // 参数顺序 = SQL 文本里 ? 出现的顺序：先 select 里那组词项命中，再 where，最后 limit。
        for (Term term : terms) {
            args.add("%" + escapeLike(term.text()) + "%");
        }
        args.add(sourceKind);
        for (Term term : terms) {
            args.add("%" + escapeLike(term.text()) + "%");
        }
        args.add(candidates);

        List<Document> found = jdbc.query(sqlFor(terms), MAPPER, args.toArray());
        if (log.isDebugEnabled()) {
            log.debug("全文那一路「{}」（锚点 {}）：{} 条（候选上限 {}）",
                    raw, terms, found.size(), candidates);
        }
        return found;
    }

    /**
     * 一个查询词项与它的权重。
     *
     * <p>为什么要权重：{@code SKU-2024 还剩多少} 切出 {@code SKU-2024} 与 {@code 还剩 / 剩多 / 多少}，
     * 不区分的话，"只含『多少』"的块与"含 SKU-2024"的块同分——1 个型号锚点被 1 个虚词 bigram
     * 抵掉，精确匹配的排序优势就没了。实测表现是"编号类查询把同类文档也捞到前面"。
     *
     * @param text   子串
     * @param weight 命中这一项得几分
     */
    record Term(String text, int weight) {
    }

    /**
     * 这条查询要查哪些词项，去重保序。
     *
     * <p>顺序与权重（{@link Term#weight}）：
     *
     * <ol>
     *   <li>整句（不超过 {@value #WHOLE_QUERY_MAX_CHARS} 字）：权重 3，完整匹配是最强的信号；
     *   <li>ASCII 锚点：权重 3，{@code SKU-2024} 只有那份文档有；
     *   <li>中文 bigram：权重 1，它是覆盖度信号，单独命中一个不足以说明相关性。
     * </ol>
     *
     * <p>锚点排在 bigram 之前：bigram 会把 {@link #MAX_TERMS} 用满，先切 bigram 的话，
     * 一个 20 字的提问会把型号挤出去——正是这一路最该命中的东西没查。
     *
     * @param query 查询原文，可以为 null
     * @return 词项列表；空查询时为空
     */
    static List<Term> terms(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> weighted = new LinkedHashMap<>();
        if (trimmed.length() <= WHOLE_QUERY_MAX_CHARS) {
            weighted.put(trimmed, WHOLE_WEIGHT);
        }
        Matcher anchors = ASCII_ANCHOR.matcher(trimmed);
        int accepted = 0;
        while (anchors.find() && accepted < MAX_ANCHORS && weighted.size() < MAX_TERMS) {
            if (weighted.putIfAbsent(anchors.group(), WHOLE_WEIGHT) == null) {
                accepted++;
            }
        }
        // 短型号与长锚点共用 4 个名额：它们都是"字面精确"那一类，多收会挤掉中文 bigram。
        Matcher models = SHORT_MODEL.matcher(trimmed);
        while (models.find() && accepted < MAX_ANCHORS && weighted.size() < MAX_TERMS) {
            if (weighted.putIfAbsent(models.group(), WHOLE_WEIGHT) == null) {
                accepted++;
            }
        }
        Matcher runs = CJK_RUN.matcher(trimmed);
        while (runs.find() && weighted.size() < MAX_TERMS) {
            String run = runs.group();
            // 两两切：长度 n 的串给 n-1 个 bigram，n==1 时一个也没有（单字命中全是噪声）。
            for (int i = 0; i + 2 <= run.length() && weighted.size() < MAX_TERMS; i++) {
                weighted.putIfAbsent(run.substring(i, i + 2), BIGRAM_WEIGHT);
            }
        }
        List<Term> terms = new ArrayList<>(weighted.size());
        weighted.forEach((text, weight) -> terms.add(new Term(text, weight)));
        return List.copyOf(terms);
    }

    /** 只有词项文本，给日志与单测用。 */
    static List<String> searchTerms(String query) {
        return terms(query).stream().map(Term::text).toList();
    }

    /**
     * 按 terms 个数拼 SQL。
     *
     * <p>分数是"命中的词项权重之和"：bigram 权重 1，整句与型号锚点权重 3。原来是
     * {@code (length - length(replace(...))) / length(?)} 的出现次数求和，那样一个高频词命中十次
     * （比如某份文档里反复出现"退货"）会等于命中十个不同词项——那是拿词频当相关度。
     *
     * <p>这个分既是排序依据，也是 {@link Document#getScore()}，融合那一步按它加权
     * （见 {@link WeightedScoreJoiner}，它自己会按本次最大值归一到 1）。
     *
     * <p>{@code lower()} 两侧都套：{@code ilike} 不区分大小写，但 {@code replace} 已经不用了，
     * 这里保留 {@code ilike} 一个判据即可；{@code escape '\'} 让通配符按字面量处理。
     *
     * @param terms 子串个数
     * @return 参数按出现顺序留给调用方填的 SQL
     */
    private static String sqlFor(List<Term> terms) {
        StringBuilder where = new StringBuilder();
        StringBuilder hits = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            where.append(i == 0 ? "" : " or ").append("content ilike ? escape '\\'");
            // 权重直接拼进 SQL：它是我们自己算出来的小整数，不是外部输入。作为参数传要多占一个
            // 占位符，而参数与 ? 的个数一对一这件事已经出过一次错（改 SQL 忘了改 args）。
            hits.append(i == 0 ? "" : " + ")
                    .append("(case when content ilike ? escape '\\' then ")
                    .append(terms.get(i).weight()).append(" else 0 end)");
        }
        return "select id, content, metadata->>'path' as path, metadata->>'source' as source,"
                + " metadata->>'index' as ordinal, metadata->>'content_hash' as hash,"
                + " metadata->>'section' as section,"
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
     * <p>分数是命中的不同词项数（{@code hits} 那一列）：融合要按它加权，归一不在这里做，
     * 归一属于融合（见 {@link WeightedScoreJoiner}）。
     */
    private static final RowMapper<Document> MAPPER = (rs, rowNum) -> Document.builder()
            .id(rs.getString("id"))
            .text(rs.getString("content"))
            .metadata(RagMetadata.of(rs.getString("path"), rs.getString("source"),
                    rs.getString("ordinal"), rs.getString("hash"), rs.getString("section")))
            .score(rs.getDouble("hits"))
            .build();
}
