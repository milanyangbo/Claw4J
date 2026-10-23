package io.github.claw4j.agent.session;

import io.github.claw4j.agent.advisor.PiiRedactionAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话存储装配到的是什么。
 *
 * <p>这里跑的是<b>真的自动配置集合</b>，不是手塞 bean：官方 starter 自带
 * {@code JdbcSessionRepositoryAutoConfiguration} 与 {@code SessionServiceAutoConfiguration}，
 * 本模块自己那一份 {@code SessionRepository} 必须能顶掉官方那份
 * （官方那份拿主 DataSource，会话表会落到 public）。能不能顶掉，只有把两边一起跑起来才知道。
 *
 * <p>不需要真数据库：Hikari 是懒连接，装配阶段不会真连。
 */
class SessionMemoryConfigurationTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/claw4j";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SessionMemoryConfiguration.class,
                    JdbcSessionRepositoryAutoConfiguration.class,
                    SessionServiceAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=" + URL,
                    // 与 application.yml 保持一致：建表只有 Flyway 一个入口
                    "spring.ai.session.repository.jdbc.initialize-schema=never")
            .withBean(DataSource.class, () -> new DriverManagerDataSource(URL, "claw4j", "claw4j"));

    /**
     * "不回退到自己写个 Map 存着"这条硬要求的落点：装配出来的必须是 JDBC 实现。
     */
    @Test
    @DisplayName("会话存储是 JDBC 的，不是内存版")
    void 装配出的是jdbc实现() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SessionRepository.class);
            assertThat(context.getBean(SessionRepository.class))
                    .isInstanceOf(JdbcSessionRepository.class);
            // 内存版连 bean 都不该有：它有就是有人绕开了装配
            assertThat(context.getBeansOfType(InMemorySessionRepository.class)).isEmpty();
        });
    }

    /** 官方那份 repository 自动配置必须退让，否则会话表会同时被两个 DataSource 写。 */
    @Test
    @DisplayName("官方那份 repository 自动配置被顶掉，只有一个")
    void 只有一个会话存储() {
        contextRunner.run(context -> {
            assertThat(context.getBeanNamesForType(SessionRepository.class)).hasSize(1);
            // SessionService 仍由官方装配，但它吃的是我们这份 repository
            assertThat(context).hasSingleBean(SessionService.class);
        });
    }

    @Test
    @DisplayName("记忆 Advisor 在链上，且排在脱敏之前收尾")
    void 记忆advisor的order() {
        contextRunner.run(context -> {
            SessionMemoryAdvisor memory = context.getBean(SessionMemoryAdvisor.class);

            // 链上 Advisor 按 order 升序排 before，降序排 after：
            // 记忆 order 更小 = 它最后收尾 = 它写进库里的是脱敏之后的文本。
            assertThat(memory.getOrder())
                    .isEqualTo(SessionMemoryConfiguration.ADVISOR_ORDER)
                    .isLessThan(PiiRedactionAdvisor.ORDER)
                    .isLessThan(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER);
            assertThat(memory).isInstanceOf(Advisor.class);
        });
    }

    /**
     * 会话池指向哪个 schema 在容器里看不见（它是内部的），所以直接问工厂方法。
     * 拼错不报错，只会让会话表落到 public——那是 ADR-0005 被悄悄违背的样子。
     */
    @Test
    @DisplayName("会话连接池带着 currentSchema，落点不是 public")
    void 会话连接池指向会话schema() {
        contextRunner.run(context -> {
            DataSourceProperties properties = context.getBean(DataSourceProperties.class);
            try (var pooled = SessionMemoryConfiguration.sessionDataSource(
                    properties, SessionMemoryProperties.defaults())) {
                assertThat(pooled.getJdbcUrl()).contains("currentSchema=" + SessionSchema.DEFAULT_SCHEMA);
            }
        });
    }

    /** schema 可配：换部署环境时不该改代码。 */
    @Test
    @DisplayName("schema 可通过配置改")
    void schema可配() {
        contextRunner.withPropertyValues("claw4j.session.schema=another_schema").run(context -> {
            DataSourceProperties properties = context.getBean(DataSourceProperties.class);
            try (var pooled = SessionMemoryConfiguration.sessionDataSource(
                    properties, context.getBean(SessionMemoryProperties.class))) {
                assertThat(pooled.getJdbcUrl()).contains("currentSchema=another_schema");
            }
        });
    }

    /**
     * 没有数据源时不装配——"没有 Docker 也能启动"这条承诺不能被会话存储破坏。
     */
    @Test
    @DisplayName("没有数据库时不装配，应用照样起得来")
    void 没有数据源时不装配() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SessionMemoryConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(SessionRepository.class));
    }
}
