package io.github.claw4j.capability.builtin.sales;

import io.github.claw4j.capability.PostgresTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读约束的第二道防线：数据库自己拒绝写操作。
 *
 * <p>与 SalesSqlGuardTest 的分工：那边证明"字符串校验拦得住常见的写操作"，
 * 这边证明<b>就算校验被绕过，写操作也落不了库</b>。后者才是"DDL 与 DML 不可能通过
 * 提问发生"这条判据的落点——字符串校验是人写的一段正则，它可以是错的；
 * 只读事务是数据库的规则，它不会错。
 *
 * <p>断言一律是"抛异常 + 数据没变"两件事一起：只断言抛异常的话，
 * 一个"抛了异常但其实已经删掉了"的实现也能绿。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("postgresImageIsAvailable")
class SalesSqlExecutionTest {

    /** 上周华东区成交额，与种子契约同一个数：54000 + 74400。 */
    private static final String LAST_WEEK_EAST = "128400.00";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.container();

    private static JdbcClient jdbc;

    private static DataSource dataSource;

    private static ReadOnlySqlExecutor executor;

    @BeforeAll
    static void migrateAndWire() {
        PostgresTestSupport.migrate(POSTGRES);
        // 事务管理器与 JdbcClient 必须用同一个 DataSource 实例：Spring 按 DataSource 对象
        // 做键来绑定连接，两个实例就是两条连接，"SET 与查询在同一条连接上"这个前提直接塌掉
        dataSource = PostgresTestSupport.dataSource(POSTGRES);
        jdbc = JdbcClient.create(dataSource);
        executor = new JdbcReadOnlySqlExecutor(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("模型写的真实 SQL 拿到种子数；超过行上限时截断并明确标记")
    void 真实SQL与行上限() {
        QueryResult weekly = executor.execute("""
                select coalesce(sum(oi.amount), 0) as amount
                from business.sales_order o
                         join business.order_item oi on oi.order_id = o.id
                where o.region_code = 'east'
                  and o.status <> 'CANCELLED'
                  and o.order_date >= date_trunc('week', current_date) - interval '7 days'
                  and o.order_date < date_trunc('week', current_date)""");

        assertThat(weekly.columns()).containsExactly("amount");
        assertThat(weekly.rows()).containsExactly(java.util.List.of(LAST_WEEK_EAST));
        assertThat(weekly.truncated()).isFalse();

        QueryResult capped = executor.execute("select g as n from generate_series(1, 120) g");

        assertThat(capped.rowCount()).isEqualTo(SalesSqlGuard.MAX_ROWS);
        assertThat(capped.truncated()).isTrue();
    }

    @Test
    @DisplayName("写操作与可写 CTE 都落不了库，数据一条没少")
    void 写操作落不了库() {
        int before = countOrders();

        // 裸 DML / DDL 连解析都过不去：行上限那层包装把它们变成了子查询
        assertThatThrownBy(() -> executor.execute("delete from business.sales_order"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> executor.execute("update business.region set name = 'x'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> executor.execute("drop table business.order_item"))
                .isInstanceOf(DataAccessException.class);
        // PostgreSQL 要求带数据修改的 WITH 必须在最外层，而它被包在子查询里
        assertThatThrownBy(() -> executor.execute("""
                with doomed as (delete from business.sales_order returning *)
                select * from doomed"""))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("top level");

        assertThat(countOrders()).isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from business.region where name = 'x'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from business.order_item")
                .query(Integer.class).single()).isPositive();
    }

    /**
     * "只读事务真的生效"的唯一直接证据。
     *
     * <p>上面几条都被"包一层子查询"挡在了解析阶段，证明不了事务本身；
     * 而 {@code select ... for update} 能穿过包装（它是合法的 select），
     * 撞到的就是数据库那条规则：只读事务里不能加行锁。
     */
    @Test
    @DisplayName("只读事务本身拒绝写锁：能穿过包装的写操作也落不了地")
    void 只读事务拒绝写锁() {
        assertThatThrownBy(() -> executor.execute("select id from business.sales_order for update"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only");
    }

    /** 超时是给"语法合法但语义荒谬"的 SQL 准备的：模型漏写 join 条件时会一直占着连接。 */
    @Test
    @DisplayName("跑太久了会被超时掐断")
    void 超时() {
        var impatient = new JdbcReadOnlySqlExecutor(jdbc, new DataSourceTransactionManager(dataSource),
                SalesSqlGuard.MAX_ROWS, Duration.ofMillis(200));

        assertThatThrownBy(() -> impatient.execute("select pg_sleep(3)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("statement timeout");
    }

    private static int countOrders() {
        return jdbc.sql("select count(*) from business.sales_order").query(Integer.class).single();
    }

    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        return PostgresTestSupport.databaseAvailable("SalesSqlExecutionTest");
    }
}
