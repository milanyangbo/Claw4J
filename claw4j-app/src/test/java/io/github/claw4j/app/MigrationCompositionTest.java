package io.github.claw4j.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个模块的迁移合成一套 schema：版本号接着排，执行顺序按版本号而不是按 jar。
 *
 * <p><b>为什么值得单独立一条</b>：DDL 现在分住在两个 jar 里——
 * `claw4j-example-sales` 的 `V1` `V2`（业务表与种子）、`claw4j-agent` 的 `V3`（会话表）、
 * `V4` `V5`（语义缓存表）、`V6` `V7`（run 与 step，V7 给计划加了按 kind 分支的状态约束）、
 * `V8`（用量三维）、`V9`（投递目标与结果全文）、`V10`（审批请求，P01）、
 * `V11`（审批指纹与一次性兑现、awaiting 状态、发起人快照，P02）、
 * `V12`（定时任务表，P04）、`V13`（任务的身份与工具清单，P05）、
 * `V14`（审计加列与只读视图，P07）。
 * Flyway 扫遍 classpath 上所有 `db/migration` 再按版本号排序，
 * 这件事"读文档觉得没问题"，但它有两个静默失败的姿势：
 *
 * <ul>
 *   <li><b>版本号撞了</b>：两个模块各自从 1 开始编号，Flyway 会发现同号不同校验和，
 *       报的却是一句与"谁和谁撞了"无关的话；另一种是校验和恰好相同，于是被**去重掉**，
 *       少建一张表，而启动日志看着完全正常。</li>
 *   <li><b>文件根本不在 classpath 上</b>：资源放错模块（比如放回一个没人依赖的模块），
 *       迁移数就从 14 变 13，而应用照样起得来——只有真用到那张表时才会报错，
 *       那时候离"搬了个文件"已经很远了。</li>
 * </ul>
 *
 * <p>所以这里断言的是一件很具体的事：**跑完之后迁移历史正好是 1 到 14，四个 schema 都在**。
 * 搬动任何迁移文件、改任何版本号，这条会红。
 */
@Testcontainers
class MigrationCompositionTest {

    /** 和 `SemanticCacheSqlTest` 用同一个镜像：`V4` 要 `create extension vector`。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Test
    @DisplayName("跨 jar 的迁移按版本号合并：1..14 全跑到，四个 schema 都在")
    void migrationsFromEveryModuleLandInOneHistory() {
        var flyway = flyway();
        flyway.migrate();

        // 断言"跑完之后历史里有什么"，而不是"这一次执行了几条"——同一份容器被两条用例共用，
        // 谁先跑都会把迁移做完，盯着 migrationsExecuted 会变成一条看执行顺序的用例。
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");

        var jdbc = new JdbcTemplate(dataSource());

        // 四个 schema 各自的归属：business 是示例插件的，后三个是 agent 的
        // （会话 claw4j_session、缓存 claw4j_cache、run 与 step 在 claw4j_run）。
        assertThat(jdbc.queryForList(
                "select schema_name from information_schema.schemata"
                        + " where schema_name in ('business', 'claw4j_session', 'claw4j_cache', 'claw4j_run')"
                        + " order by schema_name", String.class))
                .containsExactly("business", "claw4j_cache", "claw4j_run", "claw4j_session");

        // V1 的种子真的落了（示例插件那份 DDL 也在同一批迁移里执行，不是被跳过）。
        assertThat(jdbc.queryForObject("select count(*) from business.sales_order", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("重复跑不重复执行：第二次 migrate 执行 0 条")
    void secondRunIsANoOp() {
        var flyway = flyway();
        flyway.migrate();

        // 14 个文件分布在不同 jar 里，重复扫描最容易出现的是"某个 jar 里的又被当成新的"。
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
