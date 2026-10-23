package io.github.claw4j.agent.session;

import com.zaxxer.hikari.HikariDataSource;
import org.springaicommunity.session.autoconfigure.SessionServiceProperties;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.ai.session.jdbc.PostgresJdbcSessionRepositoryDialect;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * 会话记忆的装配：把 {@code spring-ai-session} 的 JDBC 存储接到业务库所在的同一个 Postgres 上，
 * 但落在另一个 schema（ADR-0005）。
 *
 * <p><b>为什么这里只声明 repository，不声明 service</b>：{@code spring-ai-starter-session-jdbc}
 * 自带的两个自动配置里，{@code SessionService} 那个直接用（它带
 * {@code @ConditionalOnMissingBean}，且会捡起这里声明的 repository）；
 * repository 那个不用——它拿的是容器里那个主 {@code DataSource}，
 * 会话表会落到 public，正是 ADR-0005 要避免的。我们声明的 bean 先注册
 * （自动配置按类名排序，{@code io.github.claw4j.*} 排在 {@code org.springaicommunity.*} 前面），
 * 它那一份就此退让。这不是绕开它，是它留的口子。
 *
 * <p><b>为什么会话要有自己的连接池</b>：见 {@link SessionSchema}。主连接池的
 * {@code search_path} 不动，业务查询与 Flyway 的落点都不变。
 *
 * <p><b>会话连接池不注册成 bean</b>：容器里一旦有两个 {@code DataSource}，
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} 就不再成立，
 * {@code JdbcClient} 与事务管理器会整套安静地不装配——业务工具随之失效，
 * 且报错出现在完全无关的地方。所以这个池由配置类自己持有，上下文关闭时自己关掉
 * （见 {@link #destroy()}）。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({SessionService.class, JdbcSessionRepository.class})
@ConditionalOnSingleCandidate(DataSource.class)
@EnableConfigurationProperties({DataSourceProperties.class, SessionMemoryProperties.class,
        SessionServiceProperties.class})
public class SessionMemoryConfiguration implements DisposableBean {

    /**
     * 记忆 Advisor 的 order：全链最小，也就是 {@code before} 最先、{@code after} 最后。
     *
     * <p>"after 最后"是这里的要害：链的收尾按 order 降序跑，谁 order 小谁最后收尾。
     * 它最后收尾，写进会话表的才是 {@link io.github.claw4j.agent.advisor.PiiRedactionAdvisor}
     * 已经打过码的文本。反过来写，出站是干净的、会话表里躺的是明文——
     * 一份比出站更持久的泄露，而且没人会去看。
     */
    public static final int ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE;

    private volatile DataSource sessionDataSource;

    /**
     * 会话存储。返回类型是接口 {@link SessionRepository}，但装配的是 JDBC 实现——
     * {@code InMemorySessionRepository} 被架构测试挡在门外的另一半。
     */
    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    public JdbcSessionRepository sessionRepository(DataSourceProperties dataSource,
                                                   SessionMemoryProperties properties) {
        HikariDataSource pooled = sessionDataSource(dataSource, properties);
        this.sessionDataSource = pooled;

        return JdbcSessionRepository.builder()
                .jdbcTemplate(new JdbcTemplate(pooled))
                // 显式选 Postgres 方言而不是让框架去连库探测：探测要在装配阶段真连一次，
                // 而"没起数据库也能启动"是本项目的一条硬承诺（application.yml 里 Flyway 默认关着）。
                .dialect(new PostgresJdbcSessionRepositoryDialect())
                // 事务必须落在这个池上：会话的"读事件 + 追加事件"要在同一个事务里，
                // 否则两轮对话并发时会互相覆盖事件版本号。
                .transactionManager(new DataSourceTransactionManager(pooled))
                .build();
    }

    /**
     * 会话服务。这一份顶掉 starter 自带的那份（同样靠类名排序先注册），
     * 换来的唯一区别是包了一层 {@link FaultTolerantSessionService}：
     * 存储不可用时降级为"这一轮没有记忆"，而不是让整条链 500。
     * 配置项（{@code spring.ai.session.*}）仍然照官方那份读，行为不打折。
     */
    @Bean
    @ConditionalOnMissingBean(SessionService.class)
    public SessionService sessionService(SessionRepository repository,
                                         SessionServiceProperties properties) {
        SessionService delegate = DefaultSessionService.builder()
                .sessionRepository(repository)
                .allowSystemMessages(properties.isAllowSystemMessages())
                .defaultTimeToLive(properties.getTimeToLive())
                .build();
        return new FaultTolerantSessionService(delegate);
    }

    /**
     * 记忆 Advisor。它在容器的 Advisor 集合里，就会被
     * {@code AgentAutoConfiguration#chatClient} 的 {@code defaultAdvisors} 收走，
     * 调用方不需要知道有记忆这回事。
     */
    @Bean
    @ConditionalOnMissingBean(SessionMemoryAdvisor.class)
    public SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
        return SessionMemoryAdvisor.builder(sessionService)
                .order(ADVISOR_ORDER)
                .build();
    }

    /**
     * 会话连接池：与主库同一个实例、同一个凭据，只多一个 {@code currentSchema}。
     * 刻意做成静态工厂而不是 bean：注册成 bean 会让容器里出现两个 {@code DataSource}，
     * 那些 {@code @ConditionalOnSingleCandidate(DataSource.class)} 的自动配置
     * （{@code JdbcClient}、事务管理器）会整套不装配，业务工具随之失效。
     *
     * <p>包内可见是为了让测试直接问它"你到底把会话指到哪个 schema 去了"——
     * 这件事在容器里看不见（池是内部的），而拼错的表现是表落到 public，不报错。
     */
    static HikariDataSource sessionDataSource(DataSourceProperties dataSource,
                                              SessionMemoryProperties properties) {
        HikariDataSource pooled = dataSource.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .url(SessionSchema.urlWithSchema(dataSource.determineUrl(), properties.schema()))
                .build();
        pooled.setPoolName("claw4j-session");
        pooled.setMaximumPoolSize(properties.poolSize());
        return pooled;
    }

    @Override
    public void destroy() {
        DataSource dataSource = this.sessionDataSource;
        if (dataSource instanceof HikariDataSource pooled) {
            pooled.close();
        }
    }
}
