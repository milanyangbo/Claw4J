package io.github.claw4j.app;

import io.github.claw4j.rag.Bm25KeywordDocumentRetriever;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 那一路的真容器测试。
 *
 * <p><b>默认跳过</b>，因为 ParadeDB 镜像不是每个人本地都有，而拉它比跑一遍单测贵得多。
 * 开关与镜像名都走系统属性：
 *
 * <pre>
 * ./mvnw -pl claw4j-app test -Dtest=Bm25SearchTest \
 *     -Dclaw4j.test.paradedb=true \
 *     -Dclaw4j.test.paradedb.image=paradedb/paradedb:pg16
 * </pre>
 *
 * <p>镜像名做成属性而不是写死，是因为内网通常只能从镜像站拉；写死一个仓库地址会让这个测试
 * 只在一台机器上能跑。默认值是上游的官方名，不是某一家的镜像站。
 *
 * <p>它验证的是单测验证不了的那一半：<b>分词真的把「A2」当成一个词</b>。这是换引擎的全部理由，
 * 而 mock 一个 JdbcTemplate 只证明"SQL 发出去了"，证明不了"结果对了"。
 */
@EnabledIfSystemProperty(named = "claw4j.test.paradedb", matches = "true")
class Bm25SearchTest {

    /** 上游镜像名作默认值；内网一般要覆盖成镜像站的地址。 */
    private static final String IMAGE = System.getProperty(
            "claw4j.test.paradedb.image", "paradedb/paradedb:pg16");

    /**
     * 容器在 {@code @BeforeAll} 里才创建，不写成 static 字段的初始化。
     *
     * <p>写成字段会在类加载时构造它，而这个类在开关没开时也会被加载（条件注解要先读注解）——
     * 于是"跳过"变成"报错"，而且报的是镜像名不认识这种与本次无关的错。
     *
     * <p>{@code asCompatibleSubstituteFor("postgres")}：Testcontainers 只认自己见过的镜像，
     * ParadeDB 的镜像是"普通 postgres + 扩展"，声明兼容后它才会照常等就绪。
     */
    private static PostgreSQLContainer<?> postgres;

    private static JdbcTemplate jdbc;

    private static Bm25KeywordDocumentRetriever retriever;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
        postgres.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        // 向量表由框架建：这样表结构与生产一致，而不是测试里另写一份 DDL。
        // bm25 索引依赖 content 列，所以必须先有表再建索引。
        jdbc.execute("create extension if not exists vector");
        buildStore();
        Bm25KeywordDocumentRetriever.ensureIndex(jdbc);
        retriever = new Bm25KeywordDocumentRetriever(jdbc, "docs", 20);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void freshChunks() {
        jdbc.update("truncate claw4j_rag.vector_store");
        // 三块覆盖这道题需要的两类证据：价格表（只有「A2 单价」这几个字）与延保政策（有「延保」）。
        // 加一块退货政策做干扰：它含「退」「多少」这类泛词，substring 那一路容易被它带偏。
        insert("price-list-2026.md", 1, "整机价格", "整机价格表：A2 单价 3999 元，A3 单价 4599 元。");
        insert("warranty.md", 2, "延保政策", "延保政策：延保一年 599 元，整机延保两年 999 元。");
        insert("returns.md", 3, "退货政策", "退货政策：签收后 7 天可退，运费由买家承担。");
    }

    @Test
    @DisplayName("两字符型号不需要任何形状规则：价格表那块照样被命中，分数远高于无关块")
    void shortModelHitsPriceTable() {
        List<Document> found = retriever.retrieve(new Query("A2 买一年延保要多少钱"));

        assertThat(paths(found)).contains("price-list-2026.md");

        // 这里**不钉"排第一"**。实测：延保块 1.199、价格表 1.171、退货块 0.192。价格表输给
        // 延保块 0.03，原因是它多一行 A3 价格、块更长，被 BM25 的长度归一拍下来；票 15 里
        // 那 1.208 / 1.193 是更短的样例块，顺序就翻过来了。顺序由块长与词频决定，不由
        // "A2 被认出来"决定——把三块语料上的偶然顺序写成契约，下次改语料就得改测试。
        // 要钉的是这条：本来认不出 A2 的那一路，现在能把它命中，且分数与无关块差一个量级。
        assertThat(scoreOf(found, "price-list-2026.md"))
                .isPositive()
                .isGreaterThan(5 * scoreOf(found, "returns.md"));
        assertThat(paths(found))
                .as("这道题的两块证据（价格 + 延保政策）都在候选里")
                .contains("warranty.md");
    }

    @Test
    @DisplayName("块号与出处都读得回来：metadata 少读一个键的症状是「出处是空串」，不报错")
    void metadataSurvivesTheQuery() {
        List<Document> found = retriever.retrieve(new Query("延保一年多少钱"));

        assertThat(found).isNotEmpty();
        Document first = found.getFirst();
        assertThat(first.getMetadata()).containsEntry("section", "延保政策");
        assertThat(first.getMetadata().get("index")).isEqualTo(2);
        assertThat(first.getText()).contains("599");
    }

    @Test
    @DisplayName("纯标点不打库：交给引擎会变成查询解析错误，而不是空结果")
    void punctuationReturnsEmpty() {
        assertThat(retriever.retrieve(new Query("？？！"))).isEmpty();
    }

    /** 结果里的出处列表，顺序即排序。 */
    private static List<Object> paths(List<Document> found) {
        return found.stream().map(doc -> doc.getMetadata().get("path")).toList();
    }

    /** 某一块的分数；不在结果里时直接失败——比断言一条 0 更容易看懂。 */
    private static double scoreOf(List<Document> found, String path) {
        return found.stream()
                .filter(doc -> path.equals(doc.getMetadata().get("path")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有 " + path))
                .getScore();
    }

    /** 与 {@code SearchSqlTest} 同一个建表姿势：走框架，不自己写 DDL。 */
    private static void buildStore() {
        PgVectorStore store = PgVectorStore.builder(jdbc, embeddings())
                .schemaName("claw4j_rag")
                .vectorTableName("vector_store")
                .dimensions(1024)
                .idType(PgVectorStore.PgIdType.UUID)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(true)
                .build();
        try {
            store.afterPropertiesSet();
        } catch (Exception failure) {
            throw new IllegalStateException("建不出 vector_store", failure);
        }
    }

    /**
     * 直接写块，不走 EmbeddingModel。
     *
     * <p>BM25 只读 {@code content} 与 {@code metadata}，向量列填零就够了；真调一次 embedding
     * 会让这个测试依赖网络，而它要验证的是分词与排序。
     */
    private static void insert(String path, int ordinal, String section, String content) {
        String metadata = "{\"path\":\"" + path + "\",\"source\":\"docs\",\"index\":" + ordinal
                + ",\"content_hash\":\"h" + ordinal + "\",\"section\":\"" + section + "\"}";
        jdbc.update("insert into claw4j_rag.vector_store (id, content, metadata, embedding)"
                + " values (gen_random_uuid(), ?, ?::json, array_fill(0.0, ARRAY[1024])::vector)",
                content, metadata);
    }

    /** 建表只要维度，不真算向量。 */
    private static EmbeddingModel embeddings() {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new UnsupportedOperationException("这个测试不调 embedding：块是直接写进去的");
            }

            @Override
            public float[] embed(Document document) {
                throw new UnsupportedOperationException("这个测试不调 embedding：块是直接写进去的");
            }
        };
    }
}
