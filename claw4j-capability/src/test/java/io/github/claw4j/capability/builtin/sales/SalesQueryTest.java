package io.github.claw4j.capability.builtin.sales;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个完整功能：业务数据查询（Text-to-SQL 的落地那一段）。
 *
 * <p>链路上有四个会静默失效的环节：字符串守卫误拒/漏放、只读事务没开、
 * 行上限被绕过、权限表少登记一张。它们都不会抛异常，
 * 只会让模型拿到一段错误或者不完整的数据，而这四个都要在真库上才验得出来。
 *
 * <p>真库是必须的：守卫与只读事务是两层，只测字符串层等于只验了"第一道有没有写"。
 */
@SpringBootTest(classes = SalesQueryTest.TestApplication.class)
@Testcontainers
class SalesQueryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private ReadOnlySqlExecutor executor;

    @Autowired
    @Qualifier("salesToolCallbackProvider")
    private ToolCallbackProvider toolProvider;

    @Autowired
    @Qualifier("salesToolPermissionRegistry")
    private ToolPermissionRegistry permissions;

    @Test
    @DisplayName("注释与字符串字面量里的分号不算第二条语句，否则合法的查询会被误拒")
    void doesNotMistakeCommentOrLiteralForASecondStatement() {
        String sql = "select name from business.region where name <> 'a; drop table business.region'"
                + " -- ; drop table business.region\norder by name";

        assertThat(SalesSqlGuard.rejectionOf(sql)).isEmpty();
    }

    @Test
    @DisplayName("with 开头不等于只读：可写 CTE 会被拦下")
    void rejectsWriteCteEvenThoughItStartsWithWith() {
        String sql = "with doomed as (delete from business.order_item returning *) select * from doomed";

        assertThat(SalesSqlGuard.rejectionOf(sql))
                .hasValueSatisfying(reason -> assertThat(reason).contains("delete"));
    }

    @Test
    @DisplayName("两条语句只放行一条：拼在后面的写操作不能搭便车")
    void rejectsSecondStatement() {
        assertThat(SalesSqlGuard.rejectionOf("select 1; select 2"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("只允许一条"));
    }

    @Test
    @DisplayName("包一层行上限不会打断用户自己的 order by / limit")
    void keepsUserOrderingAndLimitWhenCappingRows() {
        QueryResult result = executor.execute(
                "with recent as (select g from generate_series(1, 10) g)"
                        + " select g from recent order by g desc limit 3");

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.rows().getFirst()).containsExactly("10");
    }

    @Test
    @DisplayName("超过上限时截断并明确告知，模型不会把前 50 行当成全部")
    void capsRowsAndSaysItWasTruncated() {
        // 工具回调会把返回的文本再包一层 JSON 字符串，断行数要走工具方法本身；
        // 守卫与执行仍是同一条真实链路。
        String output = new SalesTools(executor).querySales("select g from generate_series(1, 60) g order by g");

        assertThat(output.lines().filter(line -> line.matches("\\d+")).count()).isEqualTo(50);
        assertThat(output).contains("结果超过").contains("行，这里只给出前").doesNotContain("\n51\n");
    }

    @Test
    @DisplayName("守卫被绕过时数据库仍拒绝写：只读事务是第二道")
    void databaseRejectsWritesWhenTheGuardIsBypassed() {
        assertThatThrownBy(() -> executor.execute(
                "insert into business.region(code, name) values ('west', '西部')"))
                .isInstanceOf(DataAccessException.class);

        assertThat(scalar("select count(*) as regions from business.region")).isEqualTo("3");
    }

    @Test
    @DisplayName("工具入口收到写语句时给回可改的理由，且一行数据都没动")
    void refusesWriteThroughTheToolWithoutTouchingData() {
        String output = querySalesTool().call("{\"sql\":\"delete from business.order_item\"}");

        assertThat(output).contains("没有通过只读校验").contains("delete");
        assertThat(scalar("select count(*) as items from business.order_item")).isEqualTo("8");
    }

    @Test
    @DisplayName("从工具入口到真库：上周华东成交额是种子数据算出来的那个数")
    void answersLastWeekEastRevenueThroughTheTool() {
        String sql = """
                select sum(oi.amount) as revenue
                from business.order_item oi
                join business.sales_order so on so.id = oi.order_id
                where so.region_code = 'east'
                  and so.status <> 'CANCELLED'
                  and so.order_date >= date_trunc('week', current_date) - interval '7 days'
                  and so.order_date < date_trunc('week', current_date)
                """;

        String output = querySalesTool().call("{\"sql\":\"" + sql.replace("\n", " ") + "\"}");

        assertThat(output).contains("revenue").contains("128400.00");
    }

    @Test
    @DisplayName("查不到行时给自查提示：中文区域名不会报错、只会返回 0 行")
    void zeroRowsComesWithASelfCorrectionHint() {
        String output = new SalesTools(executor).querySales(
                "select name from business.region where code = '华东'");

        assertThat(output).contains("返回 0 行").contains("east/south/north");
    }

    @Test
    @DisplayName("执行失败时给的是数据库原话，不是一句笼统的执行失败")
    void reportsTheDatabaseReasonRatherThanAGenericFailure() {
        String output = querySalesTool().call("{\"sql\":\"select nope from business.region\"}");

        assertThat(output).contains("执行失败").contains("nope");
    }

    @Test
    @DisplayName("缺少任意一张业务表的身份调用 querySales 会被拒：一次查询会碰全部四张表")
    void deniesIdentityThatCannotReadEveryTableTheToolTouches() {
        ToolPermission required = permissions.permissionFor("querySales").orElseThrow();

        Principal partial = Principal.of("u-1", "analyst",
                Set.of("region", "customer", "order_item"), Set.of(ToolPermission.READ));

        assertThat(partial.canUse(required)).isFalse();
        assertThat(Principal.readOnly("u-2").canUse(required)).isTrue();
    }

    private ToolCallback querySalesTool() {
        return Arrays.stream(toolProvider.getToolCallbacks())
                .filter(callback -> "querySales".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具清单里没有 querySales"));
    }

    private String scalar(String sql) {
        return executor.execute(sql).rows().getFirst().getFirst();
    }
}
