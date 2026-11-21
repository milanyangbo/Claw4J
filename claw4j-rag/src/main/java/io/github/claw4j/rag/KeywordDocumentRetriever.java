package io.github.claw4j.rag;

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
 *   <li><b>整句</b>，只在不长于配置里的整句长度上限（默认 12 字）时用——短查询本身就是最好的词项，
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
     * 编译好的词项规则。
     *
     * <p>正则在这里编译，不等到第一次提问才暴露语法错——配错一个括号，表现会是"全文那一路
     * 突然一条都不命中"，而不是启动失败，很难归因。
     *
     * <p>锚点复用整句的权重是有意的：两者都是"字面精确"信号，只有 bigram 是覆盖度信号。
     * 拆成两个可配的权重看着更灵活，实际会让人调出"锚点比整句还重"这种无法解释的组合。
     */
    record Rules(Pattern anchor, Pattern shortModel, Pattern cjkRun, int maxAnchors, int maxTerms,
                 int wholeMaxChars, int wholeWeight, int bigramWeight) {

        static Rules of(RagProperties.Keyword.Substring config) {
            return new Rules(
                    compile(config.anchorPattern(), "anchor-pattern"),
                    compile(config.shortModelPattern(), "short-model-pattern"),
                    compile(config.cjkPattern(), "cjk-pattern"),
                    config.maxAnchors(), config.maxTerms(), config.wholeMaxChars(),
                    config.wholeWeight(), config.bigramWeight());
        }

        static Rules defaults() {
            return of(RagProperties.Keyword.Substring.defaults());
        }

        private static Pattern compile(String regex, String name) {
            try {
                return Pattern.compile(regex);
            } catch (RuntimeException broken) {
                throw new IllegalStateException(
                        "claw4j.rag.keyword." + name + " 不是合法正则：" + regex, broken);
            }
        }
    }

    private final JdbcTemplate jdbc;

    private final String sourceKind;

    private final int candidates;

    private final Rules rules;

    /**
     * @param jdbc       JDBC 句柄
     * @param sourceKind 只查这个来源的块
     * @param candidates 候选上限，交给 SQL 的 {@code limit}
     * @param substring  词项规则（正则、上限、权重），见 {@link RagProperties.Keyword.Substring}
     */
    public KeywordDocumentRetriever(JdbcTemplate jdbc, String sourceKind, int candidates,
                                    RagProperties.Keyword.Substring substring) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.candidates = candidates;
        this.rules = Rules.of(substring == null ? RagProperties.Keyword.Substring.defaults() : substring);
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
        List<Term> terms = terms(raw, rules);
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
     *   <li>整句（不超过配置里的整句长度上限）：权重 3，完整匹配是最强的信号；
     *   <li>ASCII 锚点：权重 3，{@code SKU-2024} 只有那份文档有；
     *   <li>中文 bigram：权重 1，它是覆盖度信号，单独命中一个不足以说明相关性。
     * </ol>
     *
     * <p>锚点排在 bigram 之前：bigram 会把词项上限用满，先切 bigram 的话，
     * 一个 20 字的提问会把型号挤出去——正是这一路最该命中的东西没查。
     *
     * @param query 查询原文，可以为 null
     * @return 词项列表；空查询时为空
     */
    static List<Term> terms(String query, Rules rules) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> weighted = new LinkedHashMap<>();
        if (trimmed.length() <= rules.wholeMaxChars()) {
            weighted.put(trimmed, rules.wholeWeight());
        }
        Matcher anchors = rules.anchor().matcher(trimmed);
        int accepted = 0;
        while (anchors.find() && accepted < rules.maxAnchors() && weighted.size() < rules.maxTerms()) {
            if (weighted.putIfAbsent(anchors.group(), rules.wholeWeight()) == null) {
                accepted++;
            }
        }
        // 短型号与长锚点共用名额：它们都是"字面精确"那一类，多收会挤掉中文 bigram。
        Matcher models = rules.shortModel().matcher(trimmed);
        while (models.find() && accepted < rules.maxAnchors() && weighted.size() < rules.maxTerms()) {
            if (weighted.putIfAbsent(models.group(), rules.wholeWeight()) == null) {
                accepted++;
            }
        }
        Matcher runs = rules.cjkRun().matcher(trimmed);
        while (runs.find() && weighted.size() < rules.maxTerms()) {
            String run = runs.group();
            // 两两切：长度 n 的串给 n-1 个 bigram，n==1 时一个也没有（单字命中全是噪声）。
            for (int i = 0; i + 2 <= run.length() && weighted.size() < rules.maxTerms(); i++) {
                weighted.putIfAbsent(run.substring(i, i + 2), rules.bigramWeight());
            }
        }
        List<Term> terms = new ArrayList<>(weighted.size());
        weighted.forEach((text, weight) -> terms.add(new Term(text, weight)));
        return List.copyOf(terms);
    }

    /**
     * 用默认规则切词项。
     *
     * <p>只给日志与单测用：线上那个实例带着部署方配的规则，不会走到这里。两个入口共用
     * 一份实现（这里只是传默认规则），不做第二套切分逻辑——两套一定会有一套先过期。
     */
    static List<String> searchTerms(String query) {
        return terms(query, Rules.defaults()).stream().map(Term::text).toList();
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
