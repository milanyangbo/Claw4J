package io.github.claw4j.agent.run;

import io.github.claw4j.agent.approval.ApprovalGate;
import io.github.claw4j.agent.approval.ApprovalProperties;
import io.github.claw4j.agent.approval.ApprovalService;
import io.github.claw4j.agent.approval.JdbcApprovalLedger;
import io.github.claw4j.agent.approval.RunApprovalGate;
import io.github.claw4j.agent.audit.AuditProperties;
import io.github.claw4j.agent.audit.AuditRetention;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.schedule.ScheduleAuthorization;
import io.github.claw4j.agent.schedule.ScheduleProperties;
import io.github.claw4j.agent.schedule.ScheduleScanner;
import io.github.claw4j.agent.schedule.ScheduleStore;
import io.github.claw4j.agent.schedule.ScheduleTools;
import io.github.claw4j.agent.session.SessionResetProperties;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.ApprovalLedger;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * run / step 的装配：把 {@link JdbcRunStore} 接到主连接池上，表在 {@code claw4j_run}（V6）。
 *
 * <p><b>为什么用主连接池</b>：这两张表的 SQL 全在我们手里、全写全限定名，不需要靠连接的
 * {@code currentSchema}。会话那边必须另开一个池，是因为它的 SQL 由框架生成、不带前缀；
 * 这里没有那个约束，而少一个 {@code DataSource} 的收益是实的——容器里出现两个，
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} 就不再成立，
 * 一整套 JDBC 自动配置会安静地不装配（会话那边为此专门不把池注册成 bean）。
 *
 * <p><b>为什么默认开着但可关</b>：开着是因为 run 是审批、定时、审计的挂载点，
 * 默认没有它等于那些能力永远挂不上；可关（{@code claw4j.run.enabled=false}）是因为
 * 迁移默认是关着的（"没起数据库也能启动"是硬承诺），那种环境下每一句提问都会去撞一张不存在的表。
 * 撞上的后果由 {@link JdbcRunStore} 降级消化，但能显式关掉总比靠降级好。
 *
 * <p><b>计划工具跟着这一层一起装配</b>（A03）：计划是 step 的一种，没有 run 存储就没有
 * 可写的落点。于是 {@code claw4j.run.enabled=false} 时容器里没有 {@code update_plan}——
 * 模型面前少一个工具，比"调了才发现写不进去"好。
 *
 * <p><b>预算也在这一层</b>（A04）：额度是 run 上的一个计数，没有 run 存储就无处可记，
 * 于是关掉这一层时工具调用照常发生、只是没人记账。这与"关掉 run 就不管预算了"是同一件事——
 * 记账与限流必须同进同出，否则会出现"没记账但被拦住"这种说不清的拒绝。
 *
 * <p><b>投递也在这一层</b>（A05）：收件地址与回答全文都落在 run 上，
 * 没有 run 存储就没有"送到哪儿"与"查得到什么"。关掉这一层时结果仍从请求里回去，
 * 只是"跨通道收"与"按 id 查"两件事同时不在场——它们本来就是同一件事的两半。
 *
 * <p><b>审批账本也在这一层</b>（P01）：审批表与 run / step 同一个 schema、同一批迁移，
 * 关掉这一层就没有"有人批过没有"这个问题可问。那时标了要审批的动作一律被拦——
 * 没有审批设施的环境里，收紧的那一侧才是安全的。
 *
 * <p><b>定时任务也在这一层</b>（P04）：调度表与 run 同一批迁移，"到点"这件事的产物
 * 就是一个 run。关掉这一层时没有可跑的落点，于是扫描任务也不装配——
 * 让一个跑不起来的扫描器每 30 秒记一次失败，等于给运维制造噪声。
 *
 * <p><b>定时任务的三个工具跟着调度一起进退</b>（P05）：能建任务、却没有东西会跑它，
 * 等于对用户说"已经定好了"而实际不会发生——所以 {@code claw4j.schedule.enabled=false}
 * 时它们与扫描器一起消失，模型面前少三个工具。
 *
 * <p><b>审计也在这一层</b>（P07）：一次工具调用一行，写的就是 {@code agent_step}——
 * 另建一张审计表等于让同一件事有两个答案（ADR-0023）。它跟着 run 存储进出：
 * 没有存储就没有可写的行，两个接缝拿到的都是 null，判定照常、只是事后查不到。
 * 保留期（默认 90 天）也在这里：{@code AuditRetention} 删的是整件旧事，
 * step 跟着 run 级联，所以审计不会活得比它的执行记录更久。
 *
 * <p><b>这里自己开 {@code @EnableScheduling}</b>：缓存那边已经开过一次，重复一次无害
 * （与 {@code CacheAutoConfiguration} 同一个判断），而依赖别人开的话，只装了这一层的
 * 部署里 {@code @Scheduled} 会安静地不生效——"配好了但从来不跑"是最难查的那种。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "claw4j.run", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@EnableConfigurationProperties({RunBudgetProperties.class, ApprovalProperties.class,
        ScheduleProperties.class, AuditProperties.class, SessionResetProperties.class})
public class RunPersistenceConfiguration {

    @Bean
    @ConditionalOnMissingBean(RunStore.class)
    public RunStore runStore(JdbcTemplate jdbc, ObjectProvider<MeterRegistry> registry) {
        // registry 缺席（没装 actuator）不是错误：照常落库，只是没有指标可报。
        return new JdbcRunStore(jdbc, registry.getIfAvailable());
    }

    /**
     * 审计的落点（P07）：一次工具调用一行，写在 {@code agent_step} 上。
     *
     * <p><b>为什么跟着 run 存储一起装</b>：审计行就是 step，没有 run 存储就没有可写的行。
     * 关掉这一层时两个接缝拿到的都是 null——判定照常，只是事后查不到
     * （与审批、预算、投递同一个判断：能力不在场，而不是假装它在）。
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallAudit.class)
    public ToolCallAudit toolCallAudit(RunStore runs) {
        return new ToolCallAudit(runs);
    }

    /**
     * 保留期到了就清（P07）：90 天这件事只有配一个数是不够的，得有人去删。
     *
     * <p>与缓存、会话那两个清理任务同一个形状（"配了 TTL 不等于会清理"已经踩过三次）。
     * 删的是 run 与审批——step 跟着 run 级联，于是不会出现"有审计行、但它指向的执行记录没了"。
     */
    @Bean
    @ConditionalOnMissingBean(AuditRetention.class)
    public AuditRetention auditRetention(JdbcTemplate jdbc, AuditProperties properties,
                                         ObjectProvider<MeterRegistry> registry) {
        return new AuditRetention(jdbc, properties, registry.getIfAvailable());
    }

    /**
     * 审批账本（P01、P02）：鉴权 Advisor 拿它兑现"这个人这个工具的这一种用法有没有批准"。
     *
     * <p><b>为什么跟着 run 存储一起装</b>：审批表与 run 在同一个 schema 下（V10、V11），
     * 迁移是同一批，开关也是同一个。而它缺席时 Advisor 退到 {@link ApprovalGate#denyAll()}——
     * 标了要审批的动作照样被拦，只是没人能批准，也就是"这个部署没开审批"应有的样子。
     *
     * <p>有效期（默认十分钟）走 {@code claw4j.approval.ttl}：它是策略，写在配置里，
     * 不写在表上——表上一个默认值会让"改了配置但库里还是十分钟"这种偏差看不见。
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalLedger.class)
    public ApprovalLedger approvalLedger(JdbcTemplate jdbc, ApprovalProperties properties) {
        return new JdbcApprovalLedger(jdbc, properties.getTtl().toMillis());
    }

    /**
     * 审批门：Advisor 只认这两个动作（兑现、登记）。
     *
     * <p>它把"登记一条"与"把这件事停在这一点"合成一次调用——
     * 分成两次的话，早晚有一处只做了前半截，而那种状态是"有人批了但这件事不会醒"。
     *
     * <p><b>卡片能力从容器里取</b>（P03）：哪个通道能推卡片是那个通道自己的事
     * （飞书有交互卡片，HTTP 那边入口是接口）。取不到时 {@code cards} 为空——
     * 登记与挂起照旧，只是没有那张更方便的按钮。
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalGate.class)
    public ApprovalGate approvalGate(ApprovalLedger ledger, RunStore runs,
                                     ObjectProvider<ApprovalCards> cards,
                                     ObjectProvider<RunDelivery> delivery) {
        return new RunApprovalGate(ledger, runs, cards.orderedStream().toList(),
                runId -> {
                    RunDelivery targets = delivery.getIfAvailable();
                    return targets == null ? List.of() : targets.targetsOf(runId);
                });
    }

    /**
     * 审批的"人那一侧"：有人点了批准或拒绝之后发生什么（P02），以及这个人有没有资格点（P03）。
     *
     * <p><b>续跑那条腿从 {@link AgentPort} 里认出来</b>，不另外注入一个 {@code RunResumer}：
     * 那个实现就是 Agent 的门本身（它持有会话、预算、投递，能自己把一轮跑完），
     * 再注册一个同对象的第二个名字，只会让"谁是那个实现"多一处能写错的地方。
     * 拿到的门不实现 {@code RunResumer} 时（第三方换了一个 AgentPort 实现），
     * 批准照样落得下来，只是没法自动接着做——那时 {@code ApprovalService} 会如实说出来。
     *
     * <p><b>名单在这里变成值对象</b>：{@code claw4j.approval.approvers} 是配置，
     * {@link ApproverRoster} 是"能不能批"这条判断唯一的落点——判定写在配置对象上的话，
     * 下一次改动会同时改到"读配置"与"做判断"两件事，而它们的测试完全不是一回事。
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalDecider.class)
    public ApprovalDecider approvalDecider(JdbcTemplate jdbc, RunStore runs,
                                           ObjectProvider<AgentPort> agentPort,
                                           ApprovalProperties properties) {
        AgentPort port = agentPort.getIfAvailable();
        RunResumer resumer = port instanceof RunResumer candidate ? candidate : null;
        return new ApprovalService(jdbc, runs, resumer,
                new ApproverRoster(new LinkedHashSet<>(properties.getApprovers())));
    }

    /**
     * 一次 run 的预算：三项按 run 累计（A04）。
     *
     * <p>它<b>不是</b>一个可选的装饰：没有它，一轮的 3 / 6 就是唯一的上限，
     * 而那个上限每轮重置——一件跨三轮的事能烧掉三倍的调用而不触发任何一条。
     */
    @Bean
    @ConditionalOnMissingBean(RunBudget.class)
    public RunBudget runBudget(RunStore runs, RunBudgetProperties properties,
                               SessionResetProperties reset) {
        // 空闲窗口从会话那边来（ADR-0026）：额度因换段清零，这里是"换不了段的旧数据"的兜底。
        return new RunBudget(runs, properties, reset.idleMillis());
    }

    /**
     * 结果投递与回收（A05）：登记收件地址、按通知策略推、按 id 被人查。
     *
     * <p><b>推送实现从容器里取，不在这一层写死</b>：哪个通道能推是那个通道自己的事
     * （HTTP 不能，它靠轮询），写死等于把"哪些通道支持推"这张表埋进 Agent 模块。
     * 取不到任何实现时它照常登记与回收——只是没有人会被主动找到。
     */
    @Bean
    @ConditionalOnMissingBean(RunDelivery.class)
    public RunDelivery runDelivery(RunStore runs,
                                   JdbcTemplate jdbc,
                                   ObjectProvider<OutboundDelivery> deliveries,
                                   ObjectProvider<MeterRegistry> registry) {
        return new RunDelivery(runs, jdbc, deliveries.orderedStream().toList(),
                registry.getIfAvailable());
    }

    /**
     * 定时任务的落点（P04）。与 run 的落点分开一个类，因为它们的读写方式没有一处相同：
     * 一个是"每次提问开一行"，一个是"到点认领一行、跑完推进它"。
     */
    @Bean
    @ConditionalOnMissingBean(ScheduleStore.class)
    public ScheduleStore scheduleStore(JdbcTemplate jdbc) {
        return new ScheduleStore(jdbc);
    }

    /**
     * 到点就跑的那个扫描器（P04）。
     *
     * <p><b>跑起来的那条腿同样从 {@link AgentPort} 里认</b>，与审批续跑同一个理由：
     * 那个实现就是 Agent 的门本身。拿不到时扫描器照常装配，只是每一轮都记失败——
     * 那比"静悄悄谁都不跑"诚实，三次之后任务会停用并通知它的主人。
     *
     * <p><b>推送实现从容器里取</b>：停用通知要送到任务登记的那个通道，而哪个通道能推
     * 是那个通道自己的事（HTTP 不能，它靠轮询）。取不到时只留一条 WARN。
     */
    @Bean
    @ConditionalOnMissingBean(ScheduleScanner.class)
    @ConditionalOnProperty(prefix = "claw4j.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ScheduleScanner scheduleScanner(ScheduleStore schedules,
                                           ObjectProvider<AgentPort> agentPort,
                                           ScheduleAuthorization authorization,
                                           ObjectProvider<OutboundDelivery> deliveries,
                                           ScheduleProperties properties,
                                           ObjectProvider<MeterRegistry> registry) {
        AgentPort port = agentPort.getIfAvailable();
        RunLauncher launcher = port instanceof RunLauncher candidate ? candidate : null;
        return new ScheduleScanner(schedules, launcher, authorization,
                deliveries.orderedStream().toList(), properties, registry.getIfAvailable());
    }

    /**
     * "这条任务现在还跑得起来吗"（P05）：建的时候与每次到点之前问的是<b>同一个</b>对象。
     *
     * <p>角色表从容器里取（{@link RoleDirectory} 在通道那一侧实现）。取不到时它照常装配，
     * 只是每次问都答"认不出来"——于是建任务会被当场拒、到点的任务会被停用并通知。
     * 那比"没有角色表就跳过鉴权"安全：跳过的那一侧是永远跑着旧权限。
     *
     * <p>工具登记表是一份<b>列表</b>，与鉴权 Advisor 消费的是同一批 bean：
     * 各来源各登记各的，这里只负责挨个问。
     */
    @Bean
    @ConditionalOnMissingBean(ScheduleAuthorization.class)
    public ScheduleAuthorization scheduleAuthorization(ObjectProvider<RoleDirectory> roles,
                                                       ObjectProvider<ToolPermissionRegistry> registries) {
        return new ScheduleAuthorization(roles.getIfAvailable(), registries.orderedStream().toList());
    }

    /**
     * 定时任务的三个工具（P05）：{@code schedule_task} / {@code list_schedules} / {@code cancel_schedule}。
     *
     * <p><b>跟着 {@code claw4j.schedule.enabled} 一起进退</b>：关掉调度时这几个工具也不给——
     * 能建任务但没有东西会跑它，模型给出的"已经定好了"就是一句谎话。
     * 与计划工具同一个判断：模型面前少一个工具，比"调了才发现白做"好。
     *
     * <p>收件地址从 {@link RunDelivery} 里取（这件事在哪些会话被问过），
     * 与审批卡片同一处来源。取不到时任务照建，只是结果没有可推的地址。
     */
    @Bean
    @ConditionalOnMissingBean(ScheduleTools.class)
    @ConditionalOnProperty(prefix = "claw4j.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ToolCallbackProvider scheduleTools(ScheduleStore schedules,
                                              ScheduleAuthorization authorization,
                                              ObjectProvider<RunDelivery> delivery) {
        return new ScheduleTools(schedules, authorization, runId -> {
            RunDelivery targets = delivery.getIfAvailable();
            return targets == null ? List.of() : targets.targetsOf(runId);
        });
    }

    /**
     * 三个定时任务工具的权限登记。
     *
     * <p>与计划工具那条注释同一个理由：不用 {@code @ConditionalOnMissingBean}。
     * 这个 bean 跟着 {@code claw4j.schedule.enabled} 走，与工具本身同进同出——
     * 工具不在场时登记表里多三条也无人问，但少了这三条会让"模型调了它"变成越权。
     */
    @Bean
    @ConditionalOnProperty(prefix = "claw4j.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ToolPermissionRegistry schedulePermissionRegistry() {
        return ToolPermissionRegistry.of(ScheduleTools.permissions());
    }

    /**
     * 三个定时任务工具的档位：{@link ToolTier#MESSAGING}。
     *
     * <p>定时任务属于"把一件事排到以后做"，不碰代码也不碰外部系统本身——
     * 它比 {@code MINIMAL} 多一层"这个部署允许自己安排事情"，但不需要跑脚本的能力。
     *
     * <p>与工具本身同一个开关：{@code claw4j.schedule.enabled=false} 时三者一起不在场，
     * 于是档位表里也不该留着它们。
     */
    @Bean
    @ConditionalOnProperty(prefix = "claw4j.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ToolTierRegistry scheduleTierRegistry() {
        return ToolTierRegistry.sameFor(ScheduleTools.permissions().keySet(), ToolTier.MESSAGING);
    }

    /**
     * 计划工具：模型用它把"打算怎么干"写进 run，也用它把某一步标成已做或跳过。
     *
     * <p>注册成单个 {@code ToolCallback} 而不是 provider：它是<b>一个</b>工具，
     * 写哪一版计划由参数决定（与委派同一个形状）。
     */
    @Bean
    public ToolCallback planToolCallback(RunStore runs) {
        return new UpdatePlanTool(runs);
    }

    /**
     * 计划工具的权限登记。
     *
     * <p><b>不用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}</b>：
     * 那个条件的语义是"容器里有了就让位"，而鉴权 Advisor 消费的是一张表的<b>列表</b>——
     * 三个来源各登记各的时，后处理的那一个会静默不注册，它的工具从此一律按"未登记"被拒。
     */
    @Bean
    public ToolPermissionRegistry runPlanPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(UpdatePlanTool.TOOL_NAME, UpdatePlanTool.permission()));
    }

    /**
     * 计划工具的档位：{@link ToolTier#MINIMAL}。
     *
     * <p>它写的是"打算怎么干"，落在自己的 run 上，既不出网也不碰别人的数据。
     * 把它归进更高的档会让"只聊天"的部署失去外置计划的能力——
     * 而那正是长对话不丢线索的机制，与部署做不做业务无关。
     */
    @Bean
    public ToolTierRegistry runPlanTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(UpdatePlanTool.TOOL_NAME), ToolTier.MINIMAL);
    }
}
