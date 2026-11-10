package io.github.claw4j.agent.session;

import com.zaxxer.hikari.HikariDataSource;
import io.github.claw4j.agent.goal.GoalTools;
import io.github.claw4j.agent.goal.JdbcSessionGoalStore;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.persona.AgentProperties;
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
        SessionServiceProperties.class, SessionResetProperties.class,
        SessionMaintenanceProperties.class, SessionVisibilityProperties.class})
public class SessionMemoryConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryConfiguration.class);

    /**
     * 记忆 Advisor 的 order：除入站拦截外全链最小，也就是 {@code before} 第二早、{@code after} 最后。
     *
     * <p>"after 最后"是这里的要害：链的收尾按 order 降序跑，谁 order 小谁最后收尾。它最后收尾，
     * 写进会话表的才是 {@link io.github.claw4j.agent.advisor.PiiRedactionAdvisor} 已经打过码的
     * 文本。反过来写，出站是干净的、会话表里躺的是明文，而且没人会去看。
     *
     * <p>让出 {@code HIGHEST_PRECEDENCE} 这一个名额，是给 {@code SafeGuardAdvisor}：
     * 它必须比记忆更外层，否则记忆拼上来的历史会被入站拦截一起检查，历史里出现过的词会把这个
     * 会话永久锁死（判据在 {@code GuardAutoConfiguration#ORDER}）。记忆往后挪一格不影响
     * "after 最后"——它仍然比脱敏（{@code HIGHEST_PRECEDENCE + 200}）小。
     */
    public static final int ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

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
     * 会话服务。这一份顶掉 starter 自带的那份（同样靠类名排序先注册），换来的唯一区别是包了
     * 一层 {@link FaultTolerantSessionService}：存储不可用时降级为"这一轮没有记忆"，
     * 而不是让整条链失败。配置项（{@code spring.ai.session.*}）仍然照 starter 那份读。
     *
     * @param repository 会话仓储
     * @param properties starter 的会话配置项
     * @return 包了降级逻辑的会话服务
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
     * <p>不在这里配 {@code compactionTrigger} / {@code compactionStrategy}：配上去之后
     * Advisor 会在整轮结束、且这一轮没请求工具调用时再调一次 {@code SessionService.compact}，
     * 与 {@link SessionCompactor} 在进模型之前的那一次重复。压缩只走一条路。
     *
     * @param sessionService 会话服务，已包降级
     * @return 记忆 Advisor
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
     * <p>摘要用一个单独建的 {@code ChatClient}，不挂任何 Advisor：挂上记忆 Advisor 就是
     * "压缩时写事件 → 再次触发压缩"的环；挂上鉴权则摘要会因为没有 Principal 被拒。
     *
     * <p>这个客户端用的是主模型。用便宜那一路要先回答"哪一路算便宜"，那是配置问题，
     * 记在票里（见 ADR-0006）。
     *
     * @param sessionService 会话服务
     * @param properties     压缩参数：窗口、阈值比例、保留条数
     * @param chatModel      生成摘要用的模型
     * @param meterRegistry  可选，装不装 actuator 只影响指标报不报得出去
     * @return 压缩器
     */
    @Bean
    @ConditionalOnMissingBean(SessionCompactor.class)
    public SessionCompactor sessionCompactor(SessionService sessionService,
                                             SessionMemoryProperties properties,
                                             ChatModel chatModel,
                                             ObjectProvider<MeterRegistry> meterRegistry) {
        ChatClient summaryClient = ChatClient.builder(chatModel).build();
        // 三个数是"改了要重启才生效"的，所以放在启动期而不是每轮。它也是"换主模型时改没改
        // context-window"唯一看得见的地方——本仓库没有 OpenClaw 那样的模型目录可查
        // （它靠 openclaw models list 与协议里的 contextWindows 暴露，见票 05 第二节）。
        log.info("会话压缩已启用：窗口 {} token、到 {} 触发（{}%）、保留最近 {} 条事件",
                properties.contextWindow(), properties.compactionThreshold(),
                Math.round(properties.triggerRatio() * 100), properties.eventsToKeep());
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
     * 旧会话怎么清（ADR-0026 的收尾，判据照 OpenClaw 的 {@code session.maintenance}）。
     *
     * <p>用主连接池：表名写全限定，与会话仓储那一路不同（那边框架生成的 SQL 不带前缀，
     * 必须另开一个池把 {@code currentSchema} 指过去）。schema 段从
     * {@link SessionMemoryProperties#schema()} 取，不写字面量：写死的话改配之后框架往新 schema
     * 写、这里清旧的，日志全绿而一行都没清对。
     *
     * <p>把 {@code ScheduleProperties} 依赖进来，是因为 cron 的会话留存
     * （{@code claw4j.schedule.session-retention}）由清理这一侧读，调度那一侧不读。
     * 放在 schedule 命名空间是为了与 OpenClaw 的 {@code cron.sessionRetention} 同位置，
     * 写在这里则是为了"谁读谁写"能一眼看出来。
     *
     * @param jdbc JDBC 句柄，走主连接池
     * @param properties 清理判据与档位
     * @param schedule 定时运行的会话留存
     * @param meterRegistry 可选，装不装 actuator 只影响指标报不报得出去
     * @param session 会话存储配置，取 schema
     * @return 清理任务
     */
    @Bean
    @ConditionalOnMissingBean(SessionMaintenance.class)
    public SessionMaintenance sessionMaintenance(JdbcTemplate jdbc,
                                                 SessionMaintenanceProperties properties,
                                                 io.github.claw4j.agent.schedule.ScheduleProperties schedule,
                                                 ObjectProvider<MeterRegistry> meterRegistry,
                                                 SessionMemoryProperties session) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        return new SessionMaintenance(jdbc, properties, schedule,
                registry == null ? Metrics.globalRegistry : registry, session.schema());
    }

    /**
     * 过期会话清理的调度（M3-04）。
     *
     * <p>{@code spring.ai.session.time-to-live} 只是写在会话行上的时间戳，没有任何东西读它，
     * 而且它写的是"建行 + TTL"，不随活动刷新。判"该不该清"的是 {@link SessionMaintenance}
     * 里的"多久没动"，这个 bean 只管"什么时候跑"。
     *
     * @param maintenance 清理判据
     * @return 定时清理任务
     */
    @Bean
    @ConditionalOnMissingBean(SessionCleanup.class)
    public SessionCleanup sessionCleanup(SessionMaintenance maintenance) {
        return new SessionCleanup(maintenance);
    }

    /**
     * 跨会话检索（票 05）：{@code visibility=agent|all} 时召回走这一条。
     *
     * <p>用主连接池、表名写全限定：与会话状态行、会话目标同一类理由——框架那条路要另开一个池
     * 把 {@code currentSchema} 指过去（见 {@link SessionSchema}），而本仓库自己的 SQL 带上
     * schema 就不依赖连接的 {@code search_path}。
     *
     * <p>schema 从 {@link SessionMemoryProperties#schema()} 取，不写字面量：
     * {@code claw4j.session.schema} 可配，写死的话改配之后这条 SQL 会报
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
     * <p>它拿到的是包了降级的 {@link FaultTolerantSessionService}：库挂的时候这里是"没找到"，
     * 不是整轮失败。跨会话那一档也降级成同样的一句话（见 {@link SessionRecallScope#events}），
     * 否则同一个库挂掉，配 {@code self} 的部署答"没找到"、配 {@code all} 的部署整轮失败。
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
     * 召回工具的权限。动作名与工具名同值，不复用 {@code read}：能查销售单与能回忆自己说过什么
     * 是两件事，合成一个动作名等于给所有能查数的人顺手开了历史回查
     * （细节见 {@link RecallSessionTool#ACTION}）。
     *
     * @return 只登记 {@code recall_session} 一个动作的权限表
     */
    @Bean
    public ToolPermissionRegistry recallSessionPermission() {
        return ToolPermissionRegistry.of(
                Map.of(RecallSessionTool.TOOL_NAME, RecallSessionTool.permission()));
    }

    /**
     * 召回工具的档位：{@link ToolTier#MINIMAL}。
     *
     * <p>它读的是"我自己刚才说过什么"，不出网、不碰业务表，与读进程状态同一类。归进更高的档会
     * 让"只聊天"的部署丢掉长对话的连贯性，而那不是一项业务能力。
     *
     * @return 把 {@code recall_session} 归到 MINIMAL 的档位表
     */
    @Bean
    public ToolTierRegistry recallSessionTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(RecallSessionTool.TOOL_NAME), ToolTier.MINIMAL);
    }

    /**
     * 会话状态行（ADR-0026，票 06 起是 {@code session_entry}）。表在会话 schema 里，
     * 但这个 bean 用的是主连接池。
     *
     * <p>与会话仓储那一路不同：框架生成的 SQL 不带前缀，所以那边必须另开一个池把
     * {@code currentSchema} 指过去；这里的 SQL 全在自己手里、表名写全限定，不靠连接的
     * {@code search_path}。少一个 {@code DataSource} 的收益是实的——容器里出现两个，
     * {@code @ConditionalOnSingleCandidate(DataSource.class)} 就不再成立，一整套 JDBC
     * 自动配置会安静地不装配。
     *
     * <p>这一行也是目标的家：目标的 17 个列与段在同一行，换段时它们一起清空。因此目标存储不再
     * 单独建表，也没有"换段之后旧目标还在"这种中间态。
     *
     * <p>schema 段从 {@link SessionMemoryProperties#schema()} 取，与框架那一路同源：写死的话
     * 改配之后状态行留在旧 schema，换段判定与记忆读的不是同一批表。
     *
     * @param jdbc    JDBC 句柄，走主连接池
     * @param session 会话存储配置，取 schema
     * @return 状态行存储
     */
    @Bean
    @ConditionalOnMissingBean(SessionEntryStore.class)
    public SessionEntryStore sessionEntryStore(JdbcTemplate jdbc, SessionMemoryProperties session) {
        return new JdbcSessionEntryStore(jdbc, session.schema());
    }

    /**
     * 段怎么算：跨天或空闲，谁先到算谁。
     *
     * <p>时钟显式传 {@code systemUTC}：跨天边界按业务时区算
     * （{@link SessionResetProperties#dailyResetAt}），而 {@code Instant} 本身没有时区，所以用哪个
     * {@code Clock} 不影响"边界是几点"——影响它的只有 zone 参数。写死 UTC 是让这一点在代码里
     * 显式，不靠"反正 systemDefault 也对"。
     *
     * <p>{@code MeterRegistry} 走 {@code ObjectProvider}：装不装 actuator 只影响状态行失败的计数
     * 报不报得出去，不影响该不该换段。
     *
     * @param store         状态行存储
     * @param reset         换段规则
     * @param agent         智能体配置，取业务时区
     * @param meterRegistry 可选，用于失败计数
     * @return 段解析器
     */
    @Bean
    @ConditionalOnMissingBean(SessionEntries.class)
    public SessionEntries sessionEntries(SessionEntryStore store,
                                         SessionResetProperties reset,
                                         AgentProperties agent,
                                         ObjectProvider<MeterRegistry> meterRegistry) {
        return new SessionEntries(store, reset, agent.zone(), Clock.systemUTC(),
                meterRegistry.getIfAvailable());
    }

    /**
     * 会话目标（ADR-0027 决定三）。表在会话 schema 里，用的是主连接池。
     *
     * <p>与会话状态行同一条理由写全限定表名：不靠连接的 search_path，也就不需要第二个
     * {@code DataSource}。schema 段同样从 {@link SessionMemoryProperties#schema()} 取。
     *
     * @param jdbc          JDBC 句柄，走主连接池
     * @param meterRegistry 可选，用于读写失败计数
     * @param session       会话存储配置，取 schema
     * @return 目标存储
     */
    @Bean
    @ConditionalOnMissingBean(SessionGoalStore.class)
    public SessionGoalStore sessionGoalStore(JdbcTemplate jdbc,
                                             ObjectProvider<MeterRegistry> meterRegistry,
                                             SessionMemoryProperties session) {
        return new JdbcSessionGoalStore(jdbc, session.schema(), meterRegistry.getIfAvailable());
    }

    /**
     * 三个目标工具（{@code get_goal} / {@code create_goal} / {@code update_goal}）。
     *
     * <p>不跟着 run 存储进退：目标挂在会话上，run 关掉之后"这一段对话在往哪儿走"照样成立。
     * 这也是它与原来那个 {@code update_plan} 最实质的区别——后者跟着
     * {@code claw4j.persistence.enabled} 一起消失。
     *
     * @param goals 目标存储
     * @return 目标工具集
     */
    @Bean
    @ConditionalOnMissingBean(GoalTools.class)
    public GoalTools goalTools(SessionGoalStore goals) {
        return new GoalTools(goals);
    }

    /**
     * 三个目标工具的权限。动作名与工具名同值，不复用 {@code read}：它们改的是这一段对话的走向，
     * 不是业务表。
     *
     * @return 目标工具的权限表
     */
    @Bean
    public ToolPermissionRegistry goalPermissionRegistry() {
        return ToolPermissionRegistry.of(GoalTools.permissions());
    }

    /**
     * 三个目标工具的档位：{@link ToolTier#MINIMAL}。
     *
     * <p>它们不碰业务表、不出网，读写的都是"这一段对话自己的状态"，与召回同一个档。归进更高的档
     * 会让"只聊天"的部署丢掉目标，而目标恰恰是纯聊天场景最需要的东西（一段要做很多轮的活）。
     *
     * @return 把三个目标工具归到 MINIMAL 的档位表
     */
    @Bean
    public ToolTierRegistry goalTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(GoalTools.GET, GoalTools.CREATE, GoalTools.UPDATE),
                ToolTier.MINIMAL);
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
