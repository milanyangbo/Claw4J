package io.github.claw4j.tools.sales;

import io.github.claw4j.tools.PostgresTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源接缝的第一层断言：给定区域与时间窗，查出来的钱必须对得上种子。
 *
 * <p>这个数值是 05 号票的判据："问一句话拿到数"拿到的必须是同一个数。
 * 两处各写一个数、谁也不校验对方，就会出现"库里是 A、Agent 答 B"的假绿。
 *
 * <p><b>为什么时间窗不从 Java 算</b>：种子里的日期是数据库按
 * {@code date_trunc('week', current_date)} 生成的相对日期，测试若按 JVM 的时区另算一遍，
 * 跨日或跨时区时两边会差一天，而差一天就把订单推到另一个周里去。所以上周一这个基准
 * 由数据库给出（见 {@code thisMonday}），Java 只在它上面做加减。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("postgresImageIsAvailable")
class SalesDataRepositoryTest {

    /** 上周华东区成交额。128400 = 54000（SO-LAST-01）+ 74400（SO-LAST-02）。 */
    private static final BigDecimal LAST_WEEK_EAST = new BigDecimal("128400.00");
    /** 上周华南区。06 号票"那华南呢"要拿到的就是它。 */
    private static final BigDecimal LAST_WEEK_SOUTH = new BigDecimal("26000.00");
    /** 本周华东：只有 SO-THIS-01 一单。用来证明时间窗不是摆设。 */
    private static final BigDecimal THIS_WEEK_EAST = new BigDecimal("99999.00");
    /** 不限时间窗的华东：128400 + 99999 + 77777，不含已取消那单的 50000。 */
    private static final BigDecimal ALL_TIME_EAST = new BigDecimal("306176.00");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.container();

    private static SalesDataRepository repository;
    private static JdbcClient jdbc;
    /** 本周一（数据库口径）。上周一 = 它减一周，见 {@link #lastMonday()}。 */
    private static LocalDate thisMonday;

    @BeforeAll
    static void migrateAndWire() {
        PostgresTestSupport.migrate(POSTGRES);
        jdbc = JdbcClient.create(PostgresTestSupport.dataSource(POSTGRES));
        repository = new SalesDataRepository(jdbc);
        thisMonday = jdbc.sql("select date_trunc('week', current_date)::date")
                .query(LocalDate.class)
                .single();
    }

    @Test
    @DisplayName("上周华东区成交额等于种子算出的那个数")
    void 上周华东区成交额() {
        assertThat(repository.sumAmount("east", lastMonday(), thisMonday)).isEqualTo(LAST_WEEK_EAST);
    }

    @Test
    @DisplayName("上周华南区成交额等于种子算出的那个数")
    void 上周华南区成交额() {
        assertThat(repository.sumAmount("south", lastMonday(), thisMonday)).isEqualTo(LAST_WEEK_SOUTH);
    }

    @Test
    @DisplayName("时间窗真的在过滤：换成本周就是另一个数")
    void 时间窗生效() {
        LocalDate nextMonday = thisMonday.plusWeeks(1);
        assertThat(repository.sumAmount("east", thisMonday, nextMonday)).isEqualTo(THIS_WEEK_EAST);
    }

    @Test
    @DisplayName("已取消的订单不计入成交额")
    void 取消订单不计入() {
        // 敞开时间窗：所有华东订单都在里面，但取消那单的 50000 不能出现。
        // 漏掉 status 过滤的话这里会是 356176，一眼能看出来。
        LocalDate farPast = thisMonday.minusYears(1);
        LocalDate farFuture = thisMonday.plusYears(1);
        assertThat(repository.sumAmount("east", farPast, farFuture)).isEqualTo(ALL_TIME_EAST);
    }

    @Test
    @DisplayName("窗口按天精确：只含上周二那一单")
    void 窗口边界按天() {
        // SO-LAST-01 在上周二（54000），SO-LAST-02 在上周四（74400）。
        // 窗口收到 [周二, 周四) 就该只剩周二那单。
        assertThat(repository.sumAmount("east", lastMonday().plusDays(1), lastMonday().plusDays(3)))
                .isEqualTo(new BigDecimal("54000.00"));
    }

    @Test
    @DisplayName("查不到数据时返回零而不是 null")
    void 无数据时返回零() {
        assertThat(repository.sumAmount("west", lastMonday(), thisMonday)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("上周华北区成交额等于种子算出的那个数")
    void 上周华北区成交额() {
        assertThat(repository.sumAmount("north", lastMonday(), thisMonday))
                .isEqualTo(new BigDecimal("31500.00"));
    }

    @Test
    @DisplayName("重启一次不会重放已执行过的迁移")
    void 迁移不会被重放() {
        // 第二次 migrate 是 Flyway 的 no-op，所以它自己证明不了什么——
        // 真要证明的是"种子那条只执行过一次"：把 V2 写成 repeatable（R__ 前缀）
        // 或者版本号改了，这里就会变成 2 条，种子被插两遍（撞 unique 或金额翻倍）。
        // 断言钉在 V2 这一条上而不是总数：总数会随新增迁移变化（会话 schema 那条 V3
        // 就让它是 3），改一次迁移就红一次，红的原因却跟"重放"毫无关系。
        PostgresTestSupport.migrate(POSTGRES);
        Long seedRuns = jdbc.sql("select count(*) from flyway_schema_history "
                        + "where success and script = 'V2__business_seed.sql'")
                .query(Long.class)
                .single();
        assertThat(seedRuns).isEqualTo(1L);
        assertThat(repository.sumAmount("east", lastMonday(), thisMonday)).isEqualTo(LAST_WEEK_EAST);
    }

    private static LocalDate lastMonday() {
        return thisMonday.minusWeeks(1);
    }

    /** 镜像与"拉不到就跳过"的策略都在 {@link PostgresTestSupport} 里，与别的数据库测试共用一份。 */
    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        return PostgresTestSupport.databaseAvailable("SalesDataRepositoryTest");
    }
}
