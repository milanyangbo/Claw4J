package io.github.claw4j.capability.builtin.sales;

import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务数据查询：从工具入口进去，过 SQL 守卫，落到只读事务里的真库，再把行映射回文本。
 *
 * <p>真库是必须的：守卫是字符串校验，只读约束是数据库给的，两者之间的缺口
 * （可写 CTE、驱动把 readOnly 翻成 BEGIN READ ONLY）只有真 PostgreSQL 能证。
 * 迁移与种子来自 {@code classpath:db/migration}，断言里的 128400 是种子算出来的。
 */
@Testcontainers
class SalesQueryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static PlatformTransactionManager transactions;
    private static SalesTools tools;

    @BeforeAll
    static void migrateAndWire() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        tools = new SalesTools(new JdbcReadOnlySqlExecutor(jdbc, transactions));
    }

    @Test
    @DisplayName("上周华东成交额一次查全：128400 从种子落到工具返回值")
    void weeklyEastRevenueComesFromTheSeed() {
        String answer = tools.querySales("""
                select sum(oi.amount) as revenue
                from business.sales_order o
                join business.order_item oi on oi.order_id = o.id
                where o.region_code = 'east' and o.status <> 'CANCELLED'
                  and o.order_date >= date_trunc('week', current_date) - interval '7 days'
                  and o.order_date < date_trunc('week', current_date)
                """);

        assertThat(answer).contains("revenue").contains("128400");
    }

    @Test
    @DisplayName("注释里的分号不构成多语句，不误拒")
    void semicolonInsideCommentIsNotRejected() {
        String answer = tools.querySales("""
                select count(*) as regions -- ; drop table business.region
                from business.region
                """);

        assertThat(answer).doesNotContain("没有通过只读校验").contains("\n3");
    }

    @Test
    @DisplayName("可写 CTE 被拦下：with 开头不等于只读，且数据没动")
    void writableCteIsRejectedAndDataIsUntouched() {
        String answer = tools.querySales(
                "with d as (delete from business.order_item returning *) select count(*) from d");

        assertThat(answer).contains("没有通过只读校验").contains("delete");
        assertThat(countOf("business.order_item")).isEqualTo("8");
    }

    @Test
    @DisplayName("守卫漏掉的写操作由只读事务拒绝，行数不变")
    void readOnlyTransactionRejectsWriteThatTheGuardNeverSees() {
        JdbcReadOnlySqlExecutor executor = new JdbcReadOnlySqlExecutor(jdbc, transactions);

        assertThatThrownBy(() -> executor.execute(
                "insert into business.region (code, name) values ('leak', '不该落库')"))
                .isInstanceOf(DataAccessException.class);

        assertThat(countOf("business.region")).isEqualTo("3");
    }

    @Test
    @DisplayName("超过上限的结果被截断，并且明确告诉模型「只给了前 50 行」")
    void oversizedResultIsTruncatedAndSaysSo() {
        String answer = tools.querySales("select * from generate_series(1, 60) as n");

        assertThat(answer).contains("只给出前 50 行");
        assertThat(answer.lines().count()).isEqualTo(52);
    }

    @Test
    @DisplayName("刚好等于上限不算截断，多一行才算")
    void truncationStartsAtOneRowOverTheLimit() {
        JdbcReadOnlySqlExecutor executor =
                new JdbcReadOnlySqlExecutor(jdbc, transactions, 3, Duration.ofSeconds(5));

        QueryResult exact = executor.execute("select * from generate_series(1, 3) as n");
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.rows()).hasSize(3);

        QueryResult over = executor.execute("select * from generate_series(1, 4) as n");
        assertThat(over.truncated()).isTrue();
        assertThat(over.rows()).hasSize(3);
    }

    @Test
    @DisplayName("SQL 的 NULL 不渲染成空串：空值要能被读成空值")
    void nullValueRendersAsNullToken() {
        String answer = tools.querySales("select null as missing, 'null' as literal");

        assertThat(answer).contains("missing | literal").contains("NULL | null");
    }

    @Test
    @DisplayName("写错列名时把数据库的原话交回模型，而不是一句「执行失败」")
    void syntaxErrorCarriesTheRootCause() {
        String answer = tools.querySales("select no_such_column from business.region");

        assertThat(answer).contains("执行失败").contains("no_such_column").contains("does not exist");
    }

    @Test
    @DisplayName("两条语句串在一起被拒：只允许一条")
    void multipleStatementsAreRejected() {
        assertThat(tools.querySales("select 1; select 2")).contains("只允许一条语句");
    }

    @Test
    @DisplayName("不是以 select / with 开头的不放行，并说出实际的首词")
    void statementNotStartingWithSelectIsRejected() {
        assertThat(tools.querySales("values (1)"))
                .contains("只允许以 select 或 with 开头")
                .contains("values");
    }

    @Test
    @DisplayName("只被授予 region 的身份不得调用查询工具：登记的四张表缺一张就是放行越权")
    void principalWithoutEveryDeclaredTableIsDenied() {
        ToolPermissionRegistry registry = new ToolsAutoConfiguration().salesToolPermissionRegistry();
        ToolPermission permission = registry.permissionFor("querySales").orElseThrow();

        assertThat(Principal.readOnly("analyst").canUse(permission)).isTrue();
        Principal regionOnly = Principal.of("guest", "guest", Set.of("region"), Set.of("read"));
        assertThat(regionOnly.canUse(permission)).isFalse();
    }

    @Test
    @DisplayName("以行注释结尾的语句照样查得出数：包装不会把 ) limit 吞进注释里")
    void trailingLineCommentSurvivesTheRowLimitWrapper() {
        String answer = tools.querySales("""
                select sum(oi.amount) as revenue
                from business.sales_order o
                join business.order_item oi on oi.order_id = o.id
                where o.region_code = 'east' and o.status <> 'CANCELLED'
                  and o.order_date >= date_trunc('week', current_date) - interval '7 days'
                  and o.order_date < date_trunc('week', current_date) -- 只看华东
                """);

        assertThat(answer).doesNotContain("执行失败").contains("128400");
    }

    @Test
    @DisplayName("包装是「外面再包一层」：语句自带的 order by 与 limit 不被改坏")
    void innerOrderByAndLimitSurviveTheWrapper() {
        String answer = tools.querySales("select * from generate_series(1, 5) as n order by n desc limit 2");

        assertThat(answer.strip().lines().toList()).containsExactly("n", "5", "4");
    }

    private static String countOf(String table) {
        String answer = tools.querySales("select count(*) as n from " + table);
        return answer.strip().lines().toList().getLast();
    }
}
