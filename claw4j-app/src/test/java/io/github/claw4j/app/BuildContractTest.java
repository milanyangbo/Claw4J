package io.github.claw4j.app;

import io.github.claw4j.plugin.CoreVersion;
import io.github.claw4j.plugin.PluginContract;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四条"构建与装配"的契约：Java 侧不自己拼命令行（ADR-0002）、会话 schema 只在一个地方写死、
 * 跨 jar 的迁移合成一套 schema、以及插件声明的核心区间必须对得上（P16）。
 *
 * <p><b>为什么合成一份</b>：它们本来各占一个文件，但问的是同一类问题——<b>那些不会在运行时
 * 报错、只在某天以另一种形态暴露的约定</b>。手写 shell 的代价是"1.x 把宿主机交出去"；
 * 写死会话 schema 的代价是"改一次配置，一半功能继续读旧表"；迁移版本号撞车的代价是
 * "少建一张表，而启动日志完全正常"；插件版本对不上的代价是
 * "某次调用突然 {@code NoSuchMethodError}"，日志里没有一行提到版本。四者都只能靠在构建与
 * 启动期钉住。</p>
 *
 * <p>合并的直接原因是硬约束：{@code claw4j-app} 与别处一样卡在 10 个测试文件，
 * P13 要给 Slack 的真模型闭环留一个位置。第三条（P16）进来时用的就是这些空位——
 * 它守的是同一个家族的东西，而 {@code claw4j-capability} 的文件名额已经满了。</p>
 *
 * <p><b>它们只能在 app 模块</b>：别的模块的架构测试只扫得见自己，而前两条都要扫全部模块
 * （前者要扫所有 {@code src/main/java}，后者要扫 classpath 上所有 {@code db/migration}）；
 * 第三条守的装配本身也发生在 app。</p>
 */
@Testcontainers
@AnalyzeClasses(packages = "io.github.claw4j", importOptions = ImportOption.DoNotIncludeTests.class)
class BuildContractTest {

    // ---------------------------------------------------------------- 不起进程（ADR-0002）

    /**
     * 全仓仅有的两个被允许起进程的类。
     *
     * <p>{@code LocalProcessRunner} 收 argv（脚本包走这条）；{@code ShellCommandRunner} 把命令交给
     * {@code /bin/sh -c}（{@code exec} 走这条，票 01）。区别在**这条命令行是谁给的**：
     * 包清单给的是 argv，模型给的是命令。加第三个之前先想清楚"超时、目录锁、干净环境"这三句话还成不成立。
     */
    private static final String PROCESS_SPAWNER = "io.github.claw4j.capability.process.LocalProcessRunner";

    private static final String SHELL_SPAWNER = "io.github.claw4j.capability.process.ShellCommandRunner";

    /**
     * 用 {@code callMethodWhere} 而不是 {@code callMethod(Runtime.class, "exec")}：
     * 后者第三个参数是可变形参类型，写 {@code "exec"} 等于"无参的 exec"，而它没有无参重载——
     * 规则不报错，只是永远匹配不上。那种"写了却从没生效"的规则比没有规则更糟。
     */
    private static final DescribedPredicate<JavaMethodCall> RUNTIME_EXEC =
            DescribedPredicate.describe("调用 java.lang.Runtime 的 exec 起进程",
                    call -> "exec".equals(call.getTarget().getName())
                            && "java.lang.Runtime".equals(call.getTarget().getOwner().getName()));

    @ArchTest
    static final ArchRule java_side_must_not_spawn_processes =
            noClasses().that().resideInAPackage("io.github.claw4j..")
                    .and().doNotHaveFullyQualifiedName(PROCESS_SPAWNER)
                    .and().doNotHaveFullyQualifiedName(SHELL_SPAWNER)
                    .should().callMethodWhere(RUNTIME_EXEC)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                    .because("起进程只发生在 " + PROCESS_SPAWNER + " 与 " + SHELL_SPAWNER
                            + "，别处不自己拼命令行（ADR-0002）");

    /**
     * 通用 shell 工具（{@code spring-ai-agent-utils} 的 {@code ShellTools}：{@code Bash} /
     * {@code BashOutput} / {@code KillShell}）**必须带着我们的沙箱后端出生**。
     *
     * <p><b>为什么是"只许在一个地方造它"而不是"不许依赖它"</b>：通用 shell 是正经能力（票 01），
     * 我们用的就是它。真正要拦的是**裸造**——{@code ShellTools.builder().build()} 不给 backend 时
     * 默认 {@code LocalExecBackend}，直接拼 {@code /bin/bash -c}，把 env 白名单、非 root、命令白名单
     * 一次作废。而上面那条 {@code ProcessBuilder} 规则看不见它：类在 jar 里，我们不 new 任何进程对象。
     *
     * <p>于是规则收窄成一个点名：装配类亲手造它，造的时候塞的是 {@code SandboxedExecBackend}。
     */
    private static final String SHELL_TOOLS = "org.springaicommunity.agent.tools.ShellTools";

    private static final String SHELL_TOOLS_ASSEMBLY = "io.github.claw4j.capability.exec.ExecAutoConfiguration";

    /** {@code ShellTools.builder()} 与 {@code new ShellTools(...)} 都算"造"：两条路都能拿到裸后端。 */
    private static final DescribedPredicate<JavaMethodCall> BUILDS_SHELL_TOOLS =
            DescribedPredicate.describe("调用 ShellTools.builder()",
                    call -> "builder".equals(call.getTarget().getName())
                            && SHELL_TOOLS.equals(call.getTarget().getOwner().getName()));

    private static final DescribedPredicate<JavaConstructorCall> CONSTRUCTS_SHELL_TOOLS =
            DescribedPredicate.describe("new ShellTools(...)",
                    call -> SHELL_TOOLS.equals(call.getTarget().getOwner().getName()));

    @ArchTest
    static final ArchRule shell_tools_must_be_born_with_our_backend =
            noClasses().that().resideInAPackage("io.github.claw4j..")
                    .and().doNotHaveFullyQualifiedName(SHELL_TOOLS_ASSEMBLY)
                    .should().callMethodWhere(BUILDS_SHELL_TOOLS)
                    .orShould().callConstructorWhere(CONSTRUCTS_SHELL_TOOLS)
                    .because("ShellTools 只许在 " + SHELL_TOOLS_ASSEMBLY + " 里造："
                            + "别处造它默认拿到 LocalExecBackend，env 白名单与命令白名单一次作废（票 01）");

    /**
     * 会命令解释器的写法。shell 会把参数里的分号当成第二条命令，
     * 于是"传一个参数"变成了"执行一段脚本"。注释里也不许出现——扫的是整份文件，
     * 区分代码与注释要写解析器，那份代价比"注释换个说法"贵得多。
     *
     * <p><b>唯一豁免：{@code ShellCommandRunner}</b>（票 01）。它的全部内容就是"把命令交给
     * {@code /bin/sh -c}"，而那条命令是模型给的、已经过了白名单与工作区边界。
     * 豁免的是这一个文件名，不是这批字面量——多一个文件想出现 {@code bash -c} 就得先改这里，
     * 那一步和"要不要再开一个 shell 入口"是同一个决定。
     */
    private static final List<String> SHELL_INTERPRETERS =
            List.of("sh -c", "bash -c", "/bin/sh", "/bin/bash", "cmd.exe");

    private static final String SHELL_ENTRY = "ShellCommandRunner.java";

    /** 会话 schema 的默认名。带点号才是"被拼进了表名"，不带点号的是那个常量自己。 */
    private static final String SESSION_SCHEMA_LITERAL = "claw4j_session.";

    /** 唯一允许持有它的文件：schema 名与"怎么拼进 SQL"都在这里定义。 */
    private static final String SESSION_SCHEMA_HOLDER = "SessionSchema.java";

    @Test
    @DisplayName("各模块主代码里没有手写的 shell 调用")
    void noHandWrittenShellInvocations() throws IOException {
        Path repository = Path.of("..").toRealPath();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repository)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                if (!module.getFileName().toString().startsWith("claw4j-")) {
                    continue;
                }
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        if (file.getFileName().toString().equals(SHELL_ENTRY)) {
                            continue;
                        }
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        for (String interpreter : SHELL_INTERPRETERS) {
                            if (content.contains(interpreter)) {
                                offenders.add(repository.relativize(file) + " 里出现了 " + interpreter);
                            }
                        }
                    }
                }
            }
        }

        assertThat(offenders).isEmpty();
    }

    /**
     * 会话 schema 的字面量只许出现在 {@link io.github.claw4j.agent.session.SessionSchema} 里。
     *
     * <p><b>为什么要有这条</b>：{@code claw4j.session.schema} 是可配的，而跑在主连接池上的
     * 手写 SQL（会话指针、会话目标、清理任务、跨会话召回）不能靠连接的 {@code search_path}，
     * 只能写全限定表名。每写死一处，改配之后就是"框架往新 schema 写、这一处读旧的"。
     * 症状不是报错，是功能悄悄失效：指针永远换不出来、目标读不到、一行都没清出去。
     *
     * <p><b>它来自哪次事故</b>：票 05 只把跨会话召回那一处改成读配置，
     * 另外三处（共 17 个表名）仍然写死。同一份代码里两种行为并存比"全都写死"更难发现，
     * 因为它看起来已经修过了。
     *
     * <p>只查 {@code src/main/java}：测试里写全限定名是正常的（真库要建表、要 truncate）。
     * 注释行不查——那里写的是"读起来像什么"，不参与执行；为了它写一个词法解析器不值。
     */
    @Test
    @DisplayName("会话 schema 的字面量只许留在 SessionSchema：手写 SQL 一律从配置取")
    void sessionSchemaLiteralLivesInOnePlace() throws IOException {
        Path repository = Path.of("..").toRealPath();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repository)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                if (!module.getFileName().toString().startsWith("claw4j-")) {
                    continue;
                }
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        if (file.getFileName().toString().equals(SESSION_SCHEMA_HOLDER)) {
                            continue;
                        }
                        collectSchemaLiterals(repository, file, offenders);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("这些地方写死了会话 schema，改配之后它们继续指向旧 schema，"
                        + "而症状是功能悄悄失效——不是报错")
                .isEmpty();
    }

    /** 逐行找，跳过注释行。注释不执行，为它写解析器的代价比"换个说法"贵。 */
    private static void collectSchemaLiterals(Path repository, Path file, List<String> offenders)
            throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                continue;
            }
            if (line.contains(SESSION_SCHEMA_LITERAL)) {
                offenders.add(repository.relativize(file) + ":" + (i + 1));
            }
        }
    }

    // ---------------------------------------------------------------- 迁移合成一套 schema

    /** 和 {@code SearchSqlTest} 用同一个镜像：{@code V5} 要 {@code create extension vector}。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Test
    @DisplayName("跨 jar 的迁移按版本号合并：六份基线全跑到，五个 schema 都在")
    void migrationsFromEveryModuleLandInOneHistory() {
        var flyway = flyway();
        flyway.migrate();

        // 断言"跑完之后历史里有什么"，而不是"这一次执行了几条"——同一份容器被两条用例共用，
        // 谁先跑都会把迁移做完，盯着 migrationsExecuted 会变成一条看执行顺序的用例。
        //
        // **没有 4**：V4 原来是语义缓存，那个能力改落 Redis 之后整张表连同 schema 一起删了，
        // 编号不复用（与 ADR 编号同一个口径）——它下面那个位置放的是已经不存在的东西。
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "5", "6", "7");

        var jdbc = new JdbcTemplate(dataSource());

        // 五个 schema 各自的归属：business 是示例插件的，其余是 agent 的
        // （会话 claw4j_session、调度/审批/审计在 claw4j_run、知识库在 claw4j_rag、
        // 入站队列在 claw4j_channel）。**缓存不在其中**：语义缓存 2026-11-09 改落 Redis Stack，
        // 那张表在基线里已经不存在（整个 claw4j_cache schema 都没了）；它一旦重新出现，
        // 就是有人又把缓存往库里写了。
        // 入站队列单独一个是因为它跟着**通道**走（V7）：它是"平台把消息交给了我们"这件事的账，
        // 与一次 agent 运行没有父子关系。
        assertThat(jdbc.queryForList(
                "select schema_name from information_schema.schemata"
                        + " where schema_name in ('business', 'claw4j_session', 'claw4j_cache',"
                        + " 'claw4j_run', 'claw4j_rag', 'claw4j_channel')"
                        + " order by schema_name", String.class))
                .containsExactly("business", "claw4j_channel", "claw4j_rag",
                        "claw4j_run", "claw4j_session");

        // 会话 schema 里只剩三张表（票 06）：目标与指针并成了 session_entry。
        // 这条断言盯的是"合并真的发生了"，而不是"新表建出来了"——
        // 两张旧表还在的话，`/new` 那条路又会退回到"段换了、目标还挂着"。
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = 'claw4j_session' order by table_name", String.class))
                .containsExactly("ai_session", "ai_session_event", "session_entry");

        // V1 的种子真的落了（示例插件那份 DDL 也在同一批迁移里执行，不是被跳过）。
        assertThat(jdbc.queryForObject("select count(*) from business.sales_order", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("重复跑不重复执行：第二次 migrate 执行 0 条")
    void secondRunIsANoOp() {
        var flyway = flyway();
        flyway.migrate();

        // 6 个文件分布在不同 jar 里，重复扫描最容易出现的是"某个 jar 里的又被当成新的"。
        assertThat(flyway.migrate().migrationsExecuted)
                .as("第二次调用不该再执行任何迁移")
                .isZero();
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).load();
    }

    /**
     * 不给 locations：走 Spring Boot 的默认值 `classpath:db/migration`，
     * 也就是"所有 jar 里同名目录的并集"——这正是要验的那条路径。
     */
    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // ---------------------------------------------------------------- 插件版本契约（P16）

    /**
     * 现场造 classpath 的根。每条用例一个目录，JUnit 跑完就删。
     *
     * <p>造 jar 而不是只造目录，是这组判据的重点：**插件在另一个 jar 里**。
     * 目录形态在开发期就能跑通，jar 形态失败的方式不一样——扫不到资源是静默空结果，
     * 于是"什么都没发生"看起来和"全部通过"一模一样。
     */
    @TempDir
    Path temp;

    private static final String CORE_VERSION = "0.1.0-SNAPSHOT";

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("插件声明的区间匹配时照常启动：只写一行 info，不打 WARN")
    void aMatchingDeclarationStartsQuietly(CapturedOutput output) throws IOException {
        // 不抛只是结论的一半：还要证明它真走了成功分支，否则"没有 WARN"只是因为什么都没发生
        gate(coreVersion("core-ok"), descriptorDir("plugin-ok", "sales", ">=0.1.0 <0.2.0"))
                .postProcessBeanFactory(null);

        assertThat(output.toString()).contains("插件版本契约成立");
        assertThat(output.toString()).as("匹配路径上不该有 WARN").doesNotContain("WARN");
    }

    @Test
    @DisplayName("不匹配就起不来，一句话说完：插件名、区间、当前核心、来源都在")
    void aMismatchStopsStartupAndSaysEverything() throws IOException {
        Path plugin = descriptorJar("plugin-bad", "sales", ">=0.2.0");

        assertThatThrownBy(() -> gate(coreVersion("core-bad"), plugin).postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("插件 sales")
                .hasMessageContaining(">=0.2.0")
                .hasMessageContaining(CORE_VERSION)
                .hasMessageContaining("sales.jar");
    }

    @Test
    @DisplayName("两个插件都不匹配时一次全报：升核心的人不想修一个重启一次")
    void twoMismatchesAreReportedTogether() throws IOException {
        Path sales = descriptorJar("plugin-two-sales", "sales", ">=0.2.0");
        Path jira = descriptorJar("plugin-two-jira", "jira", ">=0.3.0");

        assertThatThrownBy(() -> gate(coreVersion("core-two"), sales, jira).postProcessBeanFactory(null))
                .hasMessageContaining("插件 sales")
                .hasMessageContaining("插件 jira");
    }

    @Test
    @DisplayName("写错的比较符是硬失败：=>0.1.0 不能被当成没条件")
    void aMalformedRangeStopsStartup() throws IOException {
        Path plugin = descriptorDir("plugin-typo", "sales", "=>0.1.0");

        assertThatThrownBy(() -> gate(coreVersion("core-typo"), plugin).postProcessBeanFactory(null))
                .hasMessageContaining("读不成一个区间")
                .hasMessageContaining("=>0.1.0");
    }

    @Test
    @DisplayName("缺 requires-core 也是硬失败：不按任意版本兜底")
    void aDescriptorWithoutTheKeyStopsStartup() throws IOException {
        Path meta = Files.createDirectories(temp.resolve("plugin-nokey").resolve("META-INF"));
        Files.writeString(meta.resolve("claw4j-plugin.properties"), "name=sales\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> gate(coreVersion("core-nokey"), temp.resolve("plugin-nokey"))
                .postProcessBeanFactory(null))
                .hasMessageContaining("没有声明 " + PluginContract.KEY_REQUIRES_CORE)
                // 缺键时也得说清是哪一份文件缺的——不然"哪个插件"要靠人去 classpath 里翻
                .hasMessageContaining("plugin-nokey");
    }

    @Test
    @DisplayName("核心版本读不到就失败：读不到当成已知，是这类闸最常见的死法")
    void aMissingCoreVersionStopsStartup() throws IOException {
        Path plugin = descriptorDir("plugin-nocore", "sales", ">=0.1.0 <0.2.0");

        assertThatThrownBy(() -> gate(plugin).postProcessBeanFactory(null))
                .hasMessageContaining(PluginContractGate.CORE_VERSION_RESOURCE);
    }

    @Test
    @DisplayName("一个描述文件都没有时照常启动：宿主自己的模块就是这样")
    void anEmptyClasspathBootsFine() throws IOException {
        gate(coreVersion("core-empty")).postProcessBeanFactory(null);
    }

    @Test
    @DisplayName("这道闸确实被注册进了容器，而且是以 BeanFactoryPostProcessor 的身份")
    void theAutoConfigurationRegistersTheGateAsABeanFactoryPostProcessor() throws NoSuchMethodException {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PluginContractAutoConfiguration.class))
                .run(context -> assertThat(context.getBeansOfType(BeanFactoryPostProcessor.class).values())
                        .as("配置类里得真的产出一个 BFPP 类型的 bean")
                        .anyMatch(PluginContractGate.class::isInstance));

        assertThat(Modifier.isStatic(PluginContractAutoConfiguration.class
                .getDeclaredMethod("pluginContractGate").getModifiers()))
                .as("static 不是为了让闸生效（非静态也生效），是为了不让容器为拿到它而提前实例化配置类")
                .isTrue();
    }

    private PluginContractGate gate(Path... roots) {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            try {
                urls[i] = roots[i].toUri().toURL();
            } catch (IOException broken) {
                throw new IllegalStateException(broken);
            }
        }
        return new PluginContractGate(new PathMatchingResourcePatternResolver(new URLClassLoader(urls, null)));
    }

    private Path coreVersion(String directory) throws IOException {
        Path meta = Files.createDirectories(temp.resolve(directory).resolve("META-INF"));
        Files.writeString(meta.resolve("claw4j-core.properties"),
                "version=" + CORE_VERSION + "\n", StandardCharsets.UTF_8);
        return temp.resolve(directory);
    }

    private Path descriptorDir(String directory, String name, String requiresCore) throws IOException {
        Path meta = Files.createDirectories(temp.resolve(directory).resolve("META-INF"));
        Files.writeString(meta.resolve("claw4j-plugin.properties"),
                descriptor(name, requiresCore), StandardCharsets.UTF_8);
        return temp.resolve(directory);
    }

    private Path descriptorJar(String directory, String name, String requiresCore) throws IOException {
        Path root = Files.createDirectories(temp.resolve(directory));
        Path jar = root.resolve(name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("META-INF/claw4j-plugin.properties"));
            jarOut.write(descriptor(name, requiresCore).getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    private static String descriptor(String name, String requiresCore) {
        return "name=" + name + System.lineSeparator()
                + PluginContract.KEY_REQUIRES_CORE + "=" + requiresCore + System.lineSeparator();
    }
}
