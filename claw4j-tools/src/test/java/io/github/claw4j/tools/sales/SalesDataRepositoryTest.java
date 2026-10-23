package io.github.claw4j.tools.sales;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(image())
            .withDatabaseName("claw4j")
            .withUsername("claw4j")
            .withPassword("claw4j");

    private static SalesDataRepository repository;
    private static JdbcClient jdbc;
    /** 本周一（数据库口径）。上周一 = 它减一周，见 {@link #lastMonday()}。 */
    private static LocalDate thisMonday;

    @BeforeAll
    static void migrateAndWire() {
        migrate();
        jdbc = JdbcClient.create(dataSource());
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
        // 真要证明的是"没有多出一条执行记录"：把 V2 写成 repeatable（R__ 前缀）
        // 或者版本号改了，这里就会变成 3 条，种子被插两遍（撞 unique 或金额翻倍）。
        migrate();
        Long applied = jdbc.sql("select count(*) from flyway_schema_history where success")
                .query(Long.class)
                .single();
        assertThat(applied).isEqualTo(2L);
        assertThat(repository.sumAmount("east", lastMonday(), thisMonday)).isEqualTo(LAST_WEEK_EAST);
    }

    private static LocalDate lastMonday() {
        return thisMonday.minusWeeks(1);
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    /**
     * 镜像名可被环境变量覆盖，跟 {@code compose.yaml} 用的是同一个变量。
     * 国内网络拉不动 Docker Hub 时设成镜像站即可，不必改代码。
     */
    private static String imageName() {
        String configured = System.getenv("CLAW4J_POSTGRES_IMAGE");
        return configured == null || configured.isBlank() ? "pgvector/pgvector:pg16" : configured;
    }

    /**
     * 镜像站的名字（{@code docker.m.daocloud.io/pgvector/pgvector}）长得不像 postgres，
     * Testcontainers 会在启动前就拒绝它。名字里不带 postgres 的都得显式声明
     * "这就是 postgres"，否则换了个拉取来源就跑不起来——这个坑只在换源时出现，
     * 用官方镜像的人永远撞不到。
     */
    private static DockerImageName image() {
        DockerImageName parsed = DockerImageName.parse(imageName());
        return parsed.getRepository().endsWith("postgres")
                ? parsed
                : parsed.asCompatibleSubstituteFor("postgres");
    }

    /**
     * 镜像不在本地就整类跳过——拉不动 Docker Hub 的时候，让 {@code mvn verify}
     * 卡在下载上再变红，只会让人学会绕开这条命令。
     *
     * <p>但"跳过的绿"和"通过的绿"在构建结果里长得一模一样，这是个真风险。
     * 所以留了一个开关：{@code -Dclaw4j.dbTests=required}（CI 上就是这么跑的）
     * 会把跳过改成失败。本机默认宽容，CI 上必须有库。
     */
    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(imageName()).exec();
            return true;
        } catch (RuntimeException notAvailable) {
            String reason = "数据库测试跑不起来（" + notAvailable.getMessage() + "）。"
                    + "本机可以 docker pull " + imageName()
                    + "，或用 CLAW4J_POSTGRES_IMAGE 指向一个本地已有的镜像。";
            if ("required".equals(System.getProperty("claw4j.dbTests"))) {
                // 抛出去而不是返回 false：条件方法抛异常会让整个类变成 error，构建就红了。
                throw new IllegalStateException(reason + " 当前 claw4j.dbTests=required，"
                        + "所以这里不跳过——跳过的绿会让人以为数据库测试真的跑过。");
            }
            System.err.println("[SalesDataRepositoryTest] " + reason + " 本次跳过。");
            return false;
        }
    }
}
