package io.github.claw4j.agent.toolaccess;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 工具向量落哪张表、怎么建（票 07）。
 *
 * <p><b>为什么独立一张表</b>：本仓唯一的 {@code VectorStore} 是知识库那一份（{@code
 * claw4j_rag.vector_store}），BM25 全文索引也建在同一张表上。工具描述一旦挤进去，知识库的两路召回
 * 都会把"Glob 是文件匹配工具"带进业务问答——症状是答退货政策时冒出一句工具说明，而且没人会想到
 * 是工具索引写的。
 *
 * <p><b>为什么不注册成 bean</b>：{@code RagAutoConfiguration} 那条是
 * {@code @ConditionalOnMissingBean(VectorStore.class)}。多一个同类型 bean 会因装配顺序让它静默
 * 退让，表现是"知识库突然全空"，日志里没有一行提到向量——一个只在重启后才发现、而且第一反应
 * 会去查文档目录的故障。所以它由 {@code AgentAutoConfiguration#toolIndex} 在工厂方法里现场构造，
 * 只交给 {@link org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex}，
 * 不进容器。
 *
 * <p>表本身交给 {@link PgVectorStore} 建（{@code initializeSchema}），与知识库那张表同一条口径：
 * 迁移脚本只建 schema。我们若在这边声明 {@code id/content/metadata/embedding} 四列，就成了
 * "我们定义官方表"，维度与列名要在两处同步——{@code V4__rag.sql} 开头那段说的就是这件事。
 */
public final class ToolVectorStore {

    /** 工具向量的 schema：与知识库分开，一行都不用共享。 */
    public static final String SCHEMA = "claw4j_tool";

    /** 工具向量的主表，由 {@link PgVectorStore} 建。 */
    public static final String TABLE = "tool_index";

    /**
     * 一个只服务于工具索引的向量库。
     *
     * <p>不建向量索引（与知识库那一份一致）：HNSW / IVFFlat 都是近似召回，漏掉的候选不会以任何
     * 方式被看见；工具只有几十个，顺序扫足够，等量级真上来了再单独一个迁移开。
     *
     * @param jdbc        JDBC 句柄，必填
     * @param embeddings  向量模型，必填
     * @param dimensions  向量维度，必须与建表时的 {@code vector(N)} 一致
     * @return 读写 {@code claw4j_tool.tool_index} 的向量库
     */
    public static VectorStore create(JdbcTemplate jdbc, EmbeddingModel embeddings, int dimensions) {
        PgVectorStore store = PgVectorStore.builder(jdbc, embeddings)
                .schemaName(SCHEMA)
                .vectorTableName(TABLE)
                .dimensions(dimensions)
                .idType(PgVectorStore.PgIdType.UUID)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(true)
                .build();
        // **必须自己调一次**：`build()` 只 `new` 出对象，建表在 `afterPropertiesSet` 里。
        // 容器里的 bean 由 Spring 调它，这一个是我们现场 new 的，没人替我们调——不调的话
        // `claw4j_tool.tool_index` 一张表都不会出现，而 PgVectorStore 的日志照样打出
        // "Using the vector table name: tool_index"，看起来一切正常，实际每次检索都撞 SQL 错。
        try {
            store.afterPropertiesSet();
        } catch (Exception failure) {
            throw new IllegalStateException("工具向量表 " + qualifiedTable()
                    + " 建不出来：检查数据源与 EmbeddingModel 是否可用", failure);
        }
        return store;
    }

    /**
     * 主表的全名：{@code schema.table}。
     *
     * <p>给日志与装配断言用：这里写错一个字，症状是"向量检索永远空手"或者"写进了知识库那张表"，
     * 两种都不会自己报错。
     *
     * @return 全名
     */
    public static String qualifiedTable() {
        return SCHEMA + "." + TABLE;
    }

    private ToolVectorStore() {
    }
}
