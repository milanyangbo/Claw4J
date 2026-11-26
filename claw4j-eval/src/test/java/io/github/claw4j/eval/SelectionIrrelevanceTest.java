package io.github.claw4j.eval;

import io.github.claw4j.agent.toolaccess.ToolCategories;
import io.github.claw4j.agent.toolaccess.ToolDescriptionTranslator;
import io.github.claw4j.agent.toolaccess.ToolSearch;
import io.github.claw4j.agent.toolaccess.ToolSearchProperties;
import io.github.claw4j.agent.toolaccess.ToolVectorStore;
import io.github.claw4j.agent.turn.ChatClientAgentPort;
import io.github.claw4j.agent.turn.ToolCallBudget;
import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.model.config.ModelConfiguration;
import io.github.claw4j.model.config.ModelProperties;
import io.github.claw4j.model.failover.RetryBudget;
import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.catalog.ModelRow;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 端到端（票 23 第 2 批）：在**我们自己的链路**上量"该不该调工具"。
 *
 * <p>与 BFCL 那一层（L1）的分工：那边是英文、每题只给 1 个工具、模型看不见检索层；这边是中文、
 * 真实目录、检索 + 渐进式披露 + 系统提示词全在。**两边的数不能比**——语言与候选数都是变量。
 * 这一层只做四组之间的对照，回答一句：我们的链路比裸模型更爱乱调吗？
 *
 * <p>判据只有一条：这一轮**有没有调用业务工具**。调 {@code toolSearchTool} 不算错——系统提示词
 * 就是让模型先检索；把检索也算进错，"正确流程"会被判成"乱调"。
 *
 * <p>工具全是替身（{@link CountingCallback}）：只数调用次数、返回固定串，不碰库、不发邮件。
 * 要判的是"调没调"，不是"调对没调对"——后者是 BFCL 的 simple_python 该管的。
 *
 * <p>四组：
 * <ul>
 *   <li><b>A</b> 一直检索 + 系统提示词（20–30 个工具时的生产形态）</li>
 *   <li><b>B</b> 一直检索，**不挂**系统提示词——直接量"提示词在不在推"</li>
 *   <li><b>C</b> 永不检索（全部工具摆在眼前）——工具少时的生产形态</li>
 *   <li><b>D</b> 一直检索 + 只给 1 个候选——贴近 BFCL 的单候选形态</li>
 * </ul>
 *
 * <p>手动跑（要先 {@code set -a; . ./.env; set +a}）：
 * <pre>
 * ./mvnw -o -Peval -pl claw4j-eval -am test -Dtest=SelectionIrrelevanceTest
 * # 只跑前 4 条验证链路：
 * ./mvnw -o -Peval -pl claw4j-eval -am test -Dtest=SelectionIrrelevanceTest -Dselection.limit=4
 * </pre>
 * 产物在 {@code eval/tools/selection/reports/}，不入库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = SelectionIrrelevanceTest.SelectionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=optional:file:./.env[.properties],"
                + "optional:file:../.env[.properties]")
@Tag("eval")
class SelectionIrrelevanceTest {

    private static final Logger log = LoggerFactory.getLogger(SelectionIrrelevanceTest.class);

    /** 与 {@code ToolRetrievalExportTest} 同一套：只声明配置类 + 打开自动配置。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SelectionApplication {
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
        registry.add("claw4j.advisor.cache.enabled", () -> "false");
        // 工具描述中文化：与生产一致，否则两翼读的是英文原文，候选质量与线上不是一回事。
        registry.add("claw4j.advisor.tool-search.zh-description.enabled", () -> "true");
    }

    @Autowired
    private ToolCatalog catalogue;

    @Autowired
    private EmbeddingModel embeddings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ToolDescriptionTranslator translator;

    /**
     * 跑四组，把每题的结果落成 jsonl。
     *
     * <p>串行而不是并行：一次真模型对话 2–5 秒，240 次在十分钟量级；并行会带来限流与
     * "同一时刻打同一个模型"的抖动，而这里要看的差异本来就只有几个点。
     */
    @Test
    @DisplayName("四组跑完，每组每题的「调了哪些业务工具」落成一份 jsonl")
    void measureGroups() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey)
                .as("L2 要真模型；运行前先 source 仓库根的 .env")
                .isNotBlank();

        List<Question> questions = loadQuestions();
        int limit = Integer.getInteger("selection.limit", questions.size());
        List<Question> sample = questions.subList(0, Math.min(limit, questions.size()));

        assertThat(countingCallbacks(catalogue))
                .as("目录里没有业务工具时，这四组量的是空气")
                .isNotEmpty();

        ChatModel model = realModel(apiKey);
        ToolIndex index = hybridIndex();
        Path reports = TestPaths.repositoryRoot().resolve("eval/tools/selection/reports");
        Files.createDirectories(reports);

        // 四组并行、组内串行：组之间没有共享状态（各自的 callback 与 advisor），
        // 而串行跑完四组要半个多小时——评测跑得太慢，人就不会去跑第二次，
        // 而这个数恰恰需要"改一次重跑一次"。
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(GROUPS.size())) {
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (Group group : GROUPS) {
                running.add(pool.submit(() -> {
                    runGroup(group, sample, model, index, reports);
                    return null;
                }));
            }
            for (var future : running) {
                future.get();
            }
        }

        System.out.println(summary(reports, sample));
    }

    /** 跑一组：每题清一次计数、每题一个会话，逐题落 jsonl。 */
    private void runGroup(Group group, List<Question> sample, ChatModel model, ToolIndex index, Path reports)
            throws IOException {
        List<ToolCallback> callbacks = countingCallbacks(catalogue);
        Map<String, AtomicInteger> calls = countsOf(callbacks);
        ChatClient chat = chatFor(group, model, index, callbacks);

        StringBuilder text = new StringBuilder();
        for (Question question : sample) {
            // **每题先把计数清零**。不清的话统计出来的是"从第 1 题到当前题的累计"，
            // 于是从第一次调用的那一题起，后面每一题都显示同一批工具名——
            // 这个错犯过一次：33 条"乱调"里每条都是 recall_session + WebFetch + querySales。
            calls.values().forEach(counter -> counter.set(0));
            String error = null;
            try {
                // 每题一个会话：跨题记历史会让"上一句"影响这一句，而那正是要排除的东西。
                new ChatClientAgentPort(chat).reply(new InboundMessage(
                        new Principal("u-eval"), "sel-" + group.name + "-" + question.id(),
                        question.text()));
            } catch (RuntimeException e) {
                // 模型编了个目录里没有的工具名时，DefaultToolCallingManager 会抛
                // IllegalStateException，**整轮直接失败**。这不是评测的噪声，是生产上的
                // 一次 500——记下来单独看，但它不该让一整组数据没有。
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("[{}] {} 整轮失败：{}", group.name, question.id(), error);
            }
            List<String> used = calledNames(calls);
            text.append(toJson(question, used, error)).append('\n');
            log.info("[{}] {} 调用={} {}", group.name, question.id(),
                    used.isEmpty() ? "无" : used, question.text());
        }
        Files.writeString(reports.resolve(group.name + ".jsonl"), text.toString(),
                StandardCharsets.UTF_8);
    }

    /**
     * 只调检索、没调业务工具时，判"没错"。
     *
     * <p>这条不花 key：用固定模型替身直接把"我做不了"当回答，验证的是判定口径本身。
     * 口径错了，上面跑出来的四组数字全都没意义。
     */
    @Test
    @DisplayName("判定口径：检索不算乱调，业务工具被调才算")
    void aSearchOnlyTurnIsNotAWrongCall() {
        List<ToolCallback> callbacks = countingCallbacks(catalogue);
        Map<String, AtomicInteger> counts = countsOf(callbacks);

        // 一条也不调 → 通过。
        assertThat(calledNames(counts)).isEmpty();

        // 调了一个业务工具 → 被抓到。
        callbacks.getFirst().call("{}");
        assertThat(calledNames(counts))
                .as("业务工具被调过，就必须出现在清单里——否则整份评测永远 0 分")
                .containsExactly(callbacks.getFirst().getToolDefinition().name());
    }

    /**
     * 对话类工具：被调用**不算乱调**。
     *
     * <p>为什么要有这一条：第一次跑 4 条时，C 组（全部工具摆在眼前）3 条"乱调"全是
     * {@code AskUserQuestionTool}——用户说「这个周末想去郊区走走」，Agent 弹一个问题问一句，
     * 那是合理的对话行为，不是事故。把它算进去，判据衡量的就变成"模型爱不爱说话"，
     * 而不是"有没有假装做事"。
     *
     * <p>只排除真正没有副作用的"继续对话"动作。{@code TodoWrite}（记计划）不排除：
     * 它意味着模型把这句寒暄当成了一个任务，那本身就是误判，只是危害比执行类小——
     * 它留在清单里，跑完可以单独看。
     */
    private static final java.util.Set<String> DIALOGUE_TOOLS = java.util.Set.of("AskUserQuestionTool");

    /** 这一轮有没有调用"执行类"业务工具——判据只认这个。 */
    private static boolean hasBusinessCall(List<String> used) {
        return used.stream().anyMatch(name -> !DIALOGUE_TOOLS.contains(name));
    }

    // ---------------------------------------------------------------- 四组

    /** 一组：名字 + 检索配置 + 挂不挂系统提示词。 */
    private record Group(String name, ToolSearchProperties properties, boolean systemSuffix) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group("A-always-suffix", props(ToolSearchProperties.Mode.ALWAYS, 5), true),
            new Group("B-always-nosuffix", props(ToolSearchProperties.Mode.ALWAYS, 5), false),
            new Group("C-off", props(ToolSearchProperties.Mode.OFF, 5), true),
            new Group("D-always-top1", props(ToolSearchProperties.Mode.ALWAYS, 1), true));

    private static ToolSearchProperties props(ToolSearchProperties.Mode mode, int maxResults) {
        return new ToolSearchProperties(ToolSearchProperties.Index.HYBRID, maxResults, null, mode, 0,
                List.of(), Map.of(), new ToolSearchProperties.Hybrid(0.5), null, List.of());
    }

    /**
     * 按一组参数拼出 ChatClient。
     *
     * <p>B 组不挂提示词这一条**只能在评测侧做**：生产那边 {@code ToolSearch.toolLoop} 把后缀写死，
     * 为一个实验加配置开关，开关本身会活在产品里。
     */
    private ChatClient chatFor(Group group, ChatModel model, ToolIndex index, List<ToolCallback> callbacks) {
        var loop = ToolSearch.toolLoop(group.properties(), index, ObservationRegistry.NOOP,
                ToolCallBudget.MAX_TOTAL_CALLS);
        if (!group.systemSuffix()) {
            loop = loop.systemMessageSuffix("");
        }
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null, loop)
                .defaultToolCallbacks(callbacks.toArray(ToolCallback[]::new))
                .build();
    }

    /** 真模型。与 {@code ChatPipelineTest} 同一个构造：句柄 + 环境里那份真值。 */
    private static ChatModel realModel(String apiKey) {
        ModelProperties.Provider deepseek = new ModelProperties.Provider();
        deepseek.setApi(ModelProperties.API_DEEPSEEK);
        deepseek.setApiKeys(List.of("secret:deepseek_api_key"));
        deepseek.setBaseUrl("https://api.deepseek.com");
        ModelRow row = new ModelRow();
        row.setId("deepseek-chat");
        row.setContextWindow(65536);
        deepseek.setModels(List.of(row));
        ModelProperties properties = new ModelProperties();
        properties.setProviders(Map.of("deepseek", deepseek));

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("real-model-key", Map.of("DEEPSEEK_API_KEY", apiKey)));

        return new ModelConfiguration()
                .providerChatModels(properties, environment, RetryBudget.defaults())
                .byRef().get(new ModelRef("deepseek", "deepseek-chat"));
    }

    /** 与生产同一档：hybrid + keyword-weight 0.5（`application.yml` 的默认值）。 */
    private ToolIndex hybridIndex() {
        VectorStore vectorStore = ToolVectorStore.create(this.jdbc, this.embeddings, 1024);
        return ToolSearch.index(props(ToolSearchProperties.Mode.ALWAYS, 5), vectorStore,
                ToolCategories.of(Map.of()), this.translator);
    }

    // ---------------------------------------------------------------- 工具替身

    /**
     * 一个只数调用次数的工具。
     *
     * <p>不真执行：判据是"调没调"，执行结果不参与判定。返回固定串而不是抛异常——抛出去会
     * 变成工具执行失败，模型可能再试一次，那一轮的调用数就翻倍了，而它反映的不是克制力。
     */
    private record CountingCallback(ToolDefinition definition, AtomicInteger calls) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return this.definition;
        }

        @Override
        public String call(String toolInput) {
            this.calls.incrementAndGet();
            return "{\"ok\":true}";
        }
    }

    private static List<ToolCallback> countingCallbacks(ToolCatalog catalogue) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallback callback : catalogue.tools()) {
            var definition = callback.getToolDefinition();
            callbacks.add(new CountingCallback(definition, new AtomicInteger()));
        }
        return callbacks;
    }

    private static Map<String, AtomicInteger> countsOf(List<ToolCallback> callbacks) {
        Map<String, AtomicInteger> counts = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            if (callback instanceof CountingCallback counting) {
                counts.put(counting.definition().name(), counting.calls());
            }
        }
        return counts;
    }

    private static List<String> calledNames(Map<String, AtomicInteger> counts) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue().get() > 0)
                .map(Map.Entry::getKey)
                .toList();
    }

    // ---------------------------------------------------------------- 题库与产物

    /** 一条题。 */
    private record Question(String id, String text, String category) {
    }

    @SuppressWarnings("unchecked")
    private static List<Question> loadQuestions() throws IOException {
        Path file = TestPaths.repositoryRoot().resolve("eval/tools/selection/irrelevance-zh.yaml");
        List<Map<String, Object>> rows = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        List<Question> questions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            questions.add(new Question((String) row.get("id"), (String) row.get("question"),
                    (String) row.get("category")));
        }
        assertThat(questions).as("题库为空时四组都跑不出东西").isNotEmpty();
        return questions;
    }

    /**
     * 一行结果。
     *
     * <p>{@code tools} 是全部被调的工具（含对话类，供人看"它到底想干嘛"），
     * {@code exec} 是去掉对话类之后的——**判据只认后者**。{@code error} 非空表示整轮抛了，
     * 那种题的 exec 一定是空的，不能当成"没错"。
     */
    private static String toJson(Question question, List<String> used, String error) {
        List<String> exec = used.stream().filter(name -> !DIALOGUE_TOOLS.contains(name)).toList();
        String errorField = error == null ? "" : ",\"error\":\"" + error.replace("\"", "'") + "\"";
        return "{\"qid\":\"" + question.id() + "\",\"category\":\"" + question.category()
                + "\",\"tools\":" + jsonArray(used) + ",\"exec\":" + jsonArray(exec) + errorField + "}";
    }

    private static String jsonArray(List<String> names) {
        return names.isEmpty() ? "[]" : "[\"" + String.join("\",\"", names) + "\"]";
    }

    /** 四组一句一行，直接把差异摆出来。 */
    private static String summary(Path reports, List<Question> sample) throws IOException {
        StringBuilder text = new StringBuilder("组,题数,乱调数,乱调率\n");
        System.out.println();
        System.out.println("== L2 四组（" + sample.size() + " 条题；判据只看执行类工具，对话类不算）==");
        for (Group group : GROUPS) {
            Path file = reports.resolve(group.name() + ".jsonl");
            if (!Files.exists(file)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank()).toList();
            List<String> wrongIds = lines.stream()
                    .filter(line -> !line.contains("\"exec\":[]"))
                    .map(line -> line.substring(line.indexOf("\"qid\":\"") + 7, line.indexOf("\",\"category\"")))
                    .toList();
            long errored = lines.stream().filter(line -> line.contains("\"error\":")).count();
            double rate = lines.isEmpty() ? 0 : (double) wrongIds.size() / lines.size();
            System.out.printf("%-20s %3d 题   乱调 %3d 题   %.1f%%   整轮失败 %d%s%n",
                    group.name(), lines.size(), wrongIds.size(), rate * 100, errored,
                    wrongIds.isEmpty() ? "" : "   " + wrongIds);
            text.append(group.name()).append(',').append(lines.size()).append(',')
                    .append(wrongIds.size()).append(',').append(String.format("%.4f", rate)).append('\n');
        }
        Files.writeString(reports.resolve("summary.csv"), text.toString(), StandardCharsets.UTF_8);
        return text.toString();
    }
}
