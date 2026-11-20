package io.github.claw4j.agent.rag;

import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * 一块文档在 metadata 里的四个键，以及"出处"的唯一拼法。
 *
 * <p>键名与拼接只在这里：写的一方是索引器，读的一方是两路检索、重排、格式化输出，五个地方。
 * 各写一遍字符串的后果是"检索时取不到 path、出处是空串"，而那既不报错也不影响排序，
 * 只是回答里引用的出处看起来像没写。
 *
 * <p>不用 {@link Document#getId()} 记出处：id 是 {@code PgVectorStore} 建的 uuid，重建索引就换
 * 一批，而"这段来自哪份文档的第几块"必须是稳定的事实。
 */
public final class RagMetadata {

    /** 相对文档目录的路径，出处的第一段。 */
    public static final String PATH = "path";

    /** 来源类别。今天只有 {@code docs} 一处；写进 metadata 是为了将来多接一份目录时能过滤。 */
    public static final String SOURCE = "source";

    /** 文档内第几块，从 1 开始。出处的第二段。 */
    public static final String INDEX = "index";

    /** 内容哈希，与 {@code claw4j_rag.source} 那一列同源。 */
    public static final String HASH = "content_hash";

    /**
     * 章节链，例如 {@code 第二章 退货 › 2.3 运费承担}。块前缀里也有一份，这里再存一次是因为
     * 父子分块要按它聚合（{@code SectionExpander}）：从文本里反解前缀是"解析自己刚拼出来的
     * 字符串"，比多存一个键脆弱。
     */
    public static final String SECTION = "section";

    /**
     * 父块覆盖的子块号，逗号分隔（例如 {@code 3,4,5}）。只有
     * {@link SectionExpander} 拼出来的父块才有这个键。
     *
     * <p>存在的理由只有一个：导出侧要按它把父块展开成子块 id。不展开的话，gold 的
     * {@code reference} 是一个子块 id，而返回列表里只有父块的 id，评测会把"其实答上了"
     * 记成"没召回"——一个只在开父块时才出现的假跌。
     */
    public static final String COVERED = "covered";

    private RagMetadata() {
    }

    /**
     * 装一块的 metadata。
     *
     * <p>用 {@code HashMap} 而不是 {@code Map.of}：从库里取出来的键可能缺失（早期写入的块没有
     * {@code content_hash}），而 {@code Map.of} 遇到 null 直接 NPE——那会让"一条老数据"变成
     * "整次检索失败"。
     *
     * @param path    相对文档目录的路径，可以为 null
     * @param source  来源类别，可以为 null
     * @param ordinal 文档内块号，文本形态，可以为 null
     * @param hash    内容哈希，可以为 null
     * @param section 章节链，可以为 null
     * @return 只含非空键的 metadata
     */
    public static Map<String, Object> of(String path, String source, String ordinal, String hash,
                                        String section) {
        Map<String, Object> metadata = new HashMap<>(5);
        if (path != null) {
            metadata.put(PATH, path);
        }
        if (source != null) {
            metadata.put(SOURCE, source);
        }
        Integer index = ordinalOf(ordinal);
        if (index != null) {
            metadata.put(INDEX, index);
        }
        if (hash != null) {
            metadata.put(HASH, hash);
        }
        if (section != null && !section.isBlank()) {
            metadata.put(SECTION, section);
        }
        return Map.copyOf(metadata);
    }

    /**
     * 出处：{@code 文档路径 第 N 段}。取不到路径时返回空串，由调用方决定怎么处理。
     *
     * @param document 一块文档
     * @return 出处文本，没有路径时是空串
     */
    public static String citation(Document document) {
        Object path = document.getMetadata().get(PATH);
        if (path == null || path.toString().isBlank()) {
            return "";
        }
        Object index = document.getMetadata().get(INDEX);
        int ordinal = index instanceof Number number ? number.intValue() : 0;
        return ordinal > 0
                ? path + " 第 " + ordinal + " 段"
                : path.toString();
    }

    /** 索引器写进去的块号是 int；从 SQL 读回来的是文本。两处口径在这里统一。 */
    private static Integer ordinalOf(String ordinal) {
        if (ordinal == null || ordinal.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(ordinal);
        }
        catch (NumberFormatException notANumber) {
            // 块号不是数字只可能是有人手改过库；当作"没有块号"，出处退化成只写路径。
            return null;
        }
    }
}
