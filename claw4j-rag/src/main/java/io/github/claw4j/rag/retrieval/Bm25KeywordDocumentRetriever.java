package io.github.claw4j.rag.retrieval;

import io.github.claw4j.rag.config.RagProperties;
import io.github.claw4j.rag.store.RagMetadata;
import io.github.claw4j.rag.store.VectorStoreNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Objects;

/**
 * 全文那一路的另一种实现：BM25（ParadeDB 的 {@code pg_search} + jieba 分词）。
 *
 * <p>与 {@link KeywordDocumentRetriever} 的差别只有一句话，但这句话决定了它能走多远：
 * <b>它不猜"什么算词"</b>。分词、IDF、长度归一都在引擎里，于是
 * {@code A2} 这种两个字符的型号、{@code AB-12} 这种两个字母带横线的编号、中文型号，
 * 都不需要各自的形状规则——而那条路每多一种写法就要多一条正则。
 *
 * <p>分数是 BM25 分数，无上界（实测 0.18~1.21）。融合层
 * （{@link WeightedScoreJoiner}）按本次最大值归一到 1，所以这里不需要也不应该做归一：
 * 分数是相对量，把一次检索的最大值改成 1 不会改变排序，但会让"两次检索的分差"失去意义。
 *
 * <p><b>索引是硬依赖，不是性能优化。</b>没有 bm25 索引时查询直接报错
 * （{@code does not contain a USING paradedb index}），不像 trigram 那样只是退化成全表扫描。
 * 所以 {@link #ensureIndex} 失败必须让启动失败——把"库不对"留到第一次提问，
 * 症状会变成"检索一直失败"，而人很难从那条报错里看出根因是镜像。
 *
 * <p><b>它要求换镜像</b>（{@code paradedb/paradedb:pg16}）。这是部署前提，不是依赖版本：
 * 装不上扩展的库上，这个引擎一行都跑不了。
 */
public class Bm25KeywordDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(Bm25KeywordDocumentRetriever.class);

    /**
     * 一条查询。
     *
     * <p>{@code pdb.match(?)} 能直接吃参数，不用把用户问题拼进 SQL——省掉手写 Lucene 语法转义
     * 那一层（{@code + - & | ! ( ) { } [ ] ^ " ~ * ? : \} 每个都要处理，漏一个就是查询解析错误）。
     *
     * <p>{@code where} 里的 {@code metadata->>'source'} 与 substring 那一路同口径：只查知识库的块，
     * 不把别的来源一起捞进来。
     *
     * <p>{@code hits} 取的是 {@code pdb.score(id)}，不是自己算的命中数。排序用别名，
     * 让分数表达式只出现一次；同分时按块号，保证同一批数据每次返回的顺序一致
     * （否则"这次召回的第 7 条"与"上次的第 7 条"可能不是同一块，评测会莫名抖动）。
     */
    private static final String SQL = "select id, content, metadata->>'path' as path,"
            + " metadata->>'source' as source, metadata->>'index' as ordinal,"
            + " metadata->>'content_hash' as hash, metadata->>'section' as section,"
            + " pdb.score(id) as hits"
            + " from " + VectorStoreNames.table()
            + " where metadata->>'source' = ? and content @@@ pdb.match(?)"
            + " order by hits desc, (metadata->>'index')::int"
            + " limit ?";

    private final JdbcTemplate jdbc;

    private final String sourceKind;

    private final int candidates;

    /**
     * @param jdbc       JDBC 句柄
     * @param sourceKind 只查这个来源的块
     * @param candidates 候选上限，交给 SQL 的 {@code limit}
     */
    public Bm25KeywordDocumentRetriever(JdbcTemplate jdbc, String sourceKind, int candidates) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.candidates = candidates;
    }

    /**
     * 检索。异常不在这里吞：这一路挂了要不要影响整体，是混合检索那层的判断。
     *
     * @param query 本次检索
     * @return 命中块，分是 BM25 分数；查询为空或没有可搜的字时是空列表
     */
    @Override
    public List<Document> retrieve(Query query) {
        if (query == null) {
            return List.of();
        }
        String raw = query.text() == null ? "" : query.text().trim();
        // 纯标点交给引擎会得到查询解析错误：那是一次真实的"没有结果"，却被记成故障。
        // 在 Java 侧判掉，比在 SQL 外面 try/catch 更容易解释，也不会顺手吞掉真正的数据库错误。
        if (!hasSearchable(raw)) {
            return List.of();
        }
        List<Document> found = jdbc.query(SQL, MAPPER, sourceKind, raw, candidates);
        if (log.isDebugEnabled()) {
            log.debug("全文那一路（bm25）「{}」：{} 条（候选上限 {}）", raw, found.size(), candidates);
        }
        return found;
    }

    /**
     * 这条查询里有没有值得交给引擎的东西：至少一个字母或数字（汉字算字母）。
     *
     * <p>不要求更多。长度、分词、停用词都是引擎的事——在这里再判断"够不够长"，
     * 等于把 substring 那一路的形状规则搬回来，那正是换引擎要摆脱的东西。
     *
     * @param text 查询原文，可以为 null
     * @return 有可搜内容时为 true
     */
    static boolean hasSearchable(String text) {
        return text != null
                && text.codePoints().anyMatch(codePoint -> Character.isLetterOrDigit(codePoint));
    }

    /**
     * 建 bm25 索引，失败即启动失败。
     *
     * <p>{@code if not exists} 让它幂等：每次启动都执行一次，第二次是空操作。
     *
     * <p>先查 {@code pg_available_extensions} 再建，是为了把"库不对"与"建索引语法不对"分开。
     * 两种失败都要启动失败，但它们的修法完全不同——前者换镜像，后者是版本差异。
     * 合成一句"建索引失败"，运维只能看到一条 Postgres 英文报错。
     *
     * <p>失败消息里必须写两条出路：这个错是部署级的，看到它的人手上一定有"改配置"这个选项，
     * 而"换镜像"可能要等下一次发版。
     *
     * @param jdbc JDBC 句柄
     * @throws IllegalStateException 当前库装不了 {@code pg_search}，或索引建不出来时
     */
    public static void ensureIndex(JdbcTemplate jdbc) {
        Boolean available = jdbc.queryForObject(
                "select count(*) > 0 from pg_available_extensions where name = 'pg_search'", Boolean.class);
        if (!Boolean.TRUE.equals(available)) {
            throw notParadeDb("当前数据库里没有可用的 pg_search 扩展（这个库不是 ParadeDB 镜像）", null);
        }
        try {
            jdbc.execute("create extension if not exists pg_search");
            jdbc.execute("create index if not exists " + VectorStoreNames.BM25_INDEX
                    + " on " + VectorStoreNames.table()
                    + " using bm25 (id, (content::pdb.jieba)) with (key_field = 'id')");
        }
        catch (RuntimeException failure) {
            throw notParadeDb("在 " + VectorStoreNames.table() + " 上建 bm25 索引失败", failure);
        }
    }

    /**
     * 构造"引擎不可用"的启动错误。
     *
     * <p>消息里带两条出路，原因写在 {@link #ensureIndex} 上：这是部署级错误，
     * 读它的人需要的是"现在能做什么"，不是一条 Postgres 原文。
     *
     * @param what  具体哪里不对
     * @param cause 原始异常，没有时为 null
     * @return 启动失败用的异常
     */
    private static IllegalStateException notParadeDb(String what, RuntimeException cause) {
        String message = "claw4j.rag.keyword.engine=bm25，但" + what + "。两条出路："
                + "① 数据库换成 ParadeDB 镜像（paradedb/paradedb:pg16）；"
                + "② 或把 claw4j.rag.keyword.engine 设回 " + RagProperties.Keyword.ENGINE_SUBSTRING;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    /**
     * 与 {@link KeywordDocumentRetriever} 的 mapper 同口径：六个 metadata 键、分数取 {@code hits}。
     *
     * <p>没有共用一份实现，因为两个类要保持可以各自删掉——但这份该与那边一起改：
     * metadata 少读一个键的症状是"出处是空串"，不报错。
     */
    private static final RowMapper<Document> MAPPER = (rs, rowNum) -> Document.builder()
            .id(rs.getString("id"))
            .text(rs.getString("content"))
            .metadata(RagMetadata.of(rs.getString("path"), rs.getString("source"),
                    rs.getString("ordinal"), rs.getString("hash"), rs.getString("section")))
            .score(rs.getDouble("hits"))
            .build();
}
