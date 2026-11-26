package io.github.claw4j.app;

import io.github.claw4j.agent.audit.AuditAction;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.AuditLedger;
import io.github.claw4j.agent.audit.JdbcDecisionFacts;
import io.github.claw4j.domain.port.AuditQuery;
import io.github.claw4j.agent.audit.JdbcAuditQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 五条构建与装配的契约：Java 侧不自己拼命令行（ADR-0002）、会话 schema 只在一个地方写死、
 * 跨 jar 的迁移合成一套 schema、插件声明的核心区间必须对得上（P16）、
 * 编解码只有一份 Jackson（3.x，ADR-0035）。
 *
 * <p>合成一份是因为它们问的是同一类问题：那些不会在运行时报错、只在某天以另一种形态暴露的约定。
 * 手写 shell 的代价是宿主机交出去；写死会话 schema 的代价是改一次配置、一半功能继续读旧表；
 * 迁移版本号撞车的代价是少建一张表而启动日志完全正常；插件版本对不上的代价是某次调用突然
 * {@code NoSuchMethodError}，日志里没有一行提到版本；两套 Jackson 并存的代价是每一处解析失败
 * 都要判两遍，而"我们用的是另一个 Jackson"这类理由听上去永远对。
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
     * 全仓没有一个类允许起进程（2026-11-18 起零例外）。
     *
     * <p>脚本层的 argv 执行器（{@code LocalProcessRunner}）随脚本层一起删了——脚本退化成技能，
     * 由模型用 {@code Bash} 去跑；{@code exec} 的宿主后端是库的 {@code LocalExecBackend}、
     * 容器后端是库的 {@code DockerCliExecBackend}，两者都在 jar 里，这条规则看不见也不需要看见。
     * 想再开一个例外之前，先读 {@code docs/reviews/2026-11-18-open-source-inventory.md} 与
     * {@code .scratch/oss-alignment/issues/02-script-packages-degrade-to-skills.md}。
     */
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
                    .should().callMethodWhere(RUNTIME_EXEC)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                    .because("本仓不再起进程：脚本层已退化成技能，exec 的两条后端都在库的 jar 里（ADR-0002）");

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
     * <p>唯一的豁免文件<b>不拼命令行</b>：它只是把解释器名字交给库的 builder
     * （{@code LocalExecBackend.builder().shellCommand("/bin/sh", "-c")}），起进程发生在 jar 里。
     * （2026-11-18 之前这里有两个名额：{@code ShellCommandRunner} 与 {@code LocalProcessRunner}
     * 随后被删；生成器的自测沙箱也退出去了——整层删了。）
     * 豁免的是文件名，不是这批字面量——多一个文件想出现 {@code bash -c} 就得先改这里，
     * 那一步和要不要再开一个 shell 入口是同一个决定。
     */
    private static final List<String> SHELL_INTERPRETERS =
            List.of("sh -c", "bash -c", "/bin/sh", "/bin/bash", "cmd.exe");

    private static final java.util.Set<String> SHELL_ENTRY_FILES =
            java.util.Set.of("ExecAutoConfiguration.java");

    /** 会话 schema 的默认名。带点号才是"被拼进了表名"，不带点号的是那个常量自己。 */
    private static final String SESSION_SCHEMA_LITERAL = "claw4j_session.";

    /** 唯一允许持有它的文件：schema 名与"怎么拼进 SQL"都在这里定义。 */
    private static final String SESSION_SCHEMA_HOLDER = "SessionSchema.java";

    /**
     * Jackson 2 的 databind 包名。
     *
     * <p>{@code com.fasterxml.jackson.core} 那一层不禁：{@code openai-java-core} 自己用它解析 HTTP
     * 响应体，{@code ModelFailures} 认它的 {@code JsonProcessingException} 是认第三方 SDK 的异常类型，
     * 不是我们 import 它的 databind。禁的是"我们自己的代码拿它当编解码库"。
     */
    private static final String JACKSON_TWO_DATABIND = "com.fasterxml.jackson.databind";

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
                        if (SHELL_ENTRY_FILES.contains(file.getFileName().toString())) {
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
     * <p>{@code claw4j.advisor.session.schema} 是可配的，而跑在主连接池上的手写 SQL（会话指针、会话目标、
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
    @DisplayName("各模块主代码里没有 Jackson 2 的 databind：编解码只有 tools.jackson 一份")
    void noJacksonTwoDatabindImports() throws IOException {
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
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i).strip();
                            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                                continue;
                            }
                            if (line.contains(JACKSON_TWO_DATABIND)) {
                                offenders.add(repository.relativize(file) + ":" + (i + 1));
                            }
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("这些地方还在用 Jackson 2 的 databind：Spring AI 抛的是 Jackson 3，"
                        + "两套并存的代价是每一处解析失败都要判两遍")
                .isEmpty();
    }

    @Test
    @DisplayName("窗口写在 application.yml 的 claw4j.advisor.session 下：换主模型的人改这一行，不是去翻代码")
    void applicationYmlDeclaresTheContextWindow() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(sources).as("application.yml 得能解析出来").isNotEmpty();
        // 键的层级也在断言里：写到 claw4j.advisor.session 之外就绑不到 SessionMemoryProperties，
        // 而那种错在运行期只表现为"配置写了、看起来配过了、其实没生效"。
        assertThat(sources.getFirst().getProperty("claw4j.advisor.session.context-window"))
                .as("这一行是换模型时唯一能被搜到的地方；删掉它不会有别的用例变红，"
                        + "而窗口会悄悄退回代码默认值")
                .isEqualTo(SessionMemoryProperties.DEFAULT_CONTEXT_WINDOW);
    }

    @Test
    @DisplayName("两个 scope 写在 application.yml 的 claw4j.advisor.session 下：多人部署要改这里")
    void applicationYmlDeclaresTheSessionScopes() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(sources).as("application.yml 得能解析出来").isNotEmpty();
        assertThat(sources.getFirst().getProperty("claw4j.advisor.session.dm-scope"))
                .as("这一行决定私聊共不共用一段上下文。删掉它默认值仍是 main，"
                        + "但读配置的人就看不到「多人可及必须改成 per-channel-peer」（ADR-0029）")
                .isEqualTo(SessionScopeProperties.DM_MAIN);
        assertThat(sources.getFirst().getProperty("claw4j.advisor.session.group-scope"))
                .as("群聊按群隔离是默认；写出来是为了让「群为什么不共享」有地方可查")
                .isEqualTo(SessionScopeProperties.GROUP_PER_GROUP);
    }

    /**
     * 两个键真能绑到 {@link SessionScopeProperties} 上。
     *
     * <p>上一条只查文本：文本对了但前缀写错（比如写成 {@code claw4j.advisor.session.scope}）、
     * 或分量名与 kebab 名对不上，逐字比对一样是绿的，而运行时两个分量都落默认值——
     * 默认值恰好就是 {@code main} / {@code per-group}，于是"绑上了"与"没绑上"在日志里
     * 长得一模一样。这里用一组<b>非默认值</b>绑一遍，绑上了才可能得到非默认结果。
     *
     * <p>同一前缀下还塞了 {@code schema} / {@code reset.mode} 两个兄弟键：它们是别的类在管，
     * 不该被当作这两个分量，也不该把绑定带崩。
     */
    @Test
    @DisplayName("claw4j.advisor.session 的两个分量真能绑上：前缀或分量名写错时，逐字比对不会红")
    void theSessionScopesBindToThePropertiesClass() {
        SessionScopeProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "claw4j.advisor.session.dm-scope", SessionScopeProperties.DM_PER_CHANNEL_PEER,
                "claw4j.advisor.session.group-scope", SessionScopeProperties.DM_MAIN,
                "claw4j.advisor.session.schema", "claw4j_session",
                "claw4j.advisor.session.reset.mode", "daily-and-idle")))
                .bind("claw4j.advisor.session", SessionScopeProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "claw4j.advisor.session 绑不上 SessionScopeProperties：前缀或分量名对不上"));

        assertThat(bound.dmScope())
                .as("拿到默认值就说明没绑上，而这两种情况在运行时看起来一样")
                .isEqualTo(SessionScopeProperties.DM_PER_CHANNEL_PEER);
        assertThat(bound.groupScope()).isEqualTo(SessionScopeProperties.DM_MAIN);
    }

    /**
     * 拼错的 {@code dm-scope} 必须当场失败。
     *
     * <p>以前它落回 {@code main}：多人可及的部署里，写 {@code per-channel-pee} 的人得到的是
     * "所有人的私聊并成一段"，而且没有任何东西说一声——这正是最坏的一种配置错误：
     * 配了、看起来生效了、其实变成了另一个意思（ADR-0032）。
     */
    @Test
    @DisplayName("认不出的 dm-scope 直接失败，不静默落回 main")
    void unknownDmScopeIsRejected() {
        assertThatThrownBy(() -> new SessionScopeProperties("per-channel-pee", null))
                .as("错的方向必须选得出来：静默落回 main 等于把多个人的私聊并成一段")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dm-scope")
                .hasMessageContaining(SessionScopeProperties.DM_PER_CHANNEL_PEER);

        assertThat(SessionScopeProperties.defaults().dmScope())
                .as("没配仍然是 main（对齐上游默认值）；它可不可接受由 SessionBoundaryCheck 判")
                .isEqualTo(SessionScopeProperties.DM_MAIN);
    }

    /**
     * 多人可及 + {@code main} = 起不来。
     *
     * <p>四种放行情形一起钉：只有一个人（多通道也算一个人）、一个人都没有、换一档分法、
     * 以及"判据是并集而不是逐通道"——http 给 alice、飞书给 bob 时，逐通道看每边都是 1 个人，
     * 而他们仍然共用一段主会话。
     */
    @Test
    @DisplayName("多人可及 + dm-scope=main 拒绝启动；单人或换一档就放行")
    void multiUserWithMainScopeIsRejected() {
        assertThatThrownBy(() -> SessionBoundaryCheck.verify(
                Set.of("alice", "bob"), SessionScopeProperties.DM_MAIN))
                .as("两个人的私聊并成一段：网页里问到的数字，飞书私聊里也看得见")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alice")
                .hasMessageContaining(SessionScopeProperties.DM_PER_CHANNEL_PEER);

        assertThatThrownBy(() -> SessionBoundaryCheck.verify(
                Set.of(SessionBoundaryCheck.ANY_USER), SessionScopeProperties.DM_MAIN))
                .as("不设准入 = 人数未知，取严的一侧")
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> SessionBoundaryCheck.verify(
                Set.of("alice"), SessionScopeProperties.DM_MAIN))
                .as("单用户：换几条通道也只有一个身份")
                .doesNotThrowAnyException();
        assertThatCode(() -> SessionBoundaryCheck.verify(
                Set.of("alice", "bob"), SessionScopeProperties.DM_PER_CHANNEL_PEER))
                .as("各有各的分法，不存在并成一段")
                .doesNotThrowAnyException();
        assertThatCode(() -> SessionBoundaryCheck.verify(Set.of(), SessionScopeProperties.DM_MAIN))
                .as("一个人都进不来，谈不上串话")
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 迁移合成一套 schema

    /** 和 {@code SearchSqlTest} 用同一个镜像：{@code V4} 要 {@code create extension vector}。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Test
    @DisplayName("跨 jar 的迁移按版本号合并：七份基线全跑到，六个 schema 都在")
    void migrationsFromEveryModuleLandInOneHistory() {
        var flyway = flyway();
        flyway.migrate();

        // 断言跑完之后历史里有什么，而不是这一次执行了几条：同一份容器被两条用例共用，
        // 谁先跑都会把迁移做完。
        //
        // 七份基线，一个 schema 一份：会话（含进度卡与事件条件意图）、知识库、审批/定时/审计/白名单、
        // 入站队列、工具检索的向量（票 07）、以及示例插件的业务库（V1 建表 + V2 种子）。改表结构的地方
        // 直接改对应的那一份，不往上叠补丁——补丁串读不出"现在的形状"，而这张表正是为了让人一眼看见它。
        //
        // 唯一的例外是 V7（2026-11-25，删会话身份层那四张表）：它是**删**，而 V3 已经在开发库与
        // 验收库里跑过了——回头改 V3 会让那些库的 Flyway 校验和对不上，连迁移都跑不起来。
        // 所以这一条留在补丁里，等下一次重建基线时并回 V3。
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");

        var jdbc = new JdbcTemplate(dataSource());

        // 六个 schema 各自的归属：business 是示例插件的，其余是 agent 的（会话 claw4j_session、
        // 调度/审批/审计在 claw4j_run、知识库在 claw4j_rag、入站队列在 claw4j_channel、
        // 工具检索的向量在 claw4j_tool）。缓存不在其中：它一旦重新出现在这里，就是有人又把缓存往库里写了。
        // 入站队列单独一个是因为它跟着通道走：它是平台把消息交给宿主这件事的账，
        // 与一次 agent 运行没有父子关系。claw4j_tool 与 claw4j_rag 必须是两个：共用一张表，
        // 工具描述会跟着知识库的两路召回进业务问答。
        assertThat(jdbc.queryForList(
                "select schema_name from information_schema.schemata"
                        + " where schema_name in ('business', 'claw4j_session', 'claw4j_cache',"
                        + " 'claw4j_run', 'claw4j_rag', 'claw4j_channel', 'claw4j_tool')"
                        + " order by schema_name", String.class))
                .containsExactly("business", "claw4j_channel", "claw4j_rag",
                        "claw4j_run", "claw4j_session", "claw4j_tool");

        // 会话 schema 里是框架的两张加我们的两张（ADR-0029 修订：身份层交回框架）。
        // 少了哪张说明有人把基线改坏了；多了哪张（比如那张平铺的 session_entry）说明有人
        // 在基线之外又摊了一张表。
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = 'claw4j_session' order by table_name", String.class))
                .containsExactly("ai_session", "ai_session_event", "session_progress_cards",
                        "standing_intents");

        // V9 的工具描述译文表：它必须存在，否则翻译每轮重译、评测分数跟着抖（票 16）。
        // 只断表在，不断列——列由迁移脚本定义，改列是那次改动自己的事。
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = 'claw4j_tool' order by table_name", String.class))
                .as("译文表不在 = 中文化退化成每次重译，于是「开了之后分数涨了多少」永远答不准")
                .contains("tool_description");

        // V2 的种子真的落了（示例插件那份 DDL 也在同一批迁移里执行，不是被跳过）。
        assertThat(jdbc.queryForObject("select count(*) from business.sales_order", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("重复跑不重复执行：第二次 migrate 执行 0 条")
    void secondRunIsANoOp() {
        var flyway = flyway();
        flyway.migrate();

        // 8 个文件分布在不同 jar 里，重复扫描最容易出现的是"某个 jar 里的又被当成新的"。
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

    /**
     * 出站暴露是<b>删掉的功能</b>，不是关着的功能（票 08 / ADR-0030 裁决二）。
     *
     * <p>三条一起验，因为它们会以三种不同的方式回来：类被加回来（有人照着旧文档恢复实现）、
     * 配置段被加回来（那份 yml 是最容易被复制粘贴的东西）、profile 文件被加回来（它只是资源，
     * 编译期什么都不报）。只验其中一条的话，另外两条回来时没有任何东西会红。
     *
     * <p>不引那个类名做编译期断言：类已经不存在，引了这条用例自己也编不过——用
     * {@link Class#forName} 换字符串，才能在"它真的没了"这件事上给出肯定答案。
     */
    @Test
    @DisplayName("出站 MCP 暴露已经不存在：三个类、配置段、stdio profile 都不在")
    void outboundExposureIsGone() throws IOException {
        for (String name : List.of(
                "io.github.claw4j.capability.mcp.expose.McpExposure",
                "io.github.claw4j.capability.mcp.expose.McpExposureProperties",
                "io.github.claw4j.capability.mcp.expose.McpServerExposureAutoConfiguration")) {
            assertThatThrownBy(() -> Class.forName(name))
                    .as("这个类被加回来了：%s", name)
                    .isInstanceOf(ClassNotFoundException.class);
        }

        // 读源文件而不是 classpath：增量构建里 target/classes 会留着已经删掉的资源，
        // 拿 classpath 判"文件不存在"会在本机绿、在 clean 构建下才红的那一边翻过来。
        Path resources = Path.of("src/main/resources");
        String application = Files.readString(resources.resolve("application.yml"), StandardCharsets.UTF_8);
        assertThat(application)
                .as("整段键都要没了，不是只删 tables 一行：留着键的人会以为它还生效")
                .doesNotContain("mcp.server")
                .doesNotContain("MCP_SERVER_ENABLED");

        assertThat(resources.resolve("application-mcp-stdio.yml"))
                .as("那个 profile 就是为了跑这条 server，功能没了它只剩误导")
                .doesNotExist();

        // 注册表这一层单看一个文件不行：每个模块各有一份 imports（app 自己那份只有插件闸）。
        // 全 classpath 扫一遍，才能同时回答"出站没被注册回来"与"入站还在注册"。
        StringBuilder registrations = new StringBuilder();
        for (Resource file : new PathMatchingResourcePatternResolver().getResources(
                "classpath*:META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            registrations.append(file.getContentAsString(StandardCharsets.UTF_8)).append('\n');
        }
        assertThat(registrations.toString())
                .doesNotContain("mcp.expose.")
                .as("入站那一侧必须还在：一个字母之差，删错就是把「连别人的 server」一起废了")
                .contains("io.github.claw4j.capability.mcp.ConfiguredMcpServersAutoConfiguration");
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

    @Test
    @DisplayName("同一条审计重复投递只留一行；表里也没有 actor_role 那列（ADR-0037 票 02 / 票 01）")
    void auditReplayIsWrittenOnce() {
        var jdbc = new JdbcTemplate(dataSource());
        var registry = new SimpleMeterRegistry();
        var ledger = new AuditLedger(jdbc, registry);
        var scope = AuditEvent.Scope.of("run-replay-1", "session-replay", null, "http");
        var event = AuditEvent.runStarted(scope);

        // 同一件事投两次：第二次被 (source_id, source_sequence) 的唯一键挡下。
        ledger.record(event);
        ledger.record(event);

        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_run.audit_event where execution_id = 'run-replay-1'",
                Integer.class))
                .as("第二次投递不该多出一行")
                .isEqualTo(1);
        assertThat(registry.get(AuditLedger.DUPLICATES).counter().count())
                .as("重复要单独计数，不能混进 write_errors")
                .isEqualTo(1.0);

        // 同参数、两次不同的调用（票 06）：编号不同 → 来源键不同 → 四行都在。
        // 改之前这里只会有两行：第二次那一对被唯一键挡成"重复"，而它其实是另一次真实调用。
        var toolScope = AuditEvent.Scope.of("run-tool-twice", "session-replay", null, "http");
        String sameArgs = "digest-same";
        for (String callId : List.of("call-a", "call-b")) {
            ledger.record(AuditEvent.toolStarted(toolScope, callId, "querySales", sameArgs));
            ledger.record(AuditEvent.toolFinished(toolScope, callId, "querySales", sameArgs,
                    AuditStatus.SUCCEEDED, null, null));
        }
        var callIds = jdbc.queryForList("select tool_call_id from claw4j_run.audit_event"
                + " where execution_id = 'run-tool-twice' order by seq", String.class);
        assertThat(callIds)
                .as("同参数两次调用 = 四行")
                .hasSize(4)
                .doesNotContainNull();
        assertThat(callIds.stream().distinct().count())
                .as("两个编号哈希后互不相同，各占两行")
                .isEqualTo(2);
        assertThat(jdbc.queryForList("select distinct args_digest from claw4j_run.audit_event"
                + " where execution_id = 'run-tool-twice'", String.class))
                .as("前提：两组的参数指纹完全一样——正是改之前会被合并的那种情形")
                .containsExactly("digest-same");

        // 同一张表的另一条形状约束（原 auditEventHasNoActorRole）：死列留着会被下一个人拿去过滤，
        // 而它恒为 NULL——那种过滤查出来永远是空，且不报错。
        assertThat(jdbc.queryForList(
                "select column_name from information_schema.columns"
                        + " where table_schema = 'claw4j_run' and table_name = 'audit_event'"
                        + " and column_name = 'actor_role'", String.class))
                .isEmpty();
    }


    @Test
    @DisplayName("审批交接落库：换一份实例还读得到，取一次就没了（ADR-0037 票 05）")
    void decisionFactsSurviveAndAreOneShot() {
        // 原来这份交接是 ToolCallAudit 里的进程内 Map：重启即丢，超 256 条整份清掉。
        // 落成表之后「这次执行是哪次批准的」不再随进程消失，也仍然只为一次执行放行。
        var jdbc = new JdbcTemplate(dataSource());
        var facts = new JdbcDecisionFacts(jdbc);
        facts.grant("run-df", "Bash", "digest-df", "ap-1");

        assertThat(new JdbcDecisionFacts(jdbc).take("run-df", "Bash", "digest-df"))
                .as("另一份实例读同一张表：这就是重启之后还对得上")
                .isEqualTo("ap-1");
        assertThat(facts.take("run-df", "Bash", "digest-df"))
                .as("取一次就没了：同一条批准不为第二次执行放行")
                .isNull();

        // 同一个键写两次只留一条（主键就是"这一次调用"），后写的批准覆盖前一条：
        // 同一次调用被重新批过时，账上该留最新那条，而不是两条。
        facts.grant("run-df2", "Bash", "digest-df2", "ap-old");
        facts.grant("run-df2", "Bash", "digest-df2", "ap-new");
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.execution_decision_fact"
                + " where execution_id = 'run-df2'", Integer.class)).isEqualTo(1);
        assertThat(facts.take("run-df2", "Bash", "digest-df2")).isEqualTo("ap-new");

        // 过期清理按 created_at：把"现在"当成过期点，刚写的那条也该被清掉。
        facts.grant("run-df3", "Bash", "digest-df3", "ap-3");
        assertThat(facts.prune(java.time.Duration.ZERO)).isGreaterThanOrEqualTo(1);
        assertThat(facts.take("run-df3", "Bash", "digest-df3"))
                .as("清掉之后取不到了")
                .isNull();
    }

    @Test
    @DisplayName("审计读侧：seq 倒序、limit 生效、nextCursor 接着翻、过滤与钳位（ADR-0037 票 03）")
    void auditQueryReturnsNewestFirst() {
        var jdbc = new JdbcTemplate(dataSource());
        var ledger = new AuditLedger(jdbc, null);
        var query = new JdbcAuditQuery(jdbc);
        // 用 agentId 当判别列：同一个容器被十几条用例共用，不带过滤的"最新三条"分不清是谁写的。
        for (int i = 1; i <= 3; i++) {
            ledger.record(AuditEvent.runStarted(
                    new AuditEvent.Scope("run-qpage" + i, "session-q", null, "agent-qpage", "u", "http")));
        }

        var page1 = query.query(new AuditQuery.Filter(null, null, "agent-qpage", null, null,
                null, null, null, null, null, 2));
        assertThat(page1.rows()).as("每页两条").hasSize(2);
        assertThat(page1.rows().get(0).seq())
                .as("倒序：第一条的序号比第二条大")
                .isGreaterThan(page1.rows().get(1).seq());
        assertThat(page1.nextCursor()).as("还有第三条，所以有下一页").isNotNull();

        var page2 = query.query(new AuditQuery.Filter(null, null, "agent-qpage", null, null,
                null, null, null, null, page1.nextCursor(), 2));
        assertThat(page2.rows()).as("第二页剩一条").hasSize(1);
        assertThat(page2.rows().getFirst().seq())
                .as("下一页的起点严格小于游标")
                .isLessThan(page1.nextCursor());
        assertThat(page2.nextCursor()).as("没有更多了").isNull();

        // 过滤与钳位在同一条用例里验：它们共用同一张表、同一份数据，拆开只是多一步准备。
        ledger.record(AuditEvent.toolStarted(
                new AuditEvent.Scope("run-qfilter", "session-q", "session-q-seg", "agent-qfilter",
                        "u", "http"),
                "call-qfilter", "Bash", "digest-qfilter"));
        var toolPage = query.query(new AuditQuery.Filter("run-qfilter", null, null, "tool_action",
                null, null, null, null, null, null, 10));
        assertThat(toolPage.rows()).as("按 executionId + kind 命中那一行").hasSize(1);
        var toolRow = toolPage.rows().getFirst();
        assertThat(toolRow.toolCallId())
                .as("工具行带得回调用编号（票 06）：落库形状是哈希，不是 provider 的原值")
                .matches("sha256:[0-9a-f]{64}");
        assertThat(toolRow.executionId()).as("执行编号读得回来（ADR-0038 之后列叫 execution_id）")
                .isEqualTo("run-qfilter");
        assertThat(toolRow.schemaVersion()).as("版本号读得回来（第三批）").isEqualTo(1);
        assertThat(toolRow.sourceSequence()).as("来源内序号读得回来").isEqualTo(1);
        assertThat(toolRow.sessionId()).as("两把会话键都在").isEqualTo("session-q-seg");
        assertThat(toolRow.actorType()).as("运行/工具行的执行者是 agent（第三批）").isEqualTo("agent");
        assertThat(toolRow.actor()).as("actor 记执行者：agentId").isEqualTo("agent-qfilter");
        assertThat(toolRow.channel()).as("channel 只属于消息行（OpenClaw 对非消息行强制 null）").isNull();
        assertThat(toolRow.redaction()).isEqualTo("metadata_only");
        assertThat(query.query(new AuditQuery.Filter("run-qfilter", null, null, "agent_run", null,
                null, null, null, null, null, 10)).rows())
                .as("kind 不匹配时一行都不给")
                .isEmpty();
        // 事件时刻是事件自己的（票 12）：直接造一条"两小时前发生"的事件，落库后读回来还得是那个时刻。
        // 不绑 at 时列默认值 now() 会把它改写成"刚刚"——而那正是这一票要修的那件事。
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        ledger.record(new AuditEvent(
                new AuditEvent.Scope("run-qmoment", "session-q", null, "agent-qfilter", "u", null),
                // 终态而不是 started：停在 started 的老行会被孤儿扫尾补成 unknown，
                // 那会把这条用例变成"顺便验了扫尾"，而它要验的只有事件时刻。
                AuditAction.AGENT_RUN_FINISHED, AuditStatus.SUCCEEDED, null, null, null, null, null,
                "exec:run-qmoment", 2, null, twoHoursAgo));
        Instant stored = query.query(new AuditQuery.Filter("run-qmoment", null, null, null, null,
                null, null, null, null, null, 10)).rows().getFirst().at();
        assertThat(Duration.between(stored, twoHoursAgo).abs())
                .as("写库不许把事件时刻改写成落库时刻")
                .isLessThan(Duration.ofSeconds(1));

        assertThat(new AuditQuery.Filter(null, null, null, null, null, null, null, null, null, null,
                999).limit())
                .as("limit 截到 OpenClaw 的上限 500，而不是照单全收")
                .isEqualTo(AuditQuery.Filter.MAX_LIMIT);

        // 消息行也读得出来，而且那几列在（ADR-0037 票 04）：读接口没有它们的话，
        // 消息审计等于只写不查——"这个客户的消息送到没有"还是得自己写 SQL。
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, agent_id, source_id, source_sequence,"
                + " direction, channel, message_outcome, conversation_kind, duration_ms, actor_type,"
                + " actor, account_ref)"
                + " values ('e-qmsg', 'message', 'message.inbound.processed', 'succeeded',"
                + " 'agent-qpage', 'message:in:q1', 1, 'inbound', 'http', 'completed', 'direct', 42,"
                + " 'channel_sender', 'hmac-sha256:v1:0011223344556677:abc',"
                + " 'hmac-sha256:v1:0011223344556677:abc')");
        var messages = query.query(new AuditQuery.Filter(null, null, "agent-qpage", "message", "inbound",
                null, null, null, null, null, 10));
        assertThat(messages.rows()).as("按 kind=message 只取消息行").hasSize(1);
        assertThat(messages.rows().getFirst().message().outcome()).isEqualTo("completed");
        assertThat(messages.rows().getFirst().message().durationMs()).isEqualTo(42);
        assertThat(messages.rows().getFirst().message().accountRef()).startsWith("hmac-sha256:v1:");
        assertThat(messages.rows().getFirst().actor())
                .as("入站消息的 actor 就是发送者假名（与 account_ref 同值，第三批）")
                .isEqualTo(messages.rows().getFirst().message().accountRef());
        assertThat(messages.rows().getFirst().actorType()).isEqualTo("channel_sender");
        assertThat(messages.rows().getFirst().channel()).isEqualTo("http");
        // 方向过滤（照 audit.activity.list）：进来的与出去的混在一页里，问"今天发出去的送到没有"
        // 就得先能只要 outbound。这一条同时钉住 Filter 里那个字段真的进了 where。
        assertThat(query.query(new AuditQuery.Filter(null, null, "agent-qpage", "message",
                "outbound", null, null, null, null, null, 10)).rows())
                .as("那一条是 inbound，按 outbound 过滤一行都不给")
                .isEmpty();
        assertThat(messages.rows().getFirst().message().conversationKind())
                .as("私聊/群那一列也读得回来").isEqualTo("direct");
        // 运行与工具行没有那一组：读的人不必面对十二个空格去猜"是没记还是记了空"。
        assertThat(query.query(new AuditQuery.Filter(null, null, "agent-qpage", "agent_run", null,
                null, null, null, null, null, 10)).rows())
                .allSatisfy(row -> assertThat(row.message()).isNull());
    }

    @Test
    @DisplayName("审计在库里的几条规则：码与状态必须配对、编号/归属/通道的形状、孤儿补成 unknown")
    void auditTerminalRowsAreConsistent() {
        var jdbc = new JdbcTemplate(dataSource());
        // ① 配对：先证明合法的那一对能进——这条用例不能因为整张表都写不进去而"绿"。
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, error_code, execution_id, source_id, source_sequence,"
                + " actor)"
                + " values ('e-pair-ok', 'agent_run', 'agent.run.finished', 'failed',"
                + " 'run_failed', 'run-pair', 'run:run-pair', 2, 'agent-1')");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, error_code, execution_id, source_id, source_sequence,"
                + " actor)"
                + " values ('e-pair-bad', 'agent_run', 'agent.run.finished', 'failed',"
                + " 'tool_failed', 'run-pair', 'run:run-pair-2', 2, 'agent-1')"))
                .as("运行级事件配工具级的码：两层混在一起，按错误码统计就把两件事加成一个数")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, error_code, execution_id, source_id, source_sequence,"
                + " actor)"
                + " values ('e-pair-none', 'tool_action', 'tool.action.finished', 'failed',"
                + " null, 'run-pair', 'tool:run-pair:Bash:d1', 2, 'agent-1')"))
                .as("failed 不带错误码：查「为什么失败」时那一格是空的")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, error_code, execution_id, source_id, source_sequence,"
                + " actor)"
                + " values ('e-pair-started', 'tool_action', 'tool.action.started', 'started',"
                + " 'tool_failed', 'run-pair', 'tool:run-pair:Bash:d2', 1, 'agent-1')"))
                .as("开始那一条带错误码：它还没结束，有什么错")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // ③ 编号只属于工具行（票 06）：消息变体在读侧是 `toolCallId?: never`
        //    （audit-activity.ts:463），这条 CHECK 是它在写侧的落点。
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, tool_call_id, source_id, source_sequence,"
                + " actor)"
                + " values ('e-tcid-ok', 'tool_action', 'tool.action.started', 'started', 'run-tcid',"
                + " 'call-1', 'tool:run-tcid:Bash:d1', 1, 'agent-1')");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, tool_call_id, source_id, source_sequence,"
                + " actor)"
                + " values ('e-tcid-bad', 'agent_run', 'agent.run.started', 'started', 'run-tcid',"
                + " 'call-1', 'run:run-tcid', 1, 'agent-1')"))
                .as("运行行带调用编号：那一列是工具行的，读侧也按这个形状区分")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // ④ 归属与通道（第三批）：actor 必填；channel 只属于消息行
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, source_id, source_sequence)"
                + " values ('e-no-actor', 'agent_run', 'agent.run.started', 'started', 'run-1',"
                + " 'no-actor:1', 1)"))
                .as("归属是读侧四个变体的必填格（commonProperties + agentProperties），空着客户端解析不了")
                .hasMessageContaining("ck_audit_event_actor_present");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, source_id, source_sequence, actor,"
                + " channel)"
                + " values ('e-tool-channel', 'tool_action', 'tool.action.started', 'started',"
                + " 'run-1', 'tool-channel:1', 1, 'agent-1', 'http')"))
                .as("通道只属于消息行：OpenClaw 对非消息行强制写 null（audit-event-store.ts:103）")
                .hasMessageContaining("ck_audit_event_channel_message_only");

        // ⑤ 消息行的必填与互斥（第四批，票 13）：读侧消息变体里 channel / conversationKind 必填，
        //    suppressed 与 unknown 不带 deliveryKind，failed 与 unknown 必带 failureStage。
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, actor, source_id, source_sequence, direction,"
                + " conversation_kind, message_outcome)"
                + " values ('e-msg-no-channel', 'message', 'message.inbound.processed', 'succeeded',"
                + " 'agent-1', 'msg-no-channel:1', 1, 'inbound', 'direct', 'completed')"))
                .as("消息行没有 channel：读侧必填格空着，严格客户端解析不了")
                .hasMessageContaining("ck_audit_event_message_required");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, actor, source_id, source_sequence, direction,"
                + " channel, conversation_kind, message_outcome, reason_code, delivery_kind)"
                + " values ('e-msg-suppressed-text', 'message', 'message.outbound.finished', 'blocked',"
                + " 'agent-1', 'msg-suppressed:1', 1, 'outbound', 'http', 'direct', 'suppressed',"
                + " 'no_visible_payload', 'text')"))
                .as("被抑制的投递没有交付形态（audit-activity.ts:373 的 withoutDeliveryKind）")
                .hasMessageContaining("ck_audit_event_delivery_kind_absent");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, error_code, actor, source_id, source_sequence,"
                + " direction, channel, conversation_kind, message_outcome)"
                + " values ('e-msg-failed-no-stage', 'message', 'message.outbound.finished', 'failed',"
                + " 'message_delivery_failed', 'agent-1', 'msg-failed-no-stage:1', 1, 'outbound',"
                + " 'http', 'direct', 'failed')"))
                .as("失败必须说清卡在哪一段（audit-activity.ts:380）")
                .hasMessageContaining("ck_audit_event_failure_stage_required");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, actor, source_id, source_sequence, direction,"
                + " channel, conversation_kind, message_outcome, reason_code)"
                + " values ('e-msg-sent-reason', 'message', 'message.outbound.finished', 'succeeded',"
                + " 'agent-1', 'msg-sent-reason:1', 1, 'outbound', 'http', 'direct', 'sent',"
                + " 'no_visible_payload')"))
                .as("送出去了就不该带原因码（audit-activity.ts:360 的 withoutReasonCode）")
                .hasMessageContaining("ck_audit_event_outbound_reason");

        // ② 孤儿扫尾：两条停在 started 且已经过窗口的，补成 unknown；
        //    另有一条"刚开跑"的，它不该被补——补了就是把正在跑的一轮判成不知道结果。
        var ledger = new AuditLedger(jdbc, null);
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, session_key, agent_id,"
                + " source_id, source_sequence, at, actor)"
                + " values ('e-orphan-run', 'agent_run', 'agent.run.started', 'started', 'run-orphan',"
                + " 'sk-orphan', 'ops', 'run:run-orphan', 1, now() - interval '2 hours', 'ops')");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, tool_call_id, tool_name, args_digest,"
                + " source_id, source_sequence, at, actor)"
                + " values ('e-orphan-tool', 'tool_action', 'tool.action.started', 'started', 'run-orphan',"
                + " 'call-orphan', 'Bash', 'd-orphan', 'tool:run-orphan:Bash:d-orphan', 1,"
                + " now() - interval '2 hours', 'ops')");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, source_id, source_sequence, actor)"
                + " values ('e-fresh-start', 'agent_run', 'agent.run.started', 'started', 'run-fresh',"
                + " 'run:run-fresh', 1, 'ops')");

        assertThat(ledger.sweepOrphans(AuditLedger.ORPHAN_AGE)).as("补了两条").isEqualTo(2);
        assertThat(jdbc.queryForList("select status from claw4j_run.audit_event"
                + " where source_id = 'run:run-orphan' and source_sequence = 2", String.class))
                .as("运行级孤儿补成 unknown；没有运行级的 unknown 码，错误码留空")
                .containsExactly("unknown");
        assertThat(jdbc.queryForObject("select error_code from claw4j_run.audit_event"
                + " where source_id = 'tool:run-orphan:Bash:d-orphan' and source_sequence = 2",
                String.class))
                .as("工具级孤儿带 tool_outcome_unknown：它跑了，但结果不可知")
                .isEqualTo("tool_outcome_unknown");
        assertThat(jdbc.queryForObject("select tool_call_id from claw4j_run.audit_event"
                + " where source_id = 'tool:run-orphan:Bash:d-orphan' and source_sequence = 2",
                String.class))
                .as("补出来的终态跟着同一次调用的编号走，不是另一次（票 06）")
                .isEqualTo("call-orphan");
        assertThat(jdbc.queryForList("select status from claw4j_run.audit_event"
                + " where source_id = 'run:run-fresh'", String.class))
                .as("刚开跑的那一轮不动")
                .containsExactly("started");
        // 再跑一次不重复补：唯一键挡着，不然每小时维护都会给同一件事补一行。
        assertThat(ledger.sweepOrphans(AuditLedger.ORPHAN_AGE)).isZero();
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
