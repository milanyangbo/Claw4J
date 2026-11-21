package io.github.claw4j.rag.postprocess;

import io.github.claw4j.rag.store.RagMetadata;
import io.github.claw4j.rag.store.VectorStoreNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 章节父块回填：命中的子块换成"同一文档同一章节的全部块"拼成的父块（small-to-big）。
 *
 * <p>要解决的是"召回要小、生成要全"这对矛盾：小块召回准，但命中一段 350 字的块时，
 * 适用范围、例外与办理入口都在兄弟块里，模型只看到那一段就会答得似是而非。RAGFlow 的
 * parent-child chunking 与 LlamaIndex 的 small-to-big 都是这个思路。
 *
 * <p>父块不另存一份：同一章节的块用一条 SQL 按块号拼起来即可（几百段的量级），
 * 另建一张表只会多一处会与 {@code vector_store} 不一致的地方。
 *
 * <p><b>两条硬约束</b>：
 *
 * <ol>
 *   <li>父块超过 {@code max-chars} 就不聚合，保留原子块——宁可不合并，也不把一整章几千字塞进
 *       提示词；那样上下文会被一章吃掉，其它命中全被挤掉；
 *   <li>只有"同一章节命中了至少两块"才聚合：单块命中时，父块只是把上下文变长，没有补上任何
 *       模型原本看不到的东西。
 * </ol>
 *
 * <p>这个步骤在重排<b>之后</b>：先按子块的相关性排序，再把最相关的那些换成父块。反过来的话，
 * 重排看到的是拼好的长文本，分数会被章节里无关的部分拉平。
 */
public class SectionExpander implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SectionExpander.class);

    /** 一个章节在库里的全部块，按块号升序。 */
    private static final String SQL = "select content, (metadata->>'index')::int as ordinal,"
            + " metadata->>'content_hash' as hash"
            + " from " + VectorStoreNames.table()
            + " where metadata->>'source' = ? and metadata->>'path' = ? and metadata->>'section' = ?"
            + " order by ordinal";

    /**
     * 库里一行的映射。用 {@link Document} 而不是私有 record：这里只需要正文、块号与哈希，
     * 而这三样 {@code RagMetadata} 已经表达得很清楚——再造一个只有三个字段的类型，
     * 测试里还得为它开一个构造入口。
     */
    private static final RowMapper<Document> MAPPER = (rs, rowNum) -> new Document(rs.getString("content"),
            RagMetadata.of(null, null, String.valueOf(rs.getInt("ordinal")), rs.getString("hash"), null));

    private final JdbcTemplate jdbc;

    private final String sourceKind;

    private final int maxChars;

    /**
     * @param jdbc       JDBC 句柄
     * @param sourceKind 只聚合这个来源的块
     * @param maxChars   父块字符上限，超过就不聚合
     */
    public SectionExpander(JdbcTemplate jdbc, String sourceKind, int maxChars) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.maxChars = maxChars;
    }

    /**
     * 把同章节的多条命中换成一条父块，其余原样保留。
     *
     * @param query     本次查询
     * @param documents 重排后的候选，按名次
     * @return 聚合后的候选；父块占据它这一组里最靠前的名次
     */
    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.size() < 2) {
            return documents;
        }
        Map<String, List<Document>> grouped = new LinkedHashMap<>();
        for (Document document : documents) {
            String key = keyOf(document);
            if (key != null) {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(document);
            }
        }
        Map<String, Document> parents = new HashMap<>();
        for (Map.Entry<String, List<Document>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            Document parent = parentOf(entry.getValue().getFirst());
            if (parent != null) {
                parents.put(entry.getKey(), parent);
            }
        }
        if (parents.isEmpty()) {
            return documents;
        }
        List<Document> expanded = new ArrayList<>(documents.size());
        Set<String> emitted = new HashSet<>();
        for (Document document : documents) {
            String key = keyOf(document);
            Document parent = key == null ? null : parents.get(key);
            if (parent == null) {
                expanded.add(document);
                continue;
            }
            // 一组只留一条父块，放在组内第一个出现的名次上；后面的成员被它替代。
            if (emitted.add(key)) {
                expanded.add(parent);
            }
        }
        log.debug("父块回填：{} 组聚合，候选 {} → {} 条", parents.size(), documents.size(), expanded.size());
        return List.copyOf(expanded);
    }

    /**
     * 拼一个章节的父块。返回 null 表示这一组不聚合：库里只有一块，或者拼出来超长。
     */
    private Document parentOf(Document first) {
        String path = text(first, RagMetadata.PATH);
        String section = text(first, RagMetadata.SECTION);
        List<Document> blocks = jdbc.query(SQL, MAPPER, sourceKind, path, section);
        if (blocks.size() < 2) {
            return null;
        }
        int chars = blocks.stream().mapToInt(block -> block.getText().length()).sum();
        if (chars > maxChars) {
            log.debug("{} › {} 拼起来 {} 字符，超过上限 {}，保留原子块",
                    path, section, chars, maxChars);
            return null;
        }
        StringBuilder content = new StringBuilder();
        StringBuilder covered = new StringBuilder();
        for (Document block : blocks) {
            if (covered.length() > 0) {
                covered.append(',');
            }
            covered.append(ordinalOf(block));
            content.append(bodyOf(block.getText())).append("\n\n");
        }
        // 前缀只留一份：每块都带一份【文档 › 章节】拼起来就是十行同样的标题。
        String prefix = prefixOf(blocks.getFirst().getText());
        Map<String, Object> metadata = new HashMap<>(RagMetadata.of(path, sourceKind,
                String.valueOf(ordinalOf(blocks.getFirst())), text(blocks.getFirst(), RagMetadata.HASH),
                section));
        // 覆盖了哪些子块：导出侧要按它把父块展开成子块 id，否则评测里"正确段没回来"是假的。
        metadata.put(RagMetadata.COVERED, covered.toString());
        String id = path + "#" + ordinalOf(blocks.getFirst()) + "-" + ordinalOf(blocks.getLast());
        return new Document(id, prefix + content.toString().strip(), metadata);
    }

    /** 分组键：文档 + 章节。任一个缺了就返回 null——那种块无法确定"它的父块是谁"。 */
    private static String keyOf(Document document) {
        String path = text(document, RagMetadata.PATH);
        String section = text(document, RagMetadata.SECTION);
        return path == null || section == null ? null : path + "\u0000" + section;
    }

    /** 块号。库里存成 int，取不到时按 0 处理——0 不会与任何真实块号冲突（从 1 起）。 */
    private static int ordinalOf(Document block) {
        Object index = block.getMetadata().get(RagMetadata.INDEX);
        return index instanceof Number number ? number.intValue() : 0;
    }

    private static String text(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /** 块文本的第一行如果是一整个 {@code 【…】}，就取它（含换行）；否则没有前缀。 */
    private static String prefixOf(String content) {
        if (content == null || !content.startsWith("【")) {
            return "";
        }
        int newline = content.indexOf('\n');
        if (newline > 0 && content.charAt(newline - 1) == '】') {
            return content.substring(0, newline + 1);
        }
        return "";
    }

    private static String bodyOf(String content) {
        String prefix = prefixOf(content);
        return prefix.isEmpty() ? content : content.substring(prefix.length());
    }

}
