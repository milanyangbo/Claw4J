package io.github.claw4j.rag.store;

/**
 * 向量存储的表名、schema 与索引名。
 *
 * <p>这些名字是存储契约：迁移脚本与写入侧建，检索侧读，两边必须逐字一致。集中在这里，
 * 是为了让装配类只负责装配，不兼做常量宿主——否则检索器要读表名就得反向依赖装配类。
 *
 * <p>改名字要连同 {@code V4__rag.sql} 一起改。
 */
public final class VectorStoreNames {

    /** 向量表所在的 schema。 */
    public static final String SCHEMA = "claw4j_rag";

    /** 块与向量的主表，由框架建。 */
    public static final String TABLE = "vector_store";

    /** 索引元信息表（切块版本、嵌入模型等），由迁移脚本建。 */
    public static final String INDEX_META = "index_meta";

    /** 全文检索用的 trigram 索引，{@code substring} 引擎靠它。 */
    public static final String TRIGRAM_INDEX = "ix_rag_vector_store_content_trgm";

    /**
     * BM25 索引，只有 {@code bm25} 引擎才建。
     *
     * <p>与 trigram 那条分开命名：两者可以同时存在（不同引擎、不同索引），切换引擎时不必删旧的。
     */
    public static final String BM25_INDEX = "ix_rag_vector_store_content_bm25";

    private VectorStoreNames() {
    }

    /** 主表的全名：{@code schema.table}。 */
    public static String table() {
        return SCHEMA + "." + TABLE;
    }

    /** 索引元信息表的全名。 */
    public static String indexMeta() {
        return SCHEMA + "." + INDEX_META;
    }
}
