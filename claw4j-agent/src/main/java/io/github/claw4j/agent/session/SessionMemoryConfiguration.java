package io.github.claw4j.agent.session;

import com.zaxxer.hikari.HikariDataSource;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.session.autoconfigure.SessionServiceProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TokenCountTrigger;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.ai.session.jdbc.PostgresJdbcSessionRepositoryDialect;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

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
@EnableScheduling
@EnableConfigurationProperties({DataSourceProperties.class, SessionMemoryProperties.class,
        SessionServiceProperties.class})
public class SessionMemoryConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryConfiguration.class);

    /**
     * 记忆 Advisor 的 order：除入站拦截外全链最小，也就是 {@code before} 第二早、{@code after} 最后。
     *
     * <p>"after 最后"是这里的要害：链的收尾按 order 降序跑，谁 order 小谁最后收尾。
     * 它最后收尾，写进会话表的才是 {@link io.github.claw4j.agent.advisor.PiiRedactionAdvisor}
     * 已经打过码的文本。反过来写，出站是干净的、会话表里躺的是明文——
     * 一份比出站更持久的泄露，而且没人会去看。
     *
     * <p><b>为什么让出 {@code HIGHEST_PRECEDENCE} 这一个名额</b>：
     * {@code SafeGuardAdvisor} 必须比它更外层，否则它拼上来的历史会被入站拦截一起检查，
     * 历史里出现过的词会把这个会话永久锁死（判据在 {@code GuardAutoConfiguration#ORDER}）。
     * 记忆往后挪一格不影响"after 最后"——它仍然比脱敏（{@code HIGHEST_PRECEDENCE + 200}）小。
     */
    public static final int ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

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
     *
     * <p><b>刻意不在这里配 {@code compactionTrigger} / {@code compactionStrategy}</b>：
     * 那个 Builder 上确实有这两个参数，但 0.10.0 的 {@code SessionMemoryAdvisor} 从来不调
     * {@code SessionService.compact}（字节码里一次都没有）。配上去只会让读代码的人以为
     * 压缩已经生效，而实际上什么都没发生——这比不配更坏。压缩走 {@link SessionCompactor}。
     */
    @Bean
    @ConditionalOnMissingBean(SessionMemoryAdvisor.class)
    public SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
        return SessionMemoryAdvisor.builder(sessionService)
                .order(ADVISOR_ORDER)
                .build();
    }

    /**
     * 压缩器：进模型之前把历史收拾一遍（M3-02）。
     *
     * <p><b>摘要用一个单独建的 {@code ChatClient}</b>，不挂任何 Advisor：
     * 挂上记忆 Advisor 就是"压缩时写事件 → 再次触发压缩"的环；挂上鉴权则摘要会因为没有
     * Principal 被拒。它只做一件事——把一段事件变成一段摘要。
     *
     * <p>这个客户端用的是主模型。ADR-0006 的设想是"摘要走便宜那一路"，
     * 那要先回答"哪一路算便宜"，是配置问题不是这张票的范围，记在票里。
     */
    @Bean
    @ConditionalOnMissingBean(SessionCompactor.class)
    public SessionCompactor sessionCompactor(SessionService sessionService,
                                             SessionMemoryProperties properties,
                                             ChatModel chatModel,
                                             ObjectProvider<MeterRegistry> meterRegistry) {
        ChatClient summaryClient = ChatClient.builder(chatModel).build();
        var trigger = TokenCountTrigger.builder()
                // 按窗口比例算出来的数：换模型只改 context-window 一个值。
                .threshold(properties.compactionThreshold())
                .build();
        var strategy = RecursiveSummarizationCompactionStrategy.builder(summaryClient)
                .maxEventsToKeep(properties.eventsToKeep())
                .onSummarizationFailure(request -> log.warn(
                        "摘要生成失败，会话 {} 这一轮不压：{}",
                        request.session().id(), request.currentEventCount()))
                .build();
        MeterRegistry registry = meterRegistry.getIfAvailable();
        return new SessionCompactor(sessionService, trigger, strategy,
                registry == null ? Metrics.globalRegistry : registry);
    }

    /**
     * 过期会话清理（M3-04）。
     *
     * <p>{@code spring.ai.session.time-to-live} 只是写在会话行上的时间戳，没有任何东西读它。
     * 只配 TTL 不配清理 = 一条都不删，而配置看起来已经成立。
     */
    @Bean
    @ConditionalOnMissingBean(SessionCleanup.class)
    public SessionCleanup sessionCleanup(SessionService sessionService,
                                         ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        return new SessionCleanup(sessionService, registry == null ? Metrics.globalRegistry : registry);
    }

    /**
     * 跨会话召回工具（M3-03）。
     *
     * <p>注册成 {@code ToolCallback} 就会被 {@code AgentAutoConfiguration#chatClient} 收进清单，
     * 调用方不需要知道有"回忆"这个能力。工具检索开着时它一样会先被索引、
     * 模型检索到了才看得见——不影响这里的装配。
     *
     * <p><b>工具是单例，userId 不是</b>：这个 bean 上没有绑定任何人，每次调用都从
     * {@code ToolContext} 里取当前身份再造一个召回器（见 {@link RecallSessionTool}）。
     * 把身份绑在这个 bean 上，等于所有人的上下文里都装着第一个人的历史。
     *
     * <p>它拿到的是包了降级的 {@link FaultTolerantSessionService}：库挂的时候这里是
     * "没找到"，不是整轮 500。召回失败与记忆失败是同一类——都只影响这一段附加信息。
     */
    @Bean
    @ConditionalOnMissingBean(RecallSessionTool.class)
    public RecallSessionTool recallSessionTool(SessionService sessionService) {
        return new RecallSessionTool(sessionService);
    }

    /**
     * 召回工具的权限。动作名与工具名同值，**不复用 read**：
     * "能查销售单"与"能回忆自己说过什么"是两件事，合成一个动作名就等于
     * 给所有能查数的人顺手开了历史回查（细节见 {@link RecallSessionTool#ACTION}）。
     */
    @Bean
    public ToolPermissionRegistry recallSessionPermission() {
        return ToolPermissionRegistry.of(
                Map.of(RecallSessionTool.TOOL_NAME, RecallSessionTool.permission()));
    }

    /**
     * 召回工具的档位：{@link ToolTier#MINIMAL}。
     *
     * <p>它读的是"我自己刚才说过什么"，不出网、不碰业务表，与读进程状态同一类。
     * 把它归进更高的档会让"只聊天"的部署丢掉长对话的连贯性，而那不是一项业务能力。
     */
    @Bean
    public ToolTierRegistry recallSessionTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(RecallSessionTool.TOOL_NAME), ToolTier.MINIMAL);
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
