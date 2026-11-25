package io.github.claw4j.agent.session;

import com.zaxxer.hikari.HikariDataSource;
import io.github.claw4j.agent.advisor.AdvisorOrder;
import io.github.claw4j.agent.persona.AgentProperties;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.github.claw4j.domain.port.SessionQuery;
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
import io.github.claw4j.model.catalog.ModelCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆的装配：把 {@code spring-ai-session} 的 JDBC 存储接到业务库所在的同一个 Postgres 上，
 * 但落在另一个 schema（ADR-0005）。
 *
 * <p>这里只声明 repository，不声明 service：{@code spring-ai-starter-session-jdbc} 自带的两个
 * 自动配置里，{@code SessionService} 那个可以直接用（它带 {@code @ConditionalOnMissingBean}，
 * 且会捡起这里声明的 repository）；repository 那个不用——它拿容器里那个主 {@code DataSource}，
 * 会话表会落到 public。本类声明的 bean 先注册（自动配置按类名排序，{@code io.github.claw4j.*}
 * 排在 {@code org.springaicommunity.*} 前面），它那一份就此退让。
 *
 * <p>会话要有自己的连接池，原因见 {@link SessionSchema}：主连接池的 {@code search_path} 不动，
 * 业务查询与 Flyway 的落点都不变。
 *
 * <p>会话连接池不注册成 bean：容器里一旦有两个 {@code DataSource}，
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} 就不再成立，{@code JdbcClient} 与
 * 事务管理器会整套安静地不装配。所以这个池由配置类自己持有，上下文关闭时自己关掉
 * （见 {@link #destroy()}）。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({SessionService.class, JdbcSessionRepository.class})
@ConditionalOnSingleCandidate(DataSource.class)
@EnableScheduling
@EnableConfigurationProperties({DataSourceProperties.class, SessionMemoryProperties.class,
        SessionServiceProperties.class, SessionScopeProperties.class,
        SessionVisibilityProperties.class})
public class SessionMemoryConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryConfiguration.class);

    private volatile DataSource sessionDataSource;

    /**
     * 会话存储。返回类型是接口 {@link SessionRepository}，装配的是 JDBC 实现。
     *
     * @param dataSource 主连接池的配置，用来派生会话池
     * @param properties 会话存储的可调项
     * @return JDBC 会话仓储，读写会话 schema 下的两张表
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
     * 会话服务。这一份顶掉 starter 自带的那份（靠类名排序先注册），只为把 schema 与别名的
     * 配置接到我们那个池上；行为是 {@link DefaultSessionService} 自己的，不包任何降级
     * （2026-11-25，票 01）：存储不可用就是这一轮不可用——记忆与回答走同一条链，
     * 假装"这一轮没有记忆"只会让排障从"库挂了"变成"机器人突然失忆"。
     *
     * @param repository 会话仓储
     * @param properties starter 的会话配置项
     * @return 会话服务
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
        return delegate;
    }

    /**
     * 记忆 Advisor。它在容器的 Advisor 集合里，就会被
     * {@code AgentAutoConfiguration#chatClient} 的 {@code defaultAdvisors} 收走，
     * 调用方不需要知道有记忆这回事。
     *
     * <p>压缩由它自己做（2026-11-25，票 01）：{@code compactionTrigger} 在整轮结束、且这一轮
     * 没请求工具调用时判一次。此前是 {@code SessionCompactor} 在进模型之前压，两边都开会一轮
     * 压两次——现在只留框架这一条。
     *
     * <p>order 见 {@link AdvisorOrder#SESSION_MEMORY}：排在入站拦截之后、其余一切之前，
     * 于是 {@code after} 它最后收尾——写进会话表的才是脱敏之后的文本。
     *
     * @param sessionService 会话服务
     * @param properties 压缩参数：窗口、阈值比例、保留条数
     * @param chatModel 生成摘要用的模型
     * @param catalog 模型目录，可能为 null（这个部署没装配模型层）
     * @return 记忆 Advisor
     */
    @Bean
    @ConditionalOnMissingBean(SessionMemoryAdvisor.class)
    public SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService,
                                                     SessionMemoryProperties properties,
                                                     ChatModel chatModel,
                                                     ObjectProvider<ModelCatalog> catalog) {
        ChatClient summaryClient = ChatClient.builder(chatModel).build();
        int window = contextWindowOf(catalog.getIfAvailable(), properties.contextWindow());
        log.info("会话压缩已启用：窗口 {} token、到 {} 触发（{}%）、保留最近 {} 条事件",
                window, properties.compactionThreshold(window),
                Math.round(properties.triggerRatio() * 100), properties.eventsToKeep());
        return SessionMemoryAdvisor.builder(sessionService)
                .order(AdvisorOrder.SESSION_MEMORY)
                .compactionTrigger(TokenCountTrigger.builder()
                        .threshold(properties.compactionThreshold(window))
                        .build())
                .compactionStrategy(RecursiveSummarizationCompactionStrategy.builder(summaryClient)
                        .maxEventsToKeep(properties.eventsToKeep())
                        .onSummarizationFailure(request -> log.warn(
                                "摘要生成失败，会话 {} 这一轮不压：{}",
                                request.session().id(), request.currentEventCount()))
                        .build())
                .build();
    }

    /**
     * 侧边栏读侧（票 02）：列表与逐轮都走框架那两个读入口，不新增存储。
     *
     * <p>端口在 core、实现在这里、入口在通道，与审计那条线同一个安排（ADR-0010）。
     *
     * @param sessions 会话服务
     * @return 只读查询
     */
    @Bean
    @ConditionalOnMissingBean(SessionQuery.class)
    public SessionQuery sessionQuery(SessionService sessions) {
        return new JdbcSessionQuery(sessions);
    }

    /**
     * 过期会话清理的调度入口（票 01）。判据在框架那一侧（{@code expires_at}），
     * 这里只负责"什么时候跑"——删掉自己那套判据之后，如果连调度也不留，TTL 就只是一列没人读的时间。
     *
     * @param sessions 会话服务
     * @return 清理任务
     */
    @Bean
    @ConditionalOnMissingBean(SessionExpirySweep.class)
    public SessionExpirySweep sessionExpirySweep(SessionService sessions) {
        return new SessionExpirySweep(sessions);
    }

    /**
     * 当次生效的上下文窗口：目录优先，会话配置兜底。
     *
     * @param catalog  模型目录，可能为 null（这个部署没装配模型层）
     * @param fallback 会话配置里的那个数
     * @return token 数
     */
    private static int contextWindowOf(ModelCatalog catalog, int fallback) {
        if (catalog == null) {
            return fallback;
        }
        int window = catalog.primaryRow().getContextWindow();
        return window <= 0 ? fallback : window;
    }

    /**
     * 跨会话检索（票 05）：{@code visibility=agent|all} 时召回走这一条。
     *
     * <p>用主连接池、表名写全限定：与会话状态行、会话目标同一类理由——框架那条路要另开一个池
     * 把 {@code currentSchema} 指过去（见 {@link SessionSchema}），而本仓库自己的 SQL 带上
     * schema 就不依赖连接的 {@code search_path}。
     *
     * <p>schema 从 {@link SessionMemoryProperties#schema()} 取，不写字面量：
     * {@code claw4j.advisor.session.schema} 可配，写死的话改配之后这条 SQL 会报
     * {@code BadSqlGrammarException}，而那一档的异常被降级成"没找到"。
     * 会话状态行、会话目标、清理任务三处都从同一个键取。
     *
     * @param jdbc    JDBC 句柄，走主连接池
     * @param session 会话存储配置，取 schema
     * @return 跨会话检索实现
     */
    @Bean
    @ConditionalOnMissingBean(CrossSessionEvents.class)
    public CrossSessionEvents crossSessionEvents(JdbcTemplate jdbc, SessionMemoryProperties session) {
        return new JdbcCrossSessionEvents(jdbc, session.schema());
    }

    /**
     * 召回工具（M3-03）：在会话历史里按关键词回查，范围由
     * {@code claw4j.tools.sessions.visibility} 决定（票 05）。
     *
     * <p>注册成 {@code ToolCallback} 就会被 {@code AgentAutoConfiguration#chatClient} 收进清单，
     * 调用方不需要知道有"回忆"这个能力。工具检索开着时它一样会先被索引。
     *
     * <p>工具是单例，身份与会话都不是：这个 bean 上没有绑定任何人、也没绑定哪一段对话，
     * 每次调用都从 {@code ToolContext} 里取当前身份与会话 id（见 {@link RecallSessionTool}）。
     * 把身份或会话绑在 bean 上，等于所有人的上下文里都装着第一个人的历史。
     *
     * <p>库挂的时候这里会抛（2026-11-25，票 01）：不再有一层把它翻译成"没找到"。见
     * {@link SessionRecallScope#events}。
     *
     * <p>启动日志要说出生效范围：默认 {@code all} 意味着 {@code read-only} 身份可以召回别人的
     * 会话。不写这一行的话，"现在到底是哪一档"只能靠翻 yml，而 yml 里的值可能被环境变量覆盖。
     * OpenClaw 对应的检查要求"两个以上 agent 且解析成 all"（{@code docs/cli/security.md:39}），
     * 本仓库只有一个 agent 命名空间，那条永远不触发，所以改用启动日志兜底。
     *
     * @param sessionService 会话服务，已包降级
     * @param across         跨会话检索
     * @param properties     可见性配置
     * @return 召回工具
     */
    @Bean
    @ConditionalOnMissingBean(RecallSessionTool.class)
    public RecallSessionTool recallSessionTool(SessionService sessionService,
                                               CrossSessionEvents across,
                                               SessionVisibilityProperties properties) {
        SessionVisibility visibility = properties.scope();
        if (visibility == SessionVisibility.ALL) {
            log.info("会话可见性 = all（照 OpenClaw 的默认值）：recall_session 可以跨用户召回，"
                    + "结果里每条都带它来自哪一段。多用户、角色不同的部署要收紧，"
                    + "配 claw4j.tools.sessions.visibility=self（或 tree / agent）。");
        }
        else {
            log.info("会话可见性 = {}：{}（claw4j.tools.sessions.visibility）",
                    visibility.key(), visibility.description());
        }
        return new RecallSessionTool(new SessionRecallScope(sessionService, across, visibility));
    }

    /**
     * 召回工具的画像：全部（{@link ToolTier#all()}）。
     *
     * <p>它读的是"我自己刚才说过什么"，不出网、不碰业务表，与读进程状态同一类。任何画像里
     * 少掉它都会让长对话丢掉连贯性，而那不是一项业务能力——是地基。
     *
     * @return 把 {@code recall_session} 归到全部画像的登记表
     */
    @Bean
    public ToolTierRegistry recallSessionTierRegistry() {
        return ToolTierRegistry.allProfilesFor(List.of(RecallSessionTool.TOOL_NAME));
    }

    /**
     * 会话连接池：与主库同一个实例、同一份凭据，只多一个 {@code currentSchema}。
     * 刻意做成静态工厂而不是 bean：注册成 bean 会让容器里出现两个 {@code DataSource}，
     * 那些 {@code @ConditionalOnSingleCandidate(DataSource.class)} 的自动配置
     * （{@code JdbcClient}、事务管理器）会整套不装配。
     *
     * <p>包内可见，是为了让测试直接问它"会话指到哪个 schema 去了"——这件事在容器里看不见
     * （池是内部的），而拼错的表现是表落到 public，不报错。
     *
     * @param dataSource 主连接池的配置
     * @param properties 会话存储配置，取 schema 与池大小
     * @return 指到会话 schema 的连接池
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
