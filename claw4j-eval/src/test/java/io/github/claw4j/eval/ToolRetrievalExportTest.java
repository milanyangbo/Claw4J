package io.github.claw4j.eval;

import io.github.claw4j.agent.toolaccess.ToolCategories;
import io.github.claw4j.agent.toolaccess.ToolDescriptionTranslator;
import io.github.claw4j.agent.toolaccess.TranslatedVectorIndex;
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
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
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
 * 把检索结果导出成第三方的指标输入（票 12）：TREC qrels / run，加一份分类清单。
 *
 * <p>为什么导出而不是在 Java 里算：这个仓库的既有方针是"Java 只导出、Python 侧用第三方算指标"
 * （见 {@code eval/rag/run_ragas.py} 与 Ragas）。票 09 的自研指标留着跑快速反馈，但**对外口径**
 * 交给 {@code ir_measures}——它给的是 TREC 标准的 Recall / RR / nDCG，换个人也能复算。
 *
 * <p>2026-11-26（票 22）不再导出 {@code no-search.jsonl}：那 10 道"期望零候选"的题随
 * {@code ChitchatToolIndex} 一起撤了，判据归 BFCL 的 {@code irrelevance} 档。
 * 检索层对任何 query 都给候选，qrels 里每条题都有自己的 gold。
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
        // 票 26 的六个生活域。**这里必须与 application-local.yml 的 servers 段一致**：
        // 本类的上下文只加载自动配置、不读那份 yml（见上面 zh-description 那条注释），
        // 所以加一个 server 是两处都要加。漏了这一处，评测会报"60 秒内 MCP 工具没到位"，
        // 而线上已经接上了——2026-11-27 第一次跑就是这么红的。
        mcpServer(registry, "weather", "examples/mcp/weather_mcp_server.py");
        mcpServer(registry, "media", "examples/mcp/media_mcp_server.py");
        mcpServer(registry, "food", "examples/mcp/food_mcp_server.py");
        mcpServer(registry, "culture", "examples/mcp/culture_mcp_server.py");
        mcpServer(registry, "life", "examples/mcp/life_mcp_server.py");
        mcpServer(registry, "health", "examples/mcp/health_mcp_server.py");
        // 票 27 的两个域：书（本地 24 本书架）与出行（时区 / 日出 / 距离，本地算）。
        // 同样两处都要加——这是上面那条注释说的结构问题，还没解。
        mcpServer(registry, "books", "examples/mcp/books_mcp_server.py");
        mcpServer(registry, "travel", "examples/mcp/travel_mcp_server.py");
    }

    private static void mcpServer(DynamicPropertyRegistry registry, String name, String script) {
        registry.add("claw4j.capability.mcp.servers." + name + ".command", () -> "python3");
        registry.add("claw4j.capability.mcp.servers." + name + ".args[0]",
                () -> TestPaths.repositoryRoot().resolve(script).toString());
    }

    /**
     * 与 {@code ToolRetrievalSnapshotTest} 同一个顺序：一档 lucene、三档 hybrid。
     *
     * <p>票 21 把 {@code regex}（自研中文二元组）与 {@code vector} 两档删了：前者的位置由规则层承接，
     * 后者与 {@code hybrid-0.0} 等价（实测同为 0.820）。所以这张表从五行降到四行。
     */
    private static final List<String> CONFIGS =
            List.of("lucene", "hybrid-1.0", "hybrid-0.5", "hybrid-0.0");

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
     * 跑一次，把 qrels / 分类清单 / 目录 / 每档一份 run 写到 reports/。
     *
     * <p>检索只跑一遍、几个用例共用：一档一次真 embedding，重复跑三次只会让评测从"慢"变成"很慢"，
     * 而几个用例判的是同一批产物的不同侧面。
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
            // hybrid 的三档要现场建向量库；lucene 传 null。分派写在这里而不是 index() 里，
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
        dumpWingScores(tools);
    }

    /**
     * 两翼的原始分数，逐题落一份（票 25 的第一步：定相关性下限要先看分布）。
     *
     * <p>为什么要它：融合是 RRF，出来只有名次没有分数，所以"这批候选够不够格"在融合之后无处判断。
     * 定阈值需要两个分布——该命中的工具拿多少分、同一个 query 下别的工具拿多少分。后者就是
     * "不相关时的分数"，不用另造负样本，同一批题里取非 gold 的那几个即可。
     *
     * <p>两翼的门槛都调到 0：这里要的是分布本身，不是"过了 0.25 之后还剩谁"。
     * 向量翼那条 0.2 是框架的 {@code private static final}，调不动——所以**低于 0.2 的分数看不到**，
     * 这一条要写进结论，不能当成"没有低分"。
     */
    private void dumpWingScores(List<ToolReference> tools) throws IOException {
        ToolIndex luceneWing = new TranslatedVectorIndex(new LuceneToolIndex(0f), this.translator);
        VectorStore store = ToolVectorStore.create(this.jdbc, this.embeddings, 1024);
        ToolIndex vectorWing = new TranslatedVectorIndex(new VectorToolIndex(store), this.translator);
        luceneWing.indexTools("scores", tools);
        vectorWing.indexTools("scores", tools);

        StringBuilder text = new StringBuilder();
        for (ToolGold gold : this.golds) {
            for (Map.Entry<String, ToolIndex> wing : Map.of("lucene", luceneWing, "vector", vectorWing)
                    .entrySet()) {
                ToolSearchResponse response = wing.getValue().search(
                        new ToolSearchRequest("scores", gold.question(), 50, null));
                List<String> hits = new ArrayList<>();
                for (ToolReference reference : response.toolReferences()) {
                    hits.add("{\"tool\":" + quote(reference.toolName())
                            + ",\"score\":" + reference.relevanceScore() + "}");
                }
                text.append("{\"qid\":").append(quote(gold.id()))
                        .append(",\"category\":").append(quote(gold.category()))
                        .append(",\"gold\":").append(array(gold.goldTools()))
                        .append(",\"wing\":").append(quote(wing.getKey()))
                        .append(",\"hits\":[").append(String.join(",", hits)).append("]}\n");
            }
        }
        Files.writeString(this.reports.resolve("scores.jsonl"), text.toString(), StandardCharsets.UTF_8);
    }

    private static String array(List<String> values) {
        return "[" + String.join(",", values.stream().map(ToolRetrievalExportTest::quote).toList()) + "]";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
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
    @DisplayName("qrels 一条题一行，覆盖题库全部题")
    void qrelsCoversEveryQuestion() throws IOException {
        List<String> lines = Files.readAllLines(this.reports.resolve("qrels.txt"), StandardCharsets.UTF_8);

        Set<String> ids = this.golds.stream().map(ToolGold::id).collect(Collectors.toSet());
        assertThat(lines).hasSize(ids.size());
        assertThat(lines).extracting(line -> line.split(" ")[0])
                .as("漏一条 = 分母少一题，分数会悄悄变好看")
                .containsExactlyInAnyOrderElementsOf(ids);
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
     * 分类清单：题库里每一条都要有，且只许出现题库认的那几个值。
     *
     * <p>它是给报告用的：`run_ir.py` 靠它把 qrels/run 切成一类一份，分别算分。所以它必须覆盖
     * **全部**题。漏一条的后果不是报错，是那一档在报告里静默消失，而人看着少了一行的表格不会发现。
     */
    @Test
    @DisplayName("分类清单覆盖题库每一条，且只出现题库认的类别")
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
                .as("漏一条 = 报告里少一行，而人不会发现")
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
        // 每个 MCP server 抽一个代表：只要有一个没起来，说明那条 stdio 连接是 DOWN，
        // 那个 server 的整组工具都不会在目录里（不是少一个，是少一组）。
        Set<String> required = Set.of("github_issue_list", "github_pr_checks",
                "mail_list_unread", "mail_send",
                "weather_now", "tv_show_search", "recipe_search",
                "art_search", "fx_convert", "bmi_calc",
                "book_search", "sun_times");
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
    @DisplayName("示例 MCP server 的工具进了目录：代码/邮件两个 + 生活域六个")
    void mcpToolsReachTheCatalogue() {
        Set<String> names = mcpBackedCatalogue().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names)
                .as("这四个是票 13 里那四道死题的 gold；它们不在，那四道题就永远 miss")
                .contains("github_issue_list", "github_pr_checks", "mail_list_unread", "mail_send")
                .as("票 26 加的六个生活域 server，每个抽一个代表：少一个就是那个 server 没起来")
                .contains("weather_now", "tv_show_search", "recipe_search",
                        "art_search", "fx_convert", "bmi_calc");
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

    /** 只有 lucene 走这条（不要向量库）；与快照那份同一套构造。 */
    private ToolIndex index(String config) {
        ToolSearchProperties properties = switch (config) {
            case "lucene" -> new ToolSearchProperties(ToolSearchProperties.Index.LUCENE, 5, null);
            // 不再回落成 defaults：defaults 现在是 lucene，回落会让"写错档名"变成"悄悄跑了 lucene"。
            default -> throw new IllegalArgumentException("这一档不需要向量库：" + config);
        };
        return ToolSearch.index(properties, null, ToolCategories.of(Map.of()), this.translator);
    }

    private ToolIndex hybrid(double weight) {
        ToolSearchProperties properties = new ToolSearchProperties(ToolSearchProperties.Index.HYBRID, 5, null,
                null, 0, null, Map.of(), new ToolSearchProperties.Hybrid(weight), null, null);
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
