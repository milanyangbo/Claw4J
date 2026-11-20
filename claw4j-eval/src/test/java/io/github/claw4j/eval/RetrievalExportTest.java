package io.github.claw4j.eval;

import io.github.claw4j.agent.rag.KnowledgeRetrievalPipeline;
import io.github.claw4j.agent.rag.RagMetadata;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

    /** 导出落盘处。{@code eval/rag/reports} 的内容不入库，只有目录。 */
    private static final Path OUTPUT =
            TestPaths.repositoryRoot().resolve("eval/rag/reports/run.jsonl");

    /** 评测用的身份。可见性现在不按身份过滤，但检索器要求 context 里必须有它，否则整轮跳过。 */
    private static final Principal EVAL_PRINCIPAL = new Principal("eval");

    /** 容器要在 Spring 建上下文之前起好：{@code DynamicPropertySource} 要读它的端口。 */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

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
    @DisplayName("每条 gold 都导出一行，而且检索结果非空")
    void everyCaseExportsRetrievedIds() throws IOException {
        List<RetrievalGold> golds = golds();

        assertThat(rows).as("导出少一条，后面算出来的平均分就少一个分母").hasSize(golds.size());
        for (ExportRow row : rows) {
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
        // 把规模打出来：语料改了却没进库时，这是第一眼能看见的东西——56 条题照样能跑完，
        // 分数也会像个正常分数，只有"108 份却只切出 30 段"这种数字对不上。
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
            List<Document> hits = pipeline.candidates(
                    new Query(gold.question(), List.of(), Map.of(ContextKeys.PRINCIPAL, EVAL_PRINCIPAL)));
            List<String> ids = new ArrayList<>(hits.size());
            List<String> texts = new ArrayList<>(hits.size());
            for (Document hit : hits) {
                ids.add(chunkId(hit));
                texts.add(hit.getText() == null ? "" : hit.getText());
            }
            exported.add(new ExportRow(gold.id(), gold.question(), gold.reference(), answerOf(gold),
                    List.copyOf(ids), List.copyOf(texts), gold.resolveIds(chunks)));
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
     * 导出的一行。字段名按 Ragas 的 Sample 口径，Python 侧不用再翻译一遍。
     *
     * @param id                  用例 id
     * @param question            问题
     * @param reference           参考答案
     * @param response            agent 的回答，L2 的 faithfulness / answer_relevancy 要用
     * @param retrievedContextIds 检索到的段 id，按名次排
     * @param retrievedContexts   检索到的正文，与 id 同序
     * @param referenceContextIds gold 落在索引里的段 id
     */
    private record ExportRow(String id, String question, String reference, String response,
                             List<String> retrievedContextIds, List<String> retrievedContexts,
                             List<String> referenceContextIds) {

        String toJsonLine() {
            return "{\"id\":" + quote(id)
                    + ",\"question\":" + quote(question)
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
