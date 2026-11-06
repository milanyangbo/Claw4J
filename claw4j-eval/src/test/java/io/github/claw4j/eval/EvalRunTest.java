package io.github.claw4j.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器：读用例 → 调真模型 → 交给判分 → 写报告。
 *
 * <p><b>它整份带 {@code @Tag("eval")}，默认被 surefire 排除</b>。理由不是"太慢"，
 * 是它<b>不属于回归基线</b>：五十条真模型调用（每条 1~6 次往返）是另一个量级，
 * 而且要联网、要花钱、结果会随模型版本漂。ADR-0019 的原话是"与装配用例分开跑、分开看"。
 *
 * <p><b>但它不是"没人管"</b>：用例集本身由 {@link EvalCaseSetTest} 在回归里守着，
 * 判分口径由 {@code EvalScorerTest} 守着。这一份只负责"把两者接起来"，
 * 所以它是胶水——胶水漏了看得见（报告没有、id 对不上），判分错了看不见。
 *
 * <p><b>放在 {@code src/test} 而不是主代码</b>：它天然要真模型、真库、真容器，
 * 进不了回归；放在测试里，它就不会在 jacoco 上留覆盖率债（主代码那三个类都能被回归覆盖）。
 *
 * <p><b>启动类要显式写，而且不能用 app 的那个</b>：本模块不依赖 {@code claw4j-app}
 * （它的主构件被 repackage 成可执行 jar，类在 BOOT-INF 里，别的模块拿不到）。
 * 所以这里自带一个最小的 {@link EvalApplication}：{@code @SpringBootConfiguration}
 * + {@code @EnableAutoConfiguration}，与 {@code Claw4jApplication} 等价的部分只有这两样——
 * 各模块的装配全在自动配置里，不靠组件扫描。
 *
 * <p>怎么跑：{@code ./scripts/eval.sh}（全跑）、{@code ./scripts/eval.sh sales authz}（按类）、
 * {@code ./scripts/eval.sh --case sales-weekly-east}（单条）。
 */
@SpringBootTest(classes = EvalRunTest.EvalApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("eval")
class EvalRunTest {

    /**
     * 评测用的启动配置。**只有两件事**：声明这是一个 Boot 配置、把自动配置打开。
     *
     * <p>不扫 {@code io.github.claw4j.app} 包：那里只有启动类与插件版本闸，
     * 而闸不属于评测要看的东西（它证明的是"插件声明与核心版本匹配"，有它自己的用例）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class EvalApplication {
    }

    /** 报告落盘处（.gitignore 掉了内容，只留目录）。 */
    private static final Path REPORT_DIR = EvalCaseSetTest.repositoryRoot().resolve("eval/reports");

    /** 知识库目录：与 examples/README 里那一份同一个来源，用例的出处锚指着它。 */
    private static final Path KNOWLEDGE_DIR = EvalCaseSetTest.repositoryRoot().resolve("examples/knowledge");

    /**
     * 容器要在 Spring 建上下文之前起好：{@link DynamicPropertySource} 要读它的端口。
     *
     * <p>不用 {@code @Testcontainers}：那会在报告里多一行"跳过了容器管理"，
     * 而我们本来就只跑一个类，静态块更直白。
     */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 业务数据必须与回归同源：mustMention 里的 128400 来自 V2__business_seed.sql，
        // 库里的种子换一份，用例就从写下那天是红的。
        registry.add("spring.flyway.enabled", () -> "true");
        // 知识库默认关（它要一份目录）。评测要测它，所以在这里打开并指向仓库里那份文档。
        registry.add("claw4j.rag.enabled", () -> "true");
        registry.add("claw4j.rag.directory", KNOWLEDGE_DIR::toString);
        // 语义缓存要关掉。它的上下文键是"权限指纹 + 问题里的时间词与数字"，**不含区域词**，
        // 所以"上周华东"与"上周华南"在它看来是同一个问题——后一条会直接拿到前一条的答案，
        // 工具一次都不调，判出来是"轨迹没命中"。那不是模型变笨，是评测自己在读缓存。
        // 缓存本身有它自己的用例，不在这一份里判。
        registry.add("claw4j.cache.enabled", () -> "false");
    }

    @Autowired
    private AgentPort agent;

    /** 轨迹从审计读：一次工具调用一行，与判分器认的形状一致（ADR-0027）。 */
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelProperties models;

    private final ObjectMapper json = new ObjectMapper();

    /** 一跑一行的结果。{@code answer} 留在里面是为了报告：假红一眼能看出来。 */
    private record CaseResult(EvalCase testCase, String turnId, String answer, EvalVerdict verdict,
                              long millis) {
    }

    /**
     * 跑选中的用例、写报告、打印摘要、断言全过。
     *
     * <p>断言"全过"而不是"跑完了"：一个只满足于"跑完"的量具会在第一次变差时保持沉默。
     * 它红了说明这版比用例集写下的那一版差——那是要看一眼再决定改代码还是改用例的信号，
     * 而"看一眼"正是这张票要争取的东西。
     */
    @Test
    @DisplayName("一条用例从提问跑到判定，报告里三个判定都有值")
    void theCaseSetIsScoredAndReported() {
        List<EvalCase> selected = select(EvalCaseSetTest.cases());
        Map<String, Principal> roster = EvalCaseSetTest.principals();
        assertThat(selected)
                .as("过滤条件 [%s] 没匹配到任何用例：写错类别名时这里红，而不是跑完零条还报绿",
                        String.join(",", filter()))
                .isNotEmpty();

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase testCase : selected) {
            results.add(run(testCase, roster.get(testCase.principal())));
        }

        Path report = writeReport(results);
        print(results, report);

        List<CaseResult> failed = results.stream().filter(result -> !result.verdict().passed()).toList();
        assertThat(failed)
                .as("这些用例没过（报告：%s）%n%s", report,
                        failed.stream().map(result -> result.verdict().summary())
                                .collect(Collectors.joining(System.lineSeparator())))
                .isEmpty();
    }

    /**
     * 跑一条：先把 setup 说完，再问 question，然后读回 run 与它的 step。
     *
     * <p><b>runId 为 null 是硬错误，不是"跳过"</b>：判分靠它读回轨迹，为空说明 run 存储
     * 没接上（A01 的接线断了）。那种情况下"判出来的是空的"会被读成"模型没调工具"，
     * 于是有人去改工具描述——离真正的原因隔了十万八千里。
     */
    private CaseResult run(EvalCase testCase, Principal rosterEntry) {
        assertThat(rosterEntry).as("用例 %s 引用了不存在身份 %s", testCase.id(), testCase.principal()).isNotNull();
        // **每条用例一个新 userId**，这不只是"换个 chatId 那么轻的事"：
        // 会话 id 是 `userId@权限指纹`，**刻意不含 chatId**（SessionKeys 的类注释：chatId 是通道侧
        // 寻址键，换通道就会变，编进主键会让"换通道"等于"换会话"）。
        // 所以同一个 userId 跑五十条用例＝五十条共享一段会话历史：从第二条起，模型会直接从
        // 上下文里念出上一条的数字，工具一次都不调，判出来是"轨迹没命中"。
        // 第一次跑全量时 51 条只过了 10 条，就是这个原因——那不是模型变笨，是评测自己串了味。
        Principal principal = new Principal("eval-" + testCase.id(), rosterEntry.role(),
                rosterEntry.accessibleTables(), rosterEntry.allowedActions());
        String chatId = "eval-" + testCase.id();
        long startedAt = System.currentTimeMillis();
        for (String line : testCase.setup()) {
            // setup 的回答不判分：它只是把上下文摆好（多轮记忆那几条靠它）。
            agent.reply(new InboundMessage(principal, chatId, line));
        }
        OutboundMessage answer = agent.reply(new InboundMessage(principal, chatId, testCase.question()));
        long millis = System.currentTimeMillis() - startedAt;

        String turnId = answer.turnId();
        if (turnId == null || turnId.isBlank()) {
            throw new AssertionError("用例 " + testCase.id() + " 的回答没带 turnId。"
                    + "评测靠它读回轨迹，为空说明审计没接上（ADR-0027）");
        }
        String text = answer.text();
        return new CaseResult(testCase, turnId, text,
                EvalScorer.judge(testCase, text, traceOf(turnId)), millis);
    }

    /**
     * 这一轮的工具调用轨迹：读审计里每一次 {@code tool.action.finished}。
     *
     * <p>一次调用一行——审计本来就是"一行 = 一次动作"（ADR-0027）。
     * 以前读 {@code agent_step} 的 {@code tool_calls} 汇总行（逗号串好几个工具），
     * 那个形状分不出"调了两次同一个工具"，随 run 一起没了。
     */
    private List<EvalScorer.Trace> traceOf(String turnId) {
        return jdbc.query(
                "select tool_name, status, error_code from claw4j_run.audit_event"
                        + " where turn_id = ? and action = 'tool.action.finished' order by seq",
                (row, index) -> new EvalScorer.Trace(
                        row.getString("tool_name"),
                        "failed".equals(row.getString("status")),
                        row.getString("error_code")),
                turnId);
    }

    /**
     * 过滤：系统属性 {@code claw4j.eval.filter}，逗号分隔，可以写类别（{@code sales}）
     * 或 case id（{@code sales-weekly-east}）。空表示全跑。
     */
    private static List<EvalCase> select(List<EvalCase> all) {
        List<String> wanted = filter();
        if (wanted.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(testCase -> wanted.contains(testCase.category()) || wanted.contains(testCase.id()))
                .toList();
    }

    private static List<String> filter() {
        String raw = System.getProperty("claw4j.eval.filter", "");
        if (raw.isBlank()) {
            return List.of();
        }
        return Stream.of(raw.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    // ------------------------------------------------------------------ 报告

    /**
     * 一次运行一份 JSON。**能比较，才叫报告**：没有 {@code caseSetDigest} 的两份报告，
     * 分数不同时说不清是"代码变了"还是"用例变了"——而那正是这张票要回答的问题。
     */
    private Path writeReport(List<CaseResult> results) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ranAt", OffsetDateTime.now().toString());
        report.put("commit", git("rev-parse", "--short", "HEAD"));
        report.put("dirty", !git("status", "--porcelain").isBlank());
        report.put("model", modelInfo());
        report.put("caseSetDigest", "sha256:" + caseSetDigest());
        report.put("totals", totals(results));
        report.put("cases", results.stream().map(this::caseRow).toList());
        try {
            Files.createDirectories(REPORT_DIR);
            String name = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now())
                    + "-" + shortCommit() + ".json";
            Path file = REPORT_DIR.resolve(name);
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            return file;
        }
        catch (IOException failure) {
            throw new UncheckedIOException("报告写不进去：" + REPORT_DIR, failure);
        }
    }

    private Map<String, Object> modelInfo() {
        boolean deepseek = ModelProperties.DEEPSEEK.equals(models.getPrimary());
        ModelProperties.Provider provider = deepseek ? models.getDeepseek() : models.getQwen();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("primary", models.getPrimary());
        info.put("model", provider.getModel());
        // 温度进报告：不写的话，两次运行分数不同就说不清是模型抖还是代码改了。
        info.put("temperature", provider.getTemperature());
        return info;
    }

    private static Map<String, Object> totals(List<CaseResult> results) {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("cases", results.size());
        totals.put("answerHit", count(results, EvalVerdict::answerHit));
        totals.put("traceHit", count(results, EvalVerdict::traceHit));
        totals.put("authzHeld", count(results, EvalVerdict::authzHeld));
        totals.put("passed", (int) results.stream().filter(result -> result.verdict().passed()).count());
        return totals;
    }

    private static int count(List<CaseResult> results,
                             java.util.function.Predicate<EvalVerdict> predicate) {
        return (int) results.stream().filter(result -> predicate.test(result.verdict())).count();
    }

    private Map<String, Object> caseRow(CaseResult result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", result.testCase().id());
        row.put("answerHit", result.verdict().answerHit());
        row.put("traceHit", result.verdict().traceHit());
        row.put("authzHeld", result.verdict().authzHeld());
        row.put("failures", result.verdict().failures());
        // 回答原文（截断）进报告：判分是字符串包含，模型换个写法就假红。
        // 报告里没有原文的话，"红了"只能靠重跑一次去猜；有了它，一眼就能看出是不是判分的问题。
        row.put("answer", clip(result.answer()));
        // turnId 留着：报告里的每一条都能回库查那一轮的审计事件，判分有争议时唯一的裁决入口。
        row.put("turnId", result.turnId());
        row.put("millis", result.millis());
        return row;
    }

    /** 报告里一段回答的上限。够看清它答的是什么，又不至于让报告变成散文集。 */
    private static final int ANSWER_CHARS = 600;

    private static String clip(String answer) {
        if (answer == null) {
            return "";
        }
        String oneLine = answer.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= ANSWER_CHARS ? oneLine : oneLine.substring(0, ANSWER_CHARS) + "…";
    }

    /**
     * 用例集内容的哈希：文件名 + 内容，按文件名排序。
     *
     * <p>不哈希"解析后的对象"：那样调整一下缩进、改一句 note 都算"用例变了"，
     * 而 note 不参与判分——哈希该跟着判分口径走。同理也不该漏掉 note：
     * 一条 note 改掉了，人得知道"这次比的是什么"。
     */
    private static String caseSetDigest() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 没有 SHA-256", failure);
        }
        for (String category : EvalCaseSetTest.CATEGORIES) {
            Path file = EvalCaseSetTest.repositoryRoot().resolve("eval/cases/" + category + ".yaml");
            try {
                digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            catch (IOException failure) {
                throw new UncheckedIOException("读不到用例文件 " + file, failure);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String shortCommit() {
        String sha = git("rev-parse", "--short", "HEAD");
        return "unknown".equals(sha) ? "nogit" : sha;
    }

    /** 跑一条 git 只读命令。拿不到就如实说不知道——报告里写个假的 sha 比空着更坏。 */
    private static String git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(EvalCaseSetTest.repositoryRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? output.strip() : "unknown";
        }
        catch (IOException failure) {
            return "unknown";
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    // ------------------------------------------------------------------ 控制台

    /** 只打三行：总数、三类各中多少、不中的那几条。细节在 JSON 里。 */
    private static void print(List<CaseResult> results, Path report) {
        Map<String, Object> totals = totals(results);
        System.out.printf("评测 %s 条，通过 %s 条%n", totals.get("cases"), totals.get("passed"));
        System.out.printf("答案命中 %s/%s，轨迹 %s/%s，越权 %s/%s%n",
                totals.get("answerHit"), totals.get("cases"),
                totals.get("traceHit"), totals.get("cases"),
                totals.get("authzHeld"), totals.get("cases"));
        List<CaseResult> failed = results.stream().filter(result -> !result.verdict().passed()).toList();
        System.out.println(failed.isEmpty()
                ? "没过的一条都没有"
                : "没过的：" + failed.stream().map(result -> result.testCase().id())
                        .collect(Collectors.joining("、")));
        System.out.println("报告：" + report);
    }
}
