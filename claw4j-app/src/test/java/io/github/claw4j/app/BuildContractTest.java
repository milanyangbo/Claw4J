package io.github.claw4j.app;

import io.github.claw4j.agent.session.SessionMemoryProperties;
import io.github.claw4j.agent.session.SessionScopeProperties;
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
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
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
 * 四条构建与装配的契约：Java 侧不自己拼命令行（ADR-0002）、会话 schema 只在一个地方写死、
 * 跨 jar 的迁移合成一套 schema、插件声明的核心区间必须对得上（P16）。
 *
 * <p>合成一份是因为它们问的是同一类问题：那些不会在运行时报错、只在某天以另一种形态暴露的约定。
 * 手写 shell 的代价是宿主机交出去；写死会话 schema 的代价是改一次配置、一半功能继续读旧表；
 * 迁移版本号撞车的代价是少建一张表而启动日志完全正常；插件版本对不上的代价是某次调用突然
 * {@code NoSuchMethodError}，日志里没有一行提到版本。
 *
 * <p>合并还有名额上的原因：{@code claw4j-app} 卡在 10 个测试文件，P13 要给 Slack 的真模型闭环
 * 留一个位置，而 {@code claw4j-capability} 的文件名额已经满了。
 *
 * <p>只能在 app 模块：别的模块的架构测试只扫得见自己，而前两条都要扫全部模块，一条扫所有
 * {@code src/main/java}，一条扫 classpath 上所有 {@code db/migration}；第三条守的装配本身
 * 也发生在 app。
 */
@Testcontainers
@AnalyzeClasses(packages = "io.github.claw4j", importOptions = ImportOption.DoNotIncludeTests.class)
class BuildContractTest {

    // ---------------------------------------------------------------- 不起进程（ADR-0002）

    /**
     * 全仓仅有的两个被允许起进程的类。
     *
     * <p>{@code LocalProcessRunner} 收 argv，脚本包走这条；{@code ShellCommandRunner} 把命令交给
     * {@code /bin/sh -c}，{@code exec} 走这条。区别在命令行是谁给的：包清单给的是 argv，
     * 模型给的是命令。加第三个之前先想清楚超时、目录锁、干净环境这三件事还成不成立。
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
     * 通用 shell 工具（{@code spring-ai-agent-utils} 的 {@code ShellTools}：{@code Bash}、
     * {@code BashOutput}、{@code KillShell}）必须带着沙箱后端出生。
     *
     * <p>不许依赖它不是目的：通用 shell 是正经能力。要拦的是裸造，{@code ShellTools.builder().build()}
     * 不给 backend 时默认 {@code LocalExecBackend}，直接拼 {@code /bin/bash -c}，把 env 白名单、
     * 非 root、命令白名单一次作废。而那条 {@code ProcessBuilder} 规则看不见它：类在 jar 里，
     * 代码里没有任何进程对象的构造。
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
     * 会命令解释器的写法。shell 会把参数里的分号当成第二条命令，于是传一个参数变成了执行一段脚本。
     * 注释里也不许出现：扫的是整份文件，区分代码与注释要写解析器，那份代价比注释换个说法贵得多。
     *
     * <p>唯一豁免是 {@code ShellCommandRunner}。它的全部内容就是把命令交给 {@code /bin/sh -c}，
     * 而那条命令是模型给的、已经过了白名单与工作区边界。豁免的是这一个文件名，不是这批字面量，
     * 多一个文件想出现 {@code bash -c} 就得先改这里，那一步和要不要再开一个 shell 入口是同一个决定。
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
     * <p>{@code claw4j.session.schema} 是可配的，而跑在主连接池上的手写 SQL（会话指针、会话目标、
     * 清理任务、跨会话召回）不能靠连接的 {@code search_path}，只能写全限定表名。每写死一处，
     * 改配之后就是框架往新 schema 写、这一处读旧的，而症状不是报错，是指针永远换不出来、
     * 目标读不到、一行都没清出去。
     *
     * <p>规则覆盖全部四处，而不只是最近被修的那一处：同一份代码里两种行为并存比全都写死更难发现，
     * 因为它看起来已经修过了。
     *
     * <p>只查 {@code src/main/java}，测试里写全限定名是正常的；注释行不查，它不参与执行。
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

    @Test
    @DisplayName("窗口写在 application.yml 的 claw4j.session 下：换主模型的人改这一行，不是去翻代码")
    void applicationYmlDeclaresTheContextWindow() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(sources).as("application.yml 得能解析出来").isNotEmpty();
        // 键的层级也在断言里：写到 claw4j.session 之外就绑不到 SessionMemoryProperties，
        // 而那种错在运行期只表现为"配置写了、看起来配过了、其实没生效"。
        assertThat(sources.getFirst().getProperty("claw4j.session.context-window"))
                .as("这一行是换模型时唯一能被搜到的地方；删掉它不会有别的用例变红，"
                        + "而窗口会悄悄退回代码默认值")
                .isEqualTo(SessionMemoryProperties.DEFAULT_CONTEXT_WINDOW);
    }

    @Test
    @DisplayName("两个 scope 写在 application.yml 的 claw4j.session 下：多人部署要改这里")
    void applicationYmlDeclaresTheSessionScopes() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(sources).as("application.yml 得能解析出来").isNotEmpty();
        assertThat(sources.getFirst().getProperty("claw4j.session.dm-scope"))
                .as("这一行决定私聊共不共用一段上下文。删掉它默认值仍是 main，"
                        + "但读配置的人就看不到「多人可及必须改成 per-channel-peer」（ADR-0029）")
                .isEqualTo(SessionScopeProperties.DM_MAIN);
        assertThat(sources.getFirst().getProperty("claw4j.session.group-scope"))
                .as("群聊按群隔离是默认；写出来是为了让「群为什么不共享」有地方可查")
                .isEqualTo(SessionScopeProperties.GROUP_PER_GROUP);
    }

    // ---------------------------------------------------------------- 迁移合成一套 schema

    /** 和 {@code SearchSqlTest} 用同一个镜像：{@code V5} 要 {@code create extension vector}。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Test
    @DisplayName("跨 jar 的迁移按版本号合并：八份基线全跑到，五个 schema 都在")
    void migrationsFromEveryModuleLandInOneHistory() {
        var flyway = flyway();
        flyway.migrate();

        // 断言跑完之后历史里有什么，而不是这一次执行了几条：同一份容器被两条用例共用，
        // 谁先跑都会把迁移做完。
        //
        // 没有 4：V4 原来是语义缓存，那个能力改落 Redis 之后整张表连同 schema 一起删了，
        // 编号不复用（与 ADR 编号同一个口径）。
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "5", "6", "7", "8", "9");

        var jdbc = new JdbcTemplate(dataSource());

        // 五个 schema 各自的归属：business 是示例插件的，其余是 agent 的（会话 claw4j_session、
        // 调度/审批/审计在 claw4j_run、知识库在 claw4j_rag、入站队列在 claw4j_channel）。
        // 缓存不在其中：它一旦重新出现在这里，就是有人又把缓存往库里写了。
        // 入站队列单独一个是因为它跟着通道走（V7）：它是平台把消息交给宿主这件事的账，
        // 与一次 agent 运行没有父子关系。
        assertThat(jdbc.queryForList(
                "select schema_name from information_schema.schemata"
                        + " where schema_name in ('business', 'claw4j_session', 'claw4j_cache',"
                        + " 'claw4j_run', 'claw4j_rag', 'claw4j_channel')"
                        + " order by schema_name", String.class))
                .containsExactly("business", "claw4j_channel", "claw4j_rag",
                        "claw4j_run", "claw4j_session");

        // 会话 schema 里是框架的两张加 V8 那六张。V9 把旧的 session_entry 删了，所以这里不再有它：
        // 它要是回来了，说明有人绕过 V8 又摊了一张平表。
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = 'claw4j_session' order by table_name", String.class))
                .containsExactly("ai_session", "ai_session_event", "conversations",
                        "session_conversations", "session_entry_snapshots",
                        "session_key_contract", "session_nodes", "session_windows");

        // V1 的种子真的落了（示例插件那份 DDL 也在同一批迁移里执行，不是被跳过）。
        assertThat(jdbc.queryForObject("select count(*) from business.sales_order", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("重复跑不重复执行：第二次 migrate 执行 0 条")
    void secondRunIsANoOp() {
        var flyway = flyway();
        flyway.migrate();

        // 7 个文件分布在不同 jar 里，重复扫描最容易出现的是"某个 jar 里的又被当成新的"。
        assertThat(flyway.migrate().migrationsExecuted)
                .as("第二次调用不该再执行任何迁移")
                .isZero();
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).load();
    }

    /**
     * 不给 locations：走 Spring Boot 的默认值 {@code classpath:db/migration}，也就是所有 jar 里
     * 同名目录的并集，这正是要验的那条路径。
     */
    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // ---------------------------------------------------------------- 插件版本契约（P16）

    /**
     * 现场造 classpath 的根。每条用例一个目录，JUnit 跑完就删。
     *
     * <p>造 jar 而不是只造目录，是这组判据的重点：插件在另一个 jar 里。目录形态在开发期就能
     * 跑通，jar 形态失败的方式不一样，扫不到资源是静默空结果，什么都没发生看起来和全部通过
     * 一模一样。
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
