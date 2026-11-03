package io.github.claw4j.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两条"构建与库结构"的契约：Java 侧不自己拼命令行（ADR-0002），以及跨 jar 的迁移合成一套 schema。
 *
 * <p><b>为什么合成一份</b>：它们本来各占一个文件，但问的是同一类问题——<b>那些不会在运行时
 * 报错、只在某天以另一种形态暴露的约定</b>。手写 shell 的代价是"1.x 把宿主机交出去"；
 * 迁移版本号撞车的代价是"少建一张表，而启动日志完全正常"。两者都只能靠在构建里钉住，
 * 而两条都小（一条 {@code @ArchTest}、两条 SQL 断言），分开放的唯一后果是占两个文件位。
 *
 * <p>合并的直接原因是硬约束：{@code claw4j-app} 与别处一样卡在 10 个测试文件，
 * P13 要给 Slack 的真模型闭环留一个位置。
 *
 * <p><b>它们只能在 app 模块</b>：别的模块的架构测试只扫得见自己，而这两条都要扫全部模块
 * （前者要扫所有 {@code src/main/java}，后者要扫 classpath 上所有 {@code db/migration}）。
 */
@Testcontainers
@AnalyzeClasses(packages = "io.github.claw4j", importOptions = ImportOption.DoNotIncludeTests.class)
class BuildContractTest {

    // ---------------------------------------------------------------- 不起进程（ADR-0002）

    /** 全仓唯一被允许起进程的类。加第二个之前先想清楚"超时、目录锁、干净环境"这三句话还成不成立。 */
    private static final String PROCESS_SPAWNER = "io.github.claw4j.capability.process.LocalProcessRunner";

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
                    .should().callMethodWhere(RUNTIME_EXEC)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                    .because("起进程只发生在 " + PROCESS_SPAWNER + "，别处不自己拼命令行（ADR-0002）");

    /**
     * 会命令解释器的写法。shell 会把参数里的分号当成第二条命令，
     * 于是"传一个参数"变成了"执行一段脚本"。注释里也不许出现——扫的是整份文件，
     * 区分代码与注释要写解析器，那份代价比"注释换个说法"贵得多。
     */
    private static final List<String> SHELL_INTERPRETERS =
            List.of("sh -c", "bash -c", "/bin/sh", "/bin/bash", "cmd.exe");

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

    // ---------------------------------------------------------------- 迁移合成一套 schema

    /** 和 {@code SearchSqlTest} 用同一个镜像：{@code V4} 要 {@code create extension vector}。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Test
    @DisplayName("跨 jar 的迁移按版本号合并：1..15 全跑到，五个 schema 都在")
    void migrationsFromEveryModuleLandInOneHistory() {
        var flyway = flyway();
        flyway.migrate();

        // 断言"跑完之后历史里有什么"，而不是"这一次执行了几条"——同一份容器被两条用例共用，
        // 谁先跑都会把迁移做完，盯着 migrationsExecuted 会变成一条看执行顺序的用例。
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14",
                        "15");

        var jdbc = new JdbcTemplate(dataSource());

        // 五个 schema 各自的归属：business 是示例插件的，其余是 agent 的
        // （会话 claw4j_session、缓存 claw4j_cache、run 与 step 在 claw4j_run、
        // 知识库在 claw4j_rag）。**缓存与知识库是两个 schema**：合并的话，
        // 一段文档会被当成"有人问过"的答案吐出来，而它没有出处（P10）。
        assertThat(jdbc.queryForList(
                "select schema_name from information_schema.schemata"
                        + " where schema_name in ('business', 'claw4j_session', 'claw4j_cache',"
                        + " 'claw4j_run', 'claw4j_rag')"
                        + " order by schema_name", String.class))
                .containsExactly("business", "claw4j_cache", "claw4j_rag", "claw4j_run", "claw4j_session");

        // V1 的种子真的落了（示例插件那份 DDL 也在同一批迁移里执行，不是被跳过）。
        assertThat(jdbc.queryForObject("select count(*) from business.sales_order", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("重复跑不重复执行：第二次 migrate 执行 0 条")
    void secondRunIsANoOp() {
        var flyway = flyway();
        flyway.migrate();

        // 15 个文件分布在不同 jar 里，重复扫描最容易出现的是"某个 jar 里的又被当成新的"。
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
}
