package io.github.claw4j.eval;

import io.github.claw4j.rag.rewrite.GuardedQueryRewriter;
import io.github.claw4j.rag.retrieval.KnowledgeRetrievalPipeline;
import io.github.claw4j.rag.store.RagMetadata;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把每条 gold 的检索结果导成 JSONL，交给 Python 侧用 Ragas 算分。
 *
 * <p>这一份只做导出，不做判定：指标公式在 Ragas 里（{@code IDBasedContextRecall} 等），
 * 自己不实现。它带 {@code @Tag("eval")}，被 surefire 默认排除，所以 {@code ./mvnw clean verify}
 * 不会跑它——那份要起容器、建索引、调 embedding，一次几分钟。
 *
 * <p>手动跑：{@code ./eval/rag/run.sh}。导出的行没有 {@code response} 字段，
 * 因为 L1 的非 LLM 指标只吃 id 与文本，不需要模型回答。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = RetrievalExportTest.ExportApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 本机在 IDE 里跑时没有那几个环境变量，把仓库根的 .env 当属性文件导入。
        // 路径写两个：surefire 与 IDEA 的工作目录都是模块目录，从仓库根手跑则是根目录，
        // 与 application-local.yml 同一个写法（都 optional，少一个也不报错）。
        //
        // 写在注解里而不是 DynamicPropertySource：这里的值赶在 config data 处理之前进
        // Environment（内联属性以最高优先级先落地），所以 spring.config.import 还算数；
        // 而 DynamicPropertySource 的值是建上下文时才加的，那时 import 已经处理完了。
        //
        // 系统环境变量优先级高于配置文件，所以 run.sh 里 source 过的值仍然盖住 .env。
        properties = "spring.config.import=optional:file:./.env[.properties],"
                + "optional:file:../.env[.properties]")
@Tag("eval")
class RetrievalExportTest {

    /**
     * 导出用的启动配置：声明配置类 + 打开自动配置。
     *
     * <p>不扫 {@code io.github.claw4j.app} 包：那里有插件版本闸与启动类，评测不需要它们。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ExportApplication {
    }

    /** 知识库目录：与 B6 验收用的是同一份。 */
    private static final Path KNOWLEDGE_DIR = TestPaths.repositoryRoot().resolve("examples/knowledge");

    /**
     * 导出落盘处。{@code eval/rag/reports} 的内容不入库，只有目录。
     *
     * <p>可用 {@code -Dclaw4j.eval.output=...} 换一个：同一批题跑两次对照时（substring 与
     * bm25），两次不能写同一个文件——后一次会覆盖前一次，两组分数变成同一组，而报告上看不出。
     */
    private static final Path OUTPUT = TestPaths.repositoryRoot()
            .resolve(System.getProperty("claw4j.eval.output", "eval/rag/reports/run.jsonl"));

    /** 评测用的身份。可见性现在不按身份过滤，但检索器要求 context 里必须有它，否则整轮跳过。 */
    private static final Principal EVAL_PRINCIPAL = new Principal("eval");

    /**
     * 数据库镜像。默认与线上一致；跑到 BM25 时用 {@code -Dclaw4j.eval.image=paradedb/paradedb:pg16}。
     *
     * <p>{@code asCompatibleSubstituteFor("postgres")}：Testcontainers 只认自己见过的镜像，
     * ParadeDB 是"普通 postgres + 扩展"，声明兼容后才会照常等就绪。pgvector 那个镜像同样声明，
     * 于是两条路走同一段代码——不给某一边开特例。
     */
    private static final String IMAGE =
            System.getProperty("claw4j.eval.image", "pgvector/pgvector:pg16");

    /** 容器要在 Spring 建上下文之前起好：{@code DynamicPropertySource} 要读它的端口。 */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));

    /**
     * 摘掉短型号规则时替换成的正则：要求 3 位以上数字。
     *
     * <p>语料里没有 {@code A123} 这种写法，所以这条规则等于不命中任何块。用一个语法绝对合法的
     * 正则而不是"空匹配"，是为了让失败只可能来自测的东西本身，不来自这个开关。
     */
    private static final String SHORT_MODEL_DISABLED =
            "(?<![A-Za-z0-9])[A-Za-z]\\d{3,}(?![A-Za-z0-9])";

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // 知识库默认关（它要一份目录）：这里打开并指向仓库里那份文档。
        registry.add("claw4j.rag.enabled", () -> "true");
        registry.add("claw4j.rag.directory", KNOWLEDGE_DIR::toString);
        // 守卫词表也是相对路径，而 mvn 的工作目录是 claw4j-eval/，不指过来会在建上下文时就失败。
        registry.add("claw4j.guard.sensitive-words-file",
                () -> TestPaths.repositoryRoot().resolve("examples/guard/sensitive-words.txt").toString());
        // 缓存关掉：它会给同一句话换一个缓存答案，检索就测不到了。
        registry.add("claw4j.advisor.cache.enabled", () -> "false");
        // 全文那一路的引擎：默认与生产默认一致。BM25 对照传
        // -Dclaw4j.eval.keyword-engine=bm25，此时镜像也要一起换成 ParadeDB。
        registry.add("claw4j.rag.keyword.engine",
                () -> System.getProperty("claw4j.eval.keyword-engine", "substring"));
        // 摘掉短型号那条形状规则的对照：-Dclaw4j.eval.no-short-model=true。
        // 不传时一行都不加，走 yml 默认——默认路径必须零变化。
        if (Boolean.getBoolean("claw4j.eval.no-short-model")) {
            registry.add("claw4j.rag.keyword.substring.short-model-pattern",
                    () -> SHORT_MODEL_DISABLED);
        }
    }

    /**
     * 线上那条检索链：召回 → 融合 → 重排 → 截断。
     *
     * <p>取整条链，而不是把 retriever 与 reranker 各自注进来再按顺序调一遍。这不只是好看：
     * 自己拼的那一版漏了重排，量到的是「重排前」的排序，Recall@5 比线上低 0.07，
     * 而报告上看不出这件事——它只会显得检索很差。
     */
    @Autowired
    private KnowledgeRetrievalPipeline pipeline;

    @Autowired
    private AgentPort agent;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 检索改写器。默认关，所以这里可能取不到。
     *
     * <p>必须挂在这里：本测试直接调 {@code pipeline.candidates()}，绕过了 advisor，而改写是
     * advisor 的前置步骤。不挂的话，线上开了改写而评测量到的是没改写的检索——两条路静默分叉。
     */
    @Autowired
    private ObjectProvider<GuardedQueryRewriter> rewriter;

    private List<ExportRow> rows;

    @BeforeAll
    void exportOnce() throws IOException {
        rows = export();
        Files.createDirectories(OUTPUT.getParent());
        List<String> lines = new ArrayList<>(rows.size());
        for (ExportRow row : rows) {
            lines.add(row.toJsonLine());
        }
        Files.write(OUTPUT, lines, StandardCharsets.UTF_8);
        System.out.println("检索导出：" + rows.size() + " 条 → " + OUTPUT);
    }

    @Test
    @DisplayName("每条 gold 都导出一行，而且检索结果非空（无答案题除外）")
    void everyCaseExportsRetrievedIds() throws IOException {
        List<RetrievalGold> golds = golds();

        assertThat(rows).as("导出少一条，后面算出来的平均分就少一个分母").hasSize(golds.size());
        for (ExportRow row : rows) {
            if (row.id().startsWith("rag-noanswer-")) {
                // 无答案题问的东西文档里本来就没有（"你们总经理叫什么名字"），零召回是正确结果，
                // 把它当失败等于要求检索器在没答案时硬编一段出来。
                continue;
            }
            assertThat(row.retrievedContextIds())
                    .as("gold %s 一条都没检索到：知识库没建起来，或者 min-similarity 把结果全挡了", row.id())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("id 与正文等长且同序：两个列表错位时，打分是在比两件不同的东西")
    void idsAlignWithContexts() throws IOException {
        for (ExportRow row : rows) {
            assertThat(row.retrievedContextIds())
                    .as("gold %s 的 id 数与正文数对不上", row.id())
                    .hasSameSizeAs(row.retrievedContexts());
        }
    }

    @Test
    @DisplayName("每条都有回答：空的 response 会让 L2 的 faithfulness 直接算不出来")
    void everyCaseExportsAnswer() {
        for (ExportRow row : rows) {
            assertThat(row.response())
                    .as("gold %s 的回答是空的：模型没答、或者对话链路没接上", row.id())
                    .isNotBlank();
        }
    }

    /** 查索引全集的段：gold 的 span 要落到这里，不是落到检索结果里，否则没召回的 gold 就没有 id。 */
    private List<RetrievalGold.Chunk> indexedChunks() {
        return jdbc.query("select metadata->>'path' as doc, (metadata->>'index')::int as ordinal, content "
                        + "from claw4j_rag.vector_store "
                        + "where metadata->>'path' is not null and metadata->>'index' is not null",
                (rs, rowNum) -> RetrievalGold.Chunk.of(rs.getString("doc"), rs.getInt("ordinal"), rs.getString("content")));
    }

    private List<ExportRow> export() throws IOException {
        List<RetrievalGold> golds = golds();
        List<RetrievalGold.Chunk> chunks = indexedChunks();
        assertThat(chunks).as("索引是空的：知识库没建起来，导出的每一行都会是空结果").isNotEmpty();
        // 把规模打出来：语料改了却没进库时，这是第一眼能看见的东西——68 条题照样能跑完，
        // 分数也会像个正常分数，只有"111 份却只切出 30 段"这种数字对不上。
        System.out.println("索引：" + chunks.size() + " 段 / "
                + chunks.stream().map(RetrievalGold.Chunk::doc).distinct().count() + " 份文档");

        List<ExportRow> exported = new ArrayList<>(golds.size());
        for (RetrievalGold gold : golds) {
            // 必须带身份：检索器看到 context 里没有 principal 会跳过检索，只留一行 WARN，
            // 于是每条都是空结果、指标全 0，看起来像"检索烂"，实际是没传身份。
            //
            // 取候选池（重排之后、截断之前）而不是 retrieve()：截断是保序地切前缀，所以评分侧
            // 按 k=5 截出来的就是线上 top-5，同集合同顺序；留着整池，报告才能说清缺的那段是
            // "排第 9"还是"不在候选里"——这两种要改的地方完全不同。
            Query query = new Query(gold.question(), List.of(),
                    Map.of(ContextKeys.PRINCIPAL, EVAL_PRINCIPAL));
            GuardedQueryRewriter transformer = rewriter.getIfAvailable();
            if (transformer != null) {
                query = transformer.transform(query);
            }
            List<Document> hits = pipeline.candidates(query);
            List<String> ids = new ArrayList<>(hits.size());
            List<String> texts = new ArrayList<>(hits.size());
            for (Document hit : hits) {
                String text = hit.getText() == null ? "" : hit.getText();
                // 父块（父块回填开着时）覆盖了多个子块，要展开成子块 id：gold 的 reference 是
                // 「路径#块号」，只留父块自己的 id（路径#3-5）会让"其实答上了"记成没召回。
                // 展开后一个父块按它覆盖的块数占预算，与线上"模型确实看到了这些块的文本"一致。
                List<String> covered = coveredIds(hit);
                if (covered.isEmpty()) {
                    ids.add(chunkId(hit));
                    texts.add(text);
                    continue;
                }
                for (String id : covered) {
                    ids.add(id);
                    texts.add(text);
                }
            }
            exported.add(new ExportRow(gold.id(), gold.question(), query.text(), gold.reference(),
                    answerOf(gold), List.copyOf(ids), List.copyOf(texts), gold.resolveIds(chunks)));
        }
        return exported;
    }

    /**
     * 走真实对话链路拿一条回答，L2 的 faithfulness 与 answer_relevancy 要用。
     *
     * <p>每条 gold 一个身份：会话按 userId 分段，共用一个身份的话，第二条起模型会直接从
     * 上一条的上下文里念答案，faithfulness 看着很高，实际一次检索都没发生。
     *
     * @param gold 一条 gold
     * @return 回答正文
     */
    private String answerOf(RetrievalGold gold) {
        Principal principal = new Principal("eval-rag-" + gold.id());
        OutboundMessage answer = agent.reply(new InboundMessage(principal, principal.userId(), gold.question()));
        return answer.text() == null ? "" : answer.text();
    }

    private static List<RetrievalGold> golds() throws IOException {
        Path file = TestPaths.repositoryRoot().resolve("eval/rag/gold.yaml");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Map<String, Object>> raw = new Yaml().load(reader);
            List<RetrievalGold> golds = new ArrayList<>(raw.size());
            for (Map<String, Object> row : raw) {
                golds.add(RetrievalGold.from(row));
            }
            return golds;
        }
    }

    /** 段 id 用 {@code 路径#块号}，不用向量表的 uuid：uuid 重建索引就换一批。 */
    private static String chunkId(Document document) {
        Object path = document.getMetadata().get(RagMetadata.PATH);
        Object index = document.getMetadata().get(RagMetadata.INDEX);
        int ordinal = index instanceof Number number ? number.intValue() : 0;
        return path + "#" + ordinal;
    }

    /**
     * 父块覆盖的子块 id；普通块返回空列表。
     *
     * <p>父块的 {@code covered} 是 {@code "3,4,5"} 这样的块号列表，路径取自父块自己的 metadata。
     */
    private static List<String> coveredIds(Document document) {
        Object covered = document.getMetadata().get(RagMetadata.COVERED);
        if (covered == null || covered.toString().isBlank()) {
            return List.of();
        }
        Object path = document.getMetadata().get(RagMetadata.PATH);
        List<String> ids = new ArrayList<>();
        for (String ordinal : covered.toString().split(",")) {
            if (!ordinal.isBlank()) {
                ids.add(path + "#" + ordinal.strip());
            }
        }
        return ids;
    }

    /**
     * 导出的一行。字段名按 Ragas 的 Sample 口径，Python 侧不用再翻译一遍。
     *
     * @param id                  用例 id
     * @param question            问题原文
     * @param retrievalQuery      实际送去检索的查询（改写开着时与 question 不同）
     * @param reference           参考答案
     * @param response            agent 的回答，L2 的 faithfulness / answer_relevancy 要用
     * @param retrievedContextIds 检索到的段 id，按名次排
     * @param retrievedContexts   检索到的正文，与 id 同序
     * @param referenceContextIds gold 落在索引里的段 id
     */
    private record ExportRow(String id, String question, String retrievalQuery, String reference,
                             String response, List<String> retrievedContextIds,
                             List<String> retrievedContexts, List<String> referenceContextIds) {

        String toJsonLine() {
            return "{\"id\":" + quote(id)
                    + ",\"question\":" + quote(question)
                    + ",\"retrieval_query\":" + quote(retrievalQuery)
                    + ",\"reference\":" + quote(reference)
                    + ",\"response\":" + quote(response)
                    + ",\"retrieved_context_ids\":" + array(retrievedContextIds)
                    + ",\"retrieved_contexts\":" + array(retrievedContexts)
                    + ",\"reference_context_ids\":" + array(referenceContextIds)
                    + "}";
        }

        private static String array(List<String> values) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(values.get(i)));
            }
            return out.append(']').toString();
        }

        /**
         * 手工转义而不是引一个 JSON 库：这里只输出 6 个字段的字符串数组，
         * 引库为这点事多一个依赖，而转义规则只有五条。
         */
        private static String quote(String text) {
            String value = text == null ? "" : text;
            StringBuilder out = new StringBuilder(value.length() + 2).append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        }
                        else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.append('"').toString();
        }
    }
}
