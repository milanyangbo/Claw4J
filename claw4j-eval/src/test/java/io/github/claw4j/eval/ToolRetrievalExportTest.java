package io.github.claw4j.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把检索结果导出成第三方的指标输入（票 12）：TREC qrels / run，加一份"零候选"清单。
 *
 * <p>为什么导出而不是在 Java 里算：这个仓库的既有方针是"Java 只导出、Python 侧用第三方算指标"
 * （见 {@code eval/rag/run_ragas.py} 与 Ragas）。票 09 的自研指标留着跑快速反馈，但**对外口径**
 * 交给 {@code ir_measures}——它给的是 TREC 标准的 Recall / RR / nDCG，换个人也能复算。
 *
 * <p>为什么 {@code no-search} 那两题不写进 qrels：TREC 表达不了"期望零候选"。qrels 里没有相关
 * 文档的 qid 会被跳过或记 0，而这两题的判据是"给了候选才算错"。它们单独落一份 jsonl，由
 * {@code run_ir.py} 判。
 *
 * <p>手动跑：{@code ./mvnw -o -Peval -pl claw4j-eval -am test -Dtest=ToolRetrievalExportTest}。
 * 产物在 {@code eval/tools/retrieval/reports/}，不入库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = ToolRetrievalExportTest.ExportApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=optional:file:./.env[.properties],"
                + "optional:file:../.env[.properties]")
@Tag("eval")
class ToolRetrievalExportTest {

    /** 与 {@code ToolRetrievalSnapshotTest} 同一套：只声明配置类 + 打开自动配置。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ExportApplication {
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
        // 中文化只喂向量那一路（票 11 的架构决定）。应用默认已经是 true（票 20），
        // 这里仍显式写一遍：本类的上下文只加载自动配置、不读 application.yml，
        // 不写就会拿到组件默认的 false，量出来的分数与线上不是一回事。
        registry.add("claw4j.advisor.tool-search.zh-description.enabled", () -> "true");
        // 两个示例 MCP server（票 14）：github_* 与 mail_* 只有在接上它们时才会进目录，
        // 而那正是 business 这一档里"外部系统工具"那一类。工具清单在 serve(...) 里静态声明，
        // 本机不需要装 gh / himalaya —— 那两个 CLI 只在**调用**时才检查。
        mcpServer(registry, "github", "examples/mcp/gh_mcp_server.py");
        mcpServer(registry, "mail", "examples/mcp/himalaya_mcp_server.py");
    }

    private static void mcpServer(DynamicPropertyRegistry registry, String name, String script) {
        registry.add("claw4j.capability.mcp.servers." + name + ".command", () -> "python3");
        registry.add("claw4j.capability.mcp.servers." + name + ".args[0]",
                () -> TestPaths.repositoryRoot().resolve(script).toString());
    }

    /** 与 {@code ToolRetrievalSnapshotTest} 同一个顺序：两档关键字、三档 hybrid。 */
    private static final List<String> CONFIGS =
            List.of("regex", "lucene", "hybrid-1.0", "hybrid-0.5", "hybrid-0.0");

    @Autowired
    private ToolCatalog catalogue;

    @Autowired
    private EmbeddingModel embeddings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ToolDescriptionTranslator translator;

    /** 产物目录。 */
    private Path reports;

    /** 这一轮实际问出来的顺序：config → qid → 工具名（按检索给的顺序）。 */
    private final Map<String, Map<String, List<String>>> rankings = new LinkedHashMap<>();

    private List<ToolGold> golds;

    /**
     * 跑一次，写三份文件。
     *
     * <p>检索只跑一遍、三个用例共用：一档一次真 embedding，重复跑三次只会让评测从"慢"变成"很慢"，
     * 而三个用例判的是同一批产物的不同侧面。
     */
    @BeforeAll
    void export() throws IOException {
        this.reports = TestPaths.repositoryRoot().resolve("eval/tools/retrieval/reports");
        Files.createDirectories(this.reports);
        this.golds = golds();

        List<ToolReference> tools = mcpBackedCatalogue().stream()
                .map(ToolRetrievalExportTest::reference)
                .toList();

        writeQrels();
        writeCategories();
        writeCatalogue(tools);
        for (String config : CONFIGS) {
            // hybrid 的三档要现场建向量库；前三档传 null。分派写在这里而不是 index() 里，
            // 是因为 hybrid 需要 jdbc 与 embedding，那是这个类的状态，不是那个纯函数的事。
            ToolIndex index = config.startsWith("hybrid-")
                    ? hybrid(Double.parseDouble(config.substring("hybrid-".length())))
                    : index(config);
            index.indexTools(config, tools);
            Map<String, List<String>> byQid = new LinkedHashMap<>();
            for (ToolGold gold : this.golds) {
                byQid.put(gold.id(), names(index.search(
                        new ToolSearchRequest(config, gold.question(), 5, null))));
            }
            this.rankings.put(config, byQid);
            writeRun(config, byQid);
        }
        writeNoCandidate();
    }

    @Test
    @DisplayName("run 的 rank 就是检索返回的顺序，不是按名字排的")
    void runKeepsSearchOrder() throws IOException {
        List<String> lines = Files.readAllLines(this.reports.resolve("hybrid-0.0.run"), StandardCharsets.UTF_8);
        assertThat(lines).as("一档一题都没导出").isNotEmpty();

        // 取第一题：同一 qid 的连续几行，rank 必须是 1、2、3……，docno 必须与内存里的顺序逐位相同。
        String qid = lines.getFirst().split(" ")[0];
        List<String> expected = this.rankings.get("hybrid-0.0").get(qid);
        assertThat(expected).as("这一题一个候选都没给，后面的顺序断言就没有意义").isNotEmpty();
        List<String> runPart = lines.stream()
                .filter(line -> line.startsWith(qid + " "))
                .map(line -> line.split(" ")[2])
                .toList();
        assertThat(runPart)
                .as("按名字或按分数重排时，第二行就不再是检索的第二名")
                .startsWith(expected.toArray(String[]::new));
        assertThat(lines.stream().filter(line -> line.startsWith(qid + " "))
                .map(line -> Integer.parseInt(line.split(" ")[3]))
                .toList())
                .as("rank 从 1 起、连续、行数等于候选数")
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, runPart.size())
                        .boxed().toList());
    }

    @Test
    @DisplayName("qrels 只写有 gold 的题；no-search 那两题不进")
    void qrelsSkipsZeroCandidateQuestions() throws IOException {
        List<String> lines = Files.readAllLines(this.reports.resolve("qrels.txt"), StandardCharsets.UTF_8);

        Set<String> withGold = this.golds.stream()
                .filter(gold -> !gold.goldTools().isEmpty())
                .map(ToolGold::id)
                .collect(Collectors.toSet());
        Set<String> zeroCandidate = this.golds.stream()
                .filter(gold -> gold.goldTools().isEmpty())
                .map(ToolGold::id)
                .collect(Collectors.toSet());

        assertThat(lines).hasSize(withGold.size());
        assertThat(lines).extracting(line -> line.split(" ")[0])
                .as("qrels 里出现零候选题时，第三方会把它们按「没检索到」算，分母就不对了")
                .doesNotContainAnyElementsOf(zeroCandidate)
                .containsExactlyInAnyOrderElementsOf(withGold);
    }

    /**
     * qrels 里出现的每个工具名，都必须在当轮的工具目录里。
     *
     * <p>抓的是"题写错工具名"：原来 `tools-memory-preference` 的 gold 是 `MemoryView`，而目录里没有
     * 这个工具——那题**永远 miss**，`zh-en` 的 R@1 白扣一格，而报告上看着像"检索不好"。
     * `ToolGoldTest` 只校验格式，抓不到这类错：`MemoryView` 是个完全合法的工具名形状。
     *
     * <p>为什么放在这条 `@Tag("eval")` 的手跑通道里：只有这一条能同时看到真题库与真目录
     * （目录是条件装配与画像过滤之后的结果，不是代码里写死的那份名单）。
     */
    @Test
    @DisplayName("qrels 里的工具名都在当轮目录里：写错名字的题永远 miss，报告上却像检索不好")
    void everyGoldToolExistsInCatalogue() {
        Set<String> available = this.catalogue.tools().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        Map<String, List<String>> unknown = new LinkedHashMap<>();
        for (ToolGold gold : this.golds) {
            List<String> missing = gold.goldTools().stream()
                    .filter(tool -> !available.contains(tool))
                    .toList();
            if (!missing.isEmpty()) {
                unknown.put(gold.id(), missing);
            }
        }

        assertThat(unknown)
                .as("这些题的 gold 不在当轮目录里（现有 %d 个工具），它们永远 miss", available.size())
                .isEmpty();
    }

    /**
     * 分类清单：题库里每一条都要有，且只许出现题库认的那四个值。
     *
     * <p>它是给报告用的：`run_ir.py` 靠它把 qrels/run 切成四份，分别算分。所以它必须覆盖
     * **全部**题——包括 `no-search` 那两条（它们不在 qrels 里，但报告要报它们的零候选率）。
     * 漏一条的后果不是报错，是那一档在报告里静默消失，而人看着少了一行的表格不会发现。
     */
    @Test
    @DisplayName("分类清单覆盖题库每一条，且只出现题库认的四个值")
    void categoriesCoverEveryQuestion() throws IOException {
        List<String> lines = Files.readAllLines(this.reports.resolve("categories.txt"),
                StandardCharsets.UTF_8);
        Map<String, String> byQid = new LinkedHashMap<>();
        for (String line : lines) {
            String[] parts = line.split("\t");
            assertThat(parts).as("一行必须是 qid<TAB>category：" + line).hasSize(2);
            byQid.put(parts[0], parts[1]);
        }

        assertThat(byQid.keySet())
                .as("漏一条 = 报告里少一行，而人不会发现（no-search 那两条最容易漏，它们不在 qrels 里）")
                .containsExactlyInAnyOrderElementsOf(this.golds.stream().map(ToolGold::id).toList());
        assertThat(byQid.values())
                .as("分类打错一个字，那一档就静默消失")
                .allSatisfy(category -> assertThat(ToolGold.CATEGORIES).contains(category));
    }

    /**
     * 目录，且确认 MCP 那几个工具已经到位。
     *
     * <p>为什么要等：{@code McpToolProvider} 首次接触是同步的，但**连不上时只记 DOWN 再排一次
     * 退避重试**（票 02 / 07 的设计）。所以上下文起来了不等于工具到位——脚本慢一点、python3
     * 冷启动一下，目录里就可能还没有 `mail_*`。不等的话，这一轮会"少五个工具、悄悄算完"，
     * 而报告上只是数字低一点。
     *
     * <p>等不到就**失败**，不跳过：跳过等于把本票要验的东西静默取消。
     */
    private List<ToolCallback> mcpBackedCatalogue() {
        Set<String> required = Set.of("github_issue_list", "github_pr_checks",
                "mail_list_unread", "mail_send");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            List<ToolCallback> tools = this.catalogue.tools();
            Set<String> names = tools.stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .collect(Collectors.toSet());
            if (names.containsAll(required)) {
                return tools;
            }
            if (System.nanoTime() > deadline) {
                Set<String> missing = new LinkedHashSet<>(required);
                missing.removeAll(names);
                throw new IllegalStateException("60 秒内 MCP 工具没到位：" + missing
                        + "。检查 claw4j.capability.mcp.servers 里两个 server 能否起来"
                        + "（python3 在 PATH 里吗、examples/mcp/*.py 还在吗）。现有 "
                        + names.size() + " 个工具。");
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

    /**
     * 两个示例 MCP server 的工具真的进了目录。
     *
     * <p>票 13 的目录断言抓出过四道死题（gold 指向 `mail_*` / `github_*`，而评测上下文没接 MCP），
     * 当时是换成别的题绕开的。这条反过来钉住"接上了没有"：绕开与接上，报告上都只是数字，
     * 只有这条能分开它们。
     */
    @Test
    @DisplayName("两个示例 MCP server 的工具进了目录：github_* 与 mail_* 各两个")
    void mcpToolsReachTheCatalogue() {
        Set<String> names = mcpBackedCatalogue().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names)
                .as("这四个是票 13 里那四道死题的 gold；它们不在，那四道题就永远 miss")
                .contains("github_issue_list", "github_pr_checks", "mail_list_unread", "mail_send");
    }

    @Test
    @DisplayName("零候选题单独落一份，带实际候选数")
    void zeroCandidateQuestionsGoToTheirOwnFile() throws IOException {
        List<String> lines = Files.readAllLines(this.reports.resolve("no-search.jsonl"),
                StandardCharsets.UTF_8);
        List<Map<String, Object>> rows = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String line : lines) {
            rows.add(mapper.readValue(line, Map.class));
        }

        Set<String> zeroCandidate = this.golds.stream()
                .filter(gold -> gold.goldTools().isEmpty())
                .map(ToolGold::id)
                .collect(Collectors.toSet());
        assertThat(rows).extracting(row -> row.get("qid"))
                .containsExactlyInAnyOrderElementsOf(zeroCandidate);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("returned"))
                .as("actual 候选数必须写上：判据是「必须为 0」，写死 0 就永远通过")
                .isEqualTo(this.rankings.get("regex").get((String) row.get("qid")).size()));
    }

    /**
     * qrels：一条题一行，相关性固定 1。
     *
     * <p>每题只标一个 gold，所以第三方算出来的 Recall@k 与自研的"命中任一"是同一个数。
     * 以后加多 gold 的题，两者会分道扬镳（前者是覆盖比例）——到那时这条注释要跟着改。
     */
    private void writeQrels() throws IOException {
        StringBuilder text = new StringBuilder();
        for (ToolGold gold : this.golds) {
            for (String tool : gold.goldTools()) {
                text.append(gold.id()).append(" 0 ").append(tool).append(" 1\n");
            }
        }
        Files.writeString(this.reports.resolve("qrels.txt"), text.toString(), StandardCharsets.UTF_8);
    }

    /** TREC run：{@code qid Q0 docno rank score runid}。score 用 {@code 1/rank}，保证严格递减。 */
    private void writeRun(String config, Map<String, List<String>> byQid) throws IOException {
        StringBuilder text = new StringBuilder();
        for (ToolGold gold : this.golds) {
            if (gold.goldTools().isEmpty()) {
                continue;
            }
            List<String> got = byQid.get(gold.id());
            for (int i = 0; i < got.size(); i++) {
                text.append(gold.id()).append(" Q0 ").append(got.get(i)).append(' ')
                        .append(i + 1).append(' ')
                        .append(String.format(java.util.Locale.ROOT, "%.6f", 1.0 / (i + 1)))
                        .append(' ').append(config).append('\n');
            }
        }
        Files.writeString(this.reports.resolve(config + ".run"), text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 分类清单：{@code qid<TAB>category}，题库里每一条都要有。
     *
     * <p>为什么由 Java 写而不是让 Python 去读 {@code gold.yaml}：分类只有一份真相，它在这边
     * （{@link ToolGold#CATEGORIES}）。让 Python 也解析一遍，等于给它一份会漂的副本，而"两边
     * 分类不一致"这种错在报告上表现为"某一档数字不对"，最难查。代价是多一个文件、多一个依赖
     * 也没有——{@code pyyaml} 不用装了。
     */
    private void writeCategories() throws IOException {
        StringBuilder text = new StringBuilder();
        for (ToolGold gold : this.golds) {
            text.append(gold.id()).append('\t').append(gold.category()).append('\n');
        }
        Files.writeString(this.reports.resolve("categories.txt"), text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 目录清单：一行一个工具名，就是这一轮索引里那些。
     *
     * <p>报告里那句"工具目录 N 个"要有个出处；顺带排障时能一眼看出"某个工具到底装没装上"
     * （票 13 的两批死题就是这类问题的产物）。
     */
    private void writeCatalogue(List<ToolReference> tools) throws IOException {
        StringBuilder text = new StringBuilder();
        for (ToolReference tool : tools) {
            text.append(tool.toolName()).append('\n');
        }
        Files.writeString(this.reports.resolve("catalogue.txt"), text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 零候选题：qid + 实际给了几个候选 + 给了哪几个。
     *
     * <p>带工具名是因为排障要看"谁挤进来的"，而指标只记个数。
     */
    private void writeNoCandidate() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder text = new StringBuilder();
        for (ToolGold gold : this.golds) {
            if (!gold.goldTools().isEmpty()) {
                continue;
            }
            List<String> got = this.rankings.get("regex").get(gold.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("qid", gold.id());
            row.put("returned", got.size());
            row.put("tools", got);
            text.append(mapper.writeValueAsString(row)).append('\n');
        }
        Files.writeString(this.reports.resolve("no-search.jsonl"), text.toString(), StandardCharsets.UTF_8);
    }

    /** 前三档不需要向量库，传 {@code null}；与快照那份同一套构造。 */
    private ToolIndex index(String config) {
        ToolSearchProperties properties = switch (config) {
            case "lucene" -> new ToolSearchProperties(ToolSearchProperties.Index.LUCENE, 5, null);
            default -> ToolSearchProperties.defaults();
        };
        return ToolSearch.index(properties, null, ToolCategories.of(Map.of()), this.translator);
    }

    private ToolIndex hybrid(double weight) {
        ToolSearchProperties properties = new ToolSearchProperties(ToolSearchProperties.Index.HYBRID, 5, null,
                null, 0, null, Map.of(), new ToolSearchProperties.Hybrid(weight), null);
        VectorStore vectorStore = ToolVectorStore.create(this.jdbc, this.embeddings, 1024);
        return ToolSearch.index(properties, vectorStore, ToolCategories.of(Map.of()), this.translator);
    }

    private static List<String> names(ToolSearchResponse response) {
        if (response == null || response.toolReferences() == null) {
            return List.of();
        }
        return response.toolReferences().stream().map(ToolReference::toolName).toList();
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
