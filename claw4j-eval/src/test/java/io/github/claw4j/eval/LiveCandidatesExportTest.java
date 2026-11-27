package io.github.claw4j.eval;

import io.github.claw4j.agent.toolaccess.ToolCategories;
import io.github.claw4j.agent.toolaccess.ToolDescriptionTranslator;
import io.github.claw4j.agent.toolaccess.ToolSearch;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.agent.toolaccess.ToolVectorStore;
import io.github.claw4j.capability.tool.ToolCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把「BFCL 的 240 条不该调的问句」喂进**我们的**检索，导出每题的 5 个候选（票 28）。
 *
 * <p>为什么要多这一份导出：{@code retrieval/} 量的是"前 5 里有没有 gold"，
 * {@code bfcl/} 量的是"给定那 1 个函数该不该调"。真实链路是两者串起来——
 * **候选池由我们的检索决定，决策由模型做**。这个组合此前没有任何量具，所以两张卷子
 * 都及格也不等于链路被量过。
 *
 * <p>导出的东西里有两样必须是**真值**，不能在这里抄：
 * <ul>
 *   <li>{@code system_suffix} 取 {@link ToolSearch#SYSTEM_SUFFIX}——抄一份就会跟线上漂，
 *       而这是"我们的提示词"唯一的意义所在。</li>
 *   <li>候选的 {@code parameters} 取 {@code ToolCallback} 的 inputSchema——手写就等于
 *       给模型看了一份不存在的工具。</li>
 * </ul>
 *
 * <p>手动跑：{@code ./mvnw -o -Peval -pl claw4j-eval -am test -Dtest=LiveCandidatesExportTest}。
 * 产物 {@code eval/tools/live/candidates.json} 不入库（它是产物，问句清单才是输入）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = LiveCandidatesExportTest.LiveApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=optional:file:./.env[.properties],"
                + "optional:file:../.env[.properties]")
@Tag("eval")
class LiveCandidatesExportTest {

    /** 与 {@code ToolRetrievalExportTest} 同一套：只声明配置类 + 打开自动配置。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class LiveApplication {
    }

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    /** 与线上默认档一致。改这里之前先想清楚：这一份要量的是生产链路，不是最好的那一档。 */
    private static final double KEYWORD_WEIGHT = 0.5;

    private static final int TOP_K = 5;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("claw4j.guard.sensitive-words-file",
                () -> TestPaths.repositoryRoot().resolve("examples/guard/sensitive-words.txt").toString());
        registry.add("claw4j.skills.directory",
                () -> TestPaths.repositoryRoot().resolve("examples/skills").toString());
        registry.add("claw4j.subagents.directory",
                () -> TestPaths.repositoryRoot().resolve("examples/subagents").toString());
        registry.add("claw4j.agent.directory",
                () -> TestPaths.repositoryRoot().resolve("examples/agents").toString());
        registry.add("claw4j.agent.default", () -> "ops");
        registry.add("claw4j.advisor.cache.enabled", () -> "false");
        registry.add("claw4j.advisor.tool-search.zh-description.enabled", () -> "true");
        // 与 application-local.yml 的 servers 段一致。这一份只加载自动配置、不读那份 yml，
        // 所以加一个 server 是两处都要加（票 26 第一次跑就是这么红的）。
        mcpServer(registry, "github", "examples/mcp/gh_mcp_server.py");
        mcpServer(registry, "mail", "examples/mcp/himalaya_mcp_server.py");
        mcpServer(registry, "weather", "examples/mcp/weather_mcp_server.py");
        mcpServer(registry, "media", "examples/mcp/media_mcp_server.py");
        mcpServer(registry, "food", "examples/mcp/food_mcp_server.py");
        mcpServer(registry, "culture", "examples/mcp/culture_mcp_server.py");
        mcpServer(registry, "life", "examples/mcp/life_mcp_server.py");
        mcpServer(registry, "health", "examples/mcp/health_mcp_server.py");
        mcpServer(registry, "books", "examples/mcp/books_mcp_server.py");
        mcpServer(registry, "travel", "examples/mcp/travel_mcp_server.py");
    }

    private static void mcpServer(DynamicPropertyRegistry registry, String name, String script) {
        registry.add("claw4j.capability.mcp.servers." + name + ".command", () -> "python3");
        registry.add("claw4j.capability.mcp.servers." + name + ".args[0]",
                () -> TestPaths.repositoryRoot().resolve(script).toString());
    }

    @Autowired
    private ToolCatalog catalogue;

    @Autowired
    private EmbeddingModel embeddings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ToolDescriptionTranslator translator;

    private List<Map<String, Object>> questions;

    @BeforeAll
    void loadQuestions() throws IOException {
        Path file = TestPaths.repositoryRoot().resolve("eval/tools/live/questions.jsonl");
        this.questions = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(line, Map.class);
            this.questions.add(row);
        }
        assertThat(this.questions)
                .as("问句清单是这一票的输入，空了就等于什么都没量")
                .hasSize(240);
    }

    @Test
    @DisplayName("每道题都有候选、候选都带 parameters，且 system_suffix 取的是线上那一份")
    void export() throws IOException {
        // 量具自检：配置里一条闸都没有的话，这一跑量的就是票 28 那一版（没有闸），
        // 而分数"看着没变"——那种"怎么改都没效果"最难查，所以在这里先断掉。
        assertThat(appYmlRules())
                .as("线上 yml 里没有 gate 规则：这张量具退化成票 28 那一版，量不出准入的效果")
                .anyMatch(ToolSearchProperties.Rule::gate);

        List<ToolCallback> tools = mcpBackedCatalogue();
        Map<String, ToolCallback> byName = tools.stream()
                .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(),
                        callback -> callback, (first, second) -> first));

        ToolIndex index = hybrid();
        index.indexTools("hybrid-0.5", tools.stream()
                .map(callback -> ToolReference.builder()
                        .toolName(callback.getToolDefinition().name())
                        .summary(callback.getToolDefinition().description())
                        .build())
                .toList());

        StringBuilder items = new StringBuilder();
        int thin = 0;
        int empty = 0;
        for (Map<String, Object> question : this.questions) {
            String id = String.valueOf(question.get("id"));
            String text = String.valueOf(question.get("question"));
            ToolSearchResponse response = index.search(new ToolSearchRequest("hybrid-0.5", text, TOP_K, null));
            List<String> names = response == null || response.toolReferences() == null
                    ? List.of()
                    : response.toolReferences().stream().map(ToolReference::toolName).toList();
            if (names.size() < TOP_K) {
                thin++;
            }
            if (names.isEmpty()) {
                // 不是错，是一个**要单独报的事实**：检索层在这种问句上一条都没给，
                // 模型根本没得调，于是它必然"没调用"。把它混进分母会让 bfcl 那个数虚高。
                empty++;
            }
            items.append(item(id, text, names, byName)).append(",\n");
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("config", "hybrid-" + KEYWORD_WEIGHT);
        meta.put("top_k", TOP_K);
        meta.put("tool_count", tools.size());
        meta.put("system_suffix", ToolSearch.SYSTEM_SUFFIX);
        meta.put("empty_candidates", empty);
        meta.put("gate_rules", appYmlRules().stream()
                .filter(ToolSearchProperties.Rule::gate)
                .map(rule -> rule.tools().toString())
                .toList());
        meta.put("generated_at", java.time.OffsetDateTime.now().toString());

        Path out = TestPaths.repositoryRoot().resolve("eval/tools/live/candidates.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "{\"meta\": " + json(meta) + ",\n\"items\": [\n"
                + stripTrailingComma(items) + "\n]}\n", StandardCharsets.UTF_8);

        System.out.printf("导出 %d 题到 %s（%d 个工具；候选不足 %d 个 %d 题，其中空候选 %d 题）%n",
                this.questions.size(), out, tools.size(), TOP_K, thin, empty);
        // 空候选多到一定程度就不是"检索谨慎"，而是"索引对这个域没话说"，得先修检索再看决策分数。
        assertThat((double) empty / this.questions.size())
                .as("空候选的比例：这一层要量的是模型的决策，空候选多了量到的就是'没得调'")
                .isLessThan(0.5);
        assertThat(out).exists();
    }

    private static String item(String id, String question, List<String> names,
                               Map<String, ToolCallback> byName) {
        List<String> candidates = new ArrayList<>(names.size());
        for (String name : names) {
            ToolCallback callback = byName.get(name);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", name);
            one.put("description", callback == null ? ""
                    : String.valueOf(callback.getToolDefinition().description()));
            // schema 是 JSON 字符串（Spring AI 的 ToolDefinition 就长这样），原样带出去，
            // 别在这里解析再序列化——多一次解析就多一个"格式被改坏"的机会。
            one.put("parameters", callback == null ? "{}" : callback.getToolDefinition().inputSchema());
            candidates.add(json(one));
        }
        return "{\"id\": " + json(id) + ", \"question\": " + json(question)
                + ", \"candidates\": [" + String.join(", ", candidates) + "]}";
    }

    private static String stripTrailingComma(StringBuilder text) {
        String value = text.toString();
        return value.endsWith(",\n") ? value.substring(0, value.length() - 2) : value;
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("导出 JSON 失败", failure);
        }
    }

    /**
     * 从**线上那份** {@code application.yml} 读准入规则，而不是在这里抄一份。
     *
     * <p>抄一份的后果票 24 已经吃过：测试里那份和线上那份各自演化，量出来的分与线上无关。
     * 这里只读其中一段（{@code claw4j.advisor.tool-search.rules}），不把整份 yml 导进上下文——
     * 那会把数据源、Redis、模型 key 一起拖进来，而这台量具只需要一张触发词表。
     *
     * <p>读不到就抛，不退回空表：空表会让这一层悄悄退化成票 28 那一版（没有闸），
     * 而分数看起来"没变"——那种"怎么改都没效果"最难查。
     */
    private static List<ToolSearchProperties.Rule> appYmlRules() {
        Path yml = TestPaths.repositoryRoot().resolve("claw4j-app/src/main/resources/application.yml");
        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                    .load("application", new FileSystemResource(yml));
            Binder binder = new Binder(ConfigurationPropertySources.from(loaded));
            return binder.bind("claw4j.advisor.tool-search.rules",
                            Bindable.listOf(ToolSearchProperties.Rule.class))
                    .orElse(List.of());
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不到 " + yml + "：这张量具要和线上同一份配置才叫量具", failure);
        }
    }

    private ToolIndex hybrid() {
        ToolSearchProperties properties = new ToolSearchProperties(ToolSearchProperties.Index.HYBRID, TOP_K, null,
                null, 0, null, Map.of(), new ToolSearchProperties.Hybrid(KEYWORD_WEIGHT), null, appYmlRules());
        VectorStore vectorStore = ToolVectorStore.create(this.jdbc, this.embeddings, 1024);
        return ToolSearch.index(properties, vectorStore, ToolCategories.of(Map.of()), this.translator);
    }

    /** 等到 MCP 的 58 个工具都进了目录。等不到就如实说等不到——别导出半份目录。 */
    private List<ToolCallback> mcpBackedCatalogue() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            List<ToolCallback> tools = this.catalogue.tools();
            if (tools.size() >= 55) {
                return tools;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("60 秒内 MCP 工具没到位：只有 " + tools.size()
                        + " 个。检查 python3 与 examples/mcp/*.py。");
            }
            try {
                Thread.sleep(500);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等 MCP 工具时被打断", interrupted);
            }
        }
    }
}
