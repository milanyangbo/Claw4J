package io.github.claw4j.eval;

import io.github.claw4j.agent.toolaccess.ToolCategories;
import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.agent.toolaccess.ToolSearch;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.agent.toolaccess.ToolVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.vectorstore.VectorStore;
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
 * 拿**真工具目录**跑一遍题，打出对照表（票 09）。
 *
 * <p>与 {@code ToolGoldTest} 分工：那边用替身验"分数算得对不对"，这边不验算术，只出数。
 * 两份分开是因为这一份要起容器、要真 embedding，一次几分钟——它进不了 {@code clean verify}，
 * 也不该进。
 *
 * <p>手动跑：{@code ./mvnw -Peval -pl claw4j-eval test -Dtest=ToolRetrievalSnapshotTest}。
 * 表在 surefire 的输出里（`claw4j-eval/target/surefire-reports/*-output.txt`）。
 *
 * <p>工具目录取的是 {@code ToolCatalog}——**线上那一份**，不是手抄的一份。抄一份就会漂：
 * 描述改了、工具删了，题还照样"通过"，而它通过的是一份不存在的清单。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = ToolRetrievalSnapshotTest.SnapshotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 与 RetrievalExportTest 同一个写法：.env 要在 config data 之前进来，所以写在注解里。
        properties = "spring.config.import=optional:file:./.env[.properties],"
                + "optional:file:../.env[.properties]")
@Tag("eval")
class ToolRetrievalSnapshotTest {

    /** 只声明配置类 + 打开自动配置：评测不需要 app 的插件版本闸与启动类。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SnapshotApplication {
    }

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // 三样都要指到仓库里：mvn 的工作目录是模块目录，相对路径会落到 claw4j-eval/ 下。
        registry.add("claw4j.guard.sensitive-words-file",
                () -> TestPaths.repositoryRoot().resolve("examples/guard/sensitive-words.txt").toString());
        registry.add("claw4j.skills.directory", () -> TestPaths.repositoryRoot().resolve("examples/skills").toString());
        registry.add("claw4j.subagents.directory",
                () -> TestPaths.repositoryRoot().resolve("examples/subagents").toString());
        registry.add("claw4j.agent.directory", () -> TestPaths.repositoryRoot().resolve("examples/agents").toString());
        registry.add("claw4j.agent.default", () -> "ops");
        // 缓存关掉：它按相似度复用旧回答，会把"这次检索了什么"抹掉。
        registry.add("claw4j.advisor.cache.enabled", () -> "false");
    }

    @Autowired
    private ToolCatalog catalogue;

    @Autowired
    private EmbeddingModel embeddings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /** 容器里那个：开关由 {@code zh-description.enabled} 决定，关着时是 NONE。 */
    @Autowired
    private io.github.claw4j.agent.toolaccess.ToolDescriptionTranslator translator;

    @Test
    @DisplayName("五种配置跑同一份题：出对照表，不设阈值")
    void printsBaselineTable() throws IOException {
        List<ToolReference> tools = catalogue.tools().stream()
                .map(ToolRetrievalSnapshotTest::reference)
                .toList();
        List<ToolGold> golds = golds();
        System.out.println("工具目录 " + tools.size() + " 个，题 " + golds.size() + " 条");
        System.out.println("常驻（pinned）：" + context.getBean(ToolSearchProperties.class).pinned());
        System.out.println("中文化（两翼都吃）：" + context.getBean(ToolSearchProperties.class).zhDescription());


        List<ToolRetrievalEval.Score> scores = new ArrayList<>();
        for (String config : List.of("lucene")) {
            scores.addAll(ToolRetrievalEval.evaluate(config, index(config),
                    tools, golds));
        }
        for (double weight : List.of(1.0, 0.5, 0.0)) {
            String config = "hybrid-" + weight;
            scores.addAll(ToolRetrievalEval.evaluate(config, hybrid(weight), tools, golds));
        }

        String table = ToolRetrievalEval.table(scores);
        System.out.println(table);

        // 不断言分数高低（那是要观察的东西，不是要钉的东西），只断言"每档都真的跑了"：
        // 五行全同分就是"配置没接上"，与"三种索引一样好"分不开。
        assertThat(scores).extracting(ToolRetrievalEval.Score::config)
                .contains("lucene", "hybrid-1.0", "hybrid-0.5", "hybrid-0.0");
        assertThat(scores).allSatisfy(score ->
                assertThat(score.total()).as("这一档一条题都没跑").isPositive());
    }

    /** 只有 lucene 走这条（不要向量库）：分组留空，与线上默认一致。 */
    private ToolIndex index(String config) {
        // 每档显式写出自己的 Index，不吃 defaults 里那个：2026-11-26 起应用默认是 hybrid，
        // 拿它当 lucene 用会去找向量库（这里只传 null），报的是一句和本意无关的装配错误。
        ToolSearchProperties properties = switch (config) {
            case "lucene" -> new ToolSearchProperties(ToolSearchProperties.Index.LUCENE, 5, null);
            default -> throw new IllegalArgumentException("这一档不需要向量库：" + config);
        };
        return ToolSearch.index(properties, null, ToolCategories.of(Map.of()), this.translator);
    }

    /** hybrid 的三档权重：向量库现场建（与线上同一条路，不注册成 bean）。 */
    private ToolIndex hybrid(double weight) {
        ToolSearchProperties properties = new ToolSearchProperties(ToolSearchProperties.Index.HYBRID, 5, null,
                null, 0, null, Map.of(), new ToolSearchProperties.Hybrid(weight), null, null);
        VectorStore vectorStore = ToolVectorStore.create(this.jdbc, this.embeddings, 1024);
        return ToolSearch.index(properties, vectorStore, ToolCategories.of(Map.of()), this.translator);
    }

    private static ToolReference reference(ToolCallback callback) {
        return ToolReference.builder()
                .toolName(callback.getToolDefinition().name())
                .summary(callback.getToolDefinition().description())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<ToolGold> golds() throws IOException {
        Path file = TestPaths.repositoryRoot().resolve("eval/tools/retrieval/gold.yaml");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Map<String, Object>> rows = new Yaml().load(reader);
            List<ToolGold> golds = new ArrayList<>(rows.size());
            rows.forEach(row -> golds.add(ToolGold.from(row)));
            return golds;
        }
    }
}
