package io.github.claw4j.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "应用起来就迁到最新版"这件事由机器看着，而不是靠人记得打开开关。
 *
 * <p>为什么值得单独一个测试：Boot 4 把 Flyway 的自动配置拆成了独立 artifact，
 * 缺了它时 {@code spring.flyway.enabled=true} 是一句**没有任何东西在执行**的配置——
 * 不报错、不警告、日志里不出现 Flyway 三个字，只是表一直不建。真实联调时的症状是
 * 业务查询报"连不上库"，看的人会去查网络而不是查迁移。这种失败只靠"跑一遍真库"才暴露得出来，
 * 所以这个测试必须真的连一个 Postgres，而不是断言某个 bean 存在。
 *
 * <p>断言钉在两处：迁移记录的版本集合，以及会话表真的落在 {@code claw4j_session} 这个 schema 上。
 * 后者是 06 号票的落点——表建到 public 去的话，同库不同 schema 这条约定就白写了。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("postgresImageIsAvailable")
class SchemaMigrationOnStartupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.container();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContainerDataSource.class)
            .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
            .withPropertyValues("spring.flyway.enabled=true");

    @Test
    @DisplayName("启动上下文就把三个版本的迁移都跑掉")
    void 启动时迁到最新版() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            JdbcClient jdbc = JdbcClient.create(context.getBean(DataSource.class));

            assertThat(jdbc.sql("select version from flyway_schema_history where success order by version")
                    .query(String.class)
                    .list())
                    .containsExactly("1", "2", "3");
            // 业务表在 business，会话表在 claw4j_session：同库不同 schema（ADR-0005）
            assertThat(jdbc.sql("select count(*) from information_schema.tables"
                            + " where table_schema = 'claw4j_session'")
                    .query(Long.class)
                    .single())
                    .isEqualTo(2L);
            assertThat(jdbc.sql("select count(*) from business.sales_order")
                    .query(Long.class)
                    .single())
                    .isNotZero();
        });
    }

    /**
     * 再启一次不该重放：种子是 {@code insert}，重放一次金额就翻倍，
     * 而"金额翻倍"这种错在任何一层看起来都像是对的。
     */
    @Test
    @DisplayName("同一个库再启动一次不会重放迁移")
    void 重复启动不重放() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            JdbcClient jdbc = JdbcClient.create(context.getBean(DataSource.class));
            assertThat(jdbc.sql("select count(*) from flyway_schema_history where success")
                    .query(Long.class)
                    .single())
                    .isEqualTo(3L);
        });
    }

    @SuppressWarnings("unused")
    static boolean postgresImageIsAvailable() {
        return PostgresTestSupport.databaseAvailable(SchemaMigrationOnStartupTest.class.getSimpleName());
    }

    /** 把容器的连接交给自动配置：这里不给 URL，逼它走"有 DataSource 就用它"这条真路径。 */
    @Configuration(proxyBeanMethods = false)
    static class ContainerDataSource {

        @Bean
        DataSource dataSource() {
            return PostgresTestSupport.dataSource(POSTGRES);
        }
    }
}
