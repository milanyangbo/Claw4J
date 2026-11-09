package io.github.claw4j.eval;

import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用例集自洽：**评测集本身进回归**，不调模型、不连库、不烧钱。
 *
 * <p>为什么这一份必须存在：评测集不进 {@code verify.sh}（一百条真模型调用是另一个量级），
 * 而一个不进回归的资产退化成"没人跑的死文件"只是时间问题——ADR-0011 记的那个 P0
 * （26 条数据库测试从未执行却被当成绿）就是这个形状。
 *
 * <p>它把两件事分开：<b>用例写坏了</b>在这里红（便宜、立刻），<b>评测没跑</b>是这张票
 * 承认的局限（写在票面的代价一节，不让它以后变成"意外"）。所以这里只读文件，
 * 断言的是"用例集自洽"，不是"模型答得好"。
 *
 * <p>最值钱的一条是工具名：{@code querySales} 改成 {@code query_sales} 之后，
 * 这里当场红并点名是哪些用例引用了它——而不是等评测跑完发现"全都没命中"，
 * 然后有人去改提示词。
 */
class EvalCaseSetTest {

    /** 按能力分文件：一个文件一类问题。别按工具分——工具会变，问题不会。 */
    static final List<String> CATEGORIES = List.of("sales", "knowledge", "tools", "authz", "memory");

    /** 用例集的规模。少于 50 条时"分数变化"没有统计意义，多于 100 条时一次全跑太慢。 */
    private static final int MIN_CASES = 50;

    private static final int MAX_CASES = 100;

    /**
     * 事实来源：业务种子与知识库文档。
     *
     * <p>用它挡"用例按一个不存在的数写的"。这条很便宜，而它挡的那类错最贵——
     * 从写下的那天起就是红的，看起来却像"模型答错了"。
     */
    private static final List<String> FACT_SOURCES = List.of(
            "claw4j-example-sales/src/main/resources/db/migration/V1__business_schema.sql",
            "claw4j-example-sales/src/main/resources/db/migration/V2__business_seed.sql",
            "examples/knowledge/pricing.md",
            "examples/knowledge/after-sales.md");

    // ------------------------------------------------------------------ 用例集自洽

    @Test
    @DisplayName("五个类别文件都能解析，条数在 50 到 100 之间")
    void everyCaseFileParses() {
        List<EvalCase> cases = cases();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(MIN_CASES).hasSizeLessThanOrEqualTo(MAX_CASES);
        for (String category : CATEGORIES) {
            assertThat(cases.stream().filter(testCase -> testCase.category().equals(category)))
                    .as("类别 %s 一条用例都没有：按能力分文件，缺一类等于那一类没测", category)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("id 全局唯一且与所在文件同类：重名会让两条用例的判定互相覆盖")
    void everyIdIsUniqueAndMatchesItsFile() {
        Set<String> seen = new HashSet<>();
        for (EvalCase testCase : cases()) {
            assertThat(seen.add(testCase.id())).as("重复的用例 id：%s", testCase.id()).isTrue();
            assertThat(testCase.category())
                    .as("用例 %s 的 id 前缀与它所在的文件不同类：报告里按前缀分组，会归错地方", testCase.id())
                    .isIn(CATEGORIES);
        }
    }

    @Test
    @DisplayName("principal 在 principals.yaml 里真实存在：写错名字等于验了一个不存在的身份")
    void everyPrincipalExistsInTheRoster() {
        Set<String> roster = principals().keySet();

        assertThat(cases())
                .allSatisfy(testCase -> assertThat(roster)
                        .as("用例 %s 引用了不存在身份 %s", testCase.id(), testCase.principal())
                        .contains(testCase.principal()));
    }

    @Test
    @DisplayName("expectTools 里的每个名字都真的存在：工具改名时这里红，不是等评测全不命中")
    void everyExpectedToolExistsInTheCatalog() {
        Set<String> catalog = toolNames();

        assertThat(catalog)
                .as("一个 @Tool 都没扫到：扫描器坏了，这条校验会变成恒真")
                .contains("querySales", "systemStatus");
        assertThat(cases()).allSatisfy(testCase -> assertThat(catalog)
                .as("用例 %s 引用了不存在的工具 %s（当前清单：%s）",
                        testCase.id(), testCase.expectTools(), catalog)
                .containsAll(testCase.expectTools()));
    }

    @Test
    @DisplayName("数字锚在事实来源里出现过，或者是 note 里写明推导的合计")
    void everyAnchorIsTraceableToAFactSource() {
        String sources = factSources();
        for (EvalCase testCase : cases()) {
            for (String anchor : testCase.mustMention()) {
                if (sources.contains(anchor.toLowerCase())) {
                    continue;
                }
                // 合计类锚（185900 = 128400 + 26000 + 31500）不在任何一份来源里，这是正常的。
                // 留一条明路而不是删掉这条校验：note 里要写明它由哪些数字加起来，
                // 而那些数字必须都能在来源里找到。凭空写一个数仍然会红。
                assertThat(testCase.note())
                        .as("用例 %s 的锚 %s 既不在事实来源里，note 里也没写推导", testCase.id(), anchor)
                        .isNotNull()
                        .contains(anchor);
                for (String number : numbersIn(testCase.note())) {
                    if (number.equals(anchor)) {
                        continue;
                    }
                    assertThat(sources)
                            .as("用例 %s 的 note 里算进了一个来源里没有的数字 %s", testCase.id(), number)
                            .contains(number.toLowerCase());
                }
            }
        }
    }

    @Test
    @DisplayName("越权用例全引用 guest 这一档：身份集中一份，改它就影响所有引用")
    void refusalCasesUseTheEmptyRoster() {
        List<EvalCase> refusals = cases().stream().filter(EvalCase::expectRefusal).toList();

        assertThat(refusals).isNotEmpty();
        assertThat(refusals).allSatisfy(testCase -> assertThat(testCase.principal())
                .as("越权用例 %s 用了 %s：判据要求越权那一类全部引用 guest", testCase.id(), testCase.principal())
                .isEqualTo("guest"));
        assertThat(principals().get("guest").accessibleTables())
                .as("guest 的表清单必须为空——空表清单一变，上面这批用例验的东西就跟着变")
                .isEmpty();
    }

    // ------------------------------------------------------------------ 校验本身是真的

    @Test
    @DisplayName("省掉 expectTools 会被拒：缺键与'不该调工具'是两件事，后者要显式写 []")
    void aCaseWithoutExpectToolsIsRejected() {
        Map<String, Object> raw = rawCase();
        raw.remove("expectTools");

        assertThatThrownBy(() -> EvalCase.from(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectTools");
    }

    @Test
    @DisplayName("把要点写成自然语言短句会被拒：判分只锚数字与标识符")
    void aProseAnchorIsRejected() {
        Map<String, Object> raw = rawCase();
        raw.put("mustMention", List.of("华东区上周卖得不错"));

        assertThatThrownBy(() -> EvalCase.from(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不像数字或标识符");
    }

    @Test
    @DisplayName("键名敲错会被拒：mustmention 被宽松忽略时，那条用例会静默变成'什么都不判'")
    void anUnknownKeyIsRejected() {
        Map<String, Object> raw = rawCase();
        raw.put("mustmention", List.of("128400"));

        assertThatThrownBy(() -> EvalCase.from(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("认不出的键");
    }

    // ------------------------------------------------------------------ 读取与定位（EvalRunTest 也调这两个）

    /**
     * 仓库根：从工作目录往上找"同时有 eval/cases 与 pom.xml"的那一级。
     *
     * <p>不写死 {@code ../}：surefire 的工作目录是模块目录，但有人在 IDE 里从根跑，
     * 两者差一级。找标记比猜路径可靠。
     */
    static Path repositoryRoot() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("eval/cases")) && Files.exists(directory.resolve("pom.xml"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("找不到仓库根：当前工作目录下没有 eval/cases");
    }

    /** 全部用例，按文件的固定顺序拼起来（报告与过滤都依赖这个顺序）。 */
    static List<EvalCase> cases() {
        List<EvalCase> all = new ArrayList<>();
        for (String category : CATEGORIES) {
            Path file = repositoryRoot().resolve("eval/cases/" + category + ".yaml");
            for (Map<String, Object> raw : readList(file)) {
                all.add(EvalCase.from(raw));
            }
        }
        return List.copyOf(all);
    }

    /** 四档身份。执行器直接拿它造 Principal——评测不经过通道，所以这里就是"通道那一层"。 */
    static Map<String, Principal> principals() {
        Map<String, Object> raw = readMap(repositoryRoot().resolve("eval/cases/principals.yaml"));
        Map<String, Principal> roster = new LinkedHashMap<>();
        raw.forEach((name, value) -> {
            if (!(value instanceof Map<?, ?> definition)) {
                throw new IllegalArgumentException("身份 " + name + " 不是一份映射：" + value);
            }
            roster.put(name, new Principal(name,
                    text(definition, "role"),
                    Set.copyOf(strings(definition, "tables")),
                    Set.copyOf(strings(definition, "actions"))));
        });
        return Map.copyOf(roster);
    }

    // ------------------------------------------------------------------ 解析

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readList(Path file) {
        Object loaded = load(file);
        if (!(loaded instanceof List<?> list)) {
            throw new IllegalArgumentException(file + " 的顶层应当是一个列表");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(file + " 里有一条不是映射：" + item);
            }
            entries.add((Map<String, Object>) map);
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path file) {
        Object loaded = load(file);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(file + " 的顶层应当是一个映射");
        }
        return (Map<String, Object>) map;
    }

    private static Object load(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Map<String, Object> rawCase() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "sales-sample");
        raw.put("question", "上周华东区成交额是多少？");
        raw.put("principal", "analyst");
        raw.put("mustMention", List.of("128400"));
        raw.put("expectTools", List.of("querySales"));
        return raw;
    }

    private static String text(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new IllegalArgumentException("身份缺少 " + key);
        }
        return String.valueOf(value);
    }

    private static List<String> strings(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " 应当是列表：" + value);
        }
        return list.stream().map(String::valueOf).toList();
    }

    // ------------------------------------------------------------------ 事实来源与工具清单

    private static String factSources() {
        StringBuilder text = new StringBuilder();
        for (String relative : FACT_SOURCES) {
            Path file = repositoryRoot().resolve(relative);
            try {
                // 文件名本身也算：knowledge 那几条的锚就是出处名（after-sales.md）。
                text.append(file.getFileName().toString().toLowerCase()).append('\n');
                text.append(Files.readString(file, StandardCharsets.UTF_8).toLowerCase()).append('\n');
            }
            catch (IOException failure) {
                throw new UncheckedIOException("读不到事实来源 " + relative, failure);
            }
        }
        return text.toString();
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static List<String> numbersIn(String text) {
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    /**
     * 当前的工具清单：扫 classpath 上带 {@code @Tool} 方法的类，取注解里的名字（没写就取方法名）。
     *
     * <p>为什么不用容器里的 {@code ToolCatalog}：那要起应用、连库、连模型，而这条校验的
     * 全部价值在于"便宜到每天都跑"。代码里的 {@code @Tool} 是真的来源——容器里那份清单
     * 也是从它组出来的，档位与 allow/deny 只是过滤。
     *
     * <p><b>它只覆盖注解式工具，这是一个已知边界</b>：几个工具（定时任务、计划、会话回忆）
     * 是手工构造 {@code ToolDefinition} 的，没有 {@code @Tool} 注解，扫不到。
     * 现在用例里只引用 {@code querySales}，它在覆盖范围内（知识库曾经的 {@code searchDocs}
     * 在 2026-11-09 改由 {@code RetrievalAugmentationAdvisor} 自动检索，工具已删除）；
     * 以后要给手工构造的那几个写用例，得先把它们加进这份清单（或者改用具名常量登记），
     * 否则这里会报"工具不存在"——报错里带着当前清单，一眼能看出是扫描边界的问题。
     *
     * <p>加载类时<b>不初始化</b>（{@code Class.forName(name, false, loader)}）：
     * 这只是一次反射读注解，不该顺带跑别人的静态初始化块。
     */
    private static Set<String> toolNames() {
        Set<String> names = new TreeSet<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:io/github/claw4j/**/*.class");
        }
        catch (IOException failure) {
            throw new UncheckedIOException("扫不到 classpath 上的类", failure);
        }
        ClassLoader loader = EvalCaseSetTest.class.getClassLoader();
        for (Resource resource : resources) {
            String className = classNameOf(resource);
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            }
            catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
                // 扫到编译产物里回不去的类（被删的源文件、可选依赖缺失）时跳过：
                // 它们是别人的类，不该让"用例集自洽"这条校验连带红。
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                names.add(tool.name().isBlank() ? method.getName() : tool.name());
            }
        }
        return names;
    }

    private static String classNameOf(Resource resource) {
        String url;
        try {
            url = resource.getURL().toString();
        }
        catch (IOException failure) {
            return null;
        }
        int start = url.indexOf("io/github/claw4j/");
        if (start < 0 || !url.endsWith(".class")) {
            return null;
        }
        String relative = url.substring(start, url.length() - ".class".length());
        String className = relative.replace('/', '.');
        // 内部类与匿名类不带 @Tool（注解在方法上，扫到外层类就够了）。
        return className.contains("$") ? null : className;
    }
}
