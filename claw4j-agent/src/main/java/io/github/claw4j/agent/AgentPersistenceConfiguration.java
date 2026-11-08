package io.github.claw4j.agent;

import io.github.claw4j.agent.approval.ApprovalGate;
import io.github.claw4j.agent.approval.ApprovalProperties;
import io.github.claw4j.agent.approval.ApprovalService;
import io.github.claw4j.agent.approval.JdbcApprovalLedger;
import io.github.claw4j.agent.approval.ConversationApprovalGate;
import io.github.claw4j.agent.audit.AuditLedger;
import io.github.claw4j.agent.audit.AuditProperties;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.channel.ConversationDelivery;
import io.github.claw4j.agent.inbound.JdbcInboundStore;
import io.github.claw4j.domain.inbound.InboundStore;
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
import java.util.Map;

/**
 * 需要库的那几样东西的装配：审计、审批、投递、定时任务（ADR-0027）。
 *
 * <p><b>四样东西各自独立</b>：审计是一张事件流水、审批是一张请求表、
 * 投递是"把结果推到它开始的那段对话"、定时任务是一张调度表。
 * 它们同批迁移、同一个 schema（{@code claw4j_run}），但**没有一样需要另一张表做父亲**——
 * 一轮只是句柄，不落库（ADR-0027）。
 *
 * <p><b>为什么用主连接池</b>：这些表的 SQL 全在我们手里、全写全限定名，不需要靠连接的
 * {@code currentSchema}。会话那边必须另开一个池，是因为它的 SQL 由框架生成、不带前缀；
 * 这里没有那个约束，而少一个 {@code DataSource} 的收益是实的——容器里出现两个，
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} 就不再成立，
 * 一整套 JDBC 自动配置会安静地不装配（会话那边为此专门不把池注册成 bean）。
 *
 * <p><b>默认开着但可关</b>：开着是因为这四样都要库，默认没有它等于审计、审批、
 * 定时任务永远挂不上；可关（{@code claw4j.persistence.enabled=false}）是因为
 * 迁移默认是关着的（"没起数据库也能启动"是硬承诺），那种环境下每一句提问都会去撞一张不存在的表。
 *
 * <p><b>投递不依赖任何存储</b>：它只把结果推到"这一轮来自的那段对话"（ADR-0027 决定四）——
 * 地址就在入站消息上，不靠任何一张表登记。
 *
 * <p><b>审批账本在这一层</b>：审批表与审计、调度同一批迁移。关掉这一层就没有
 * "有人批过没有"这个问题可问，那时标了要审批的动作一律被拦——
 * 没有审批设施的环境里，收紧的那一侧才是安全的。
 *
 * <p><b>定时任务也在这一层</b>：调度表的"到点"产出的是一轮提问，不是一个 run。
 * 关掉这一层时没有可跑的落点，于是扫描任务也不装配——
 * 让一个跑不起来的扫描器每 30 秒记一次失败，等于给运维制造噪声。
 * 三个调度工具跟着一起进退：能建任务、却没有东西会跑它，
 * 等于对用户说"已经定好了"而实际不会发生。
 *
 * <p><b>审计在这一层</b>：一次动作一行，写的是 {@code audit_event}（ADR-0027 决定二）。
 * 它跟着这一层进出：没有存储就没有可写的行，两个接缝拿到的都是 null，
 * 判定照常、只是事后查不到。保留期（30 天 + 10 万行）在 {@link AuditLedger} 里，
 * 照 OpenClaw 的固定上限，不做成部署参数。
 *
 * <p><b>这里自己开 {@code @EnableScheduling}</b>：缓存那边已经开过一次，重复一次无害
 * （与 {@code CacheAutoConfiguration} 同一个判断），而依赖别人开的话，只装了这一层的
 * 部署里 {@code @Scheduled} 会安静地不生效——"配好了但从来不跑"是最难查的那种。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "claw4j.persistence", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableScheduling
@EnableConfigurationProperties({ApprovalProperties.class,
        ScheduleProperties.class, AuditProperties.class, SessionResetProperties.class})
public class AgentPersistenceConfiguration {

    /**
     * 审计账本（ADR-0027 决定二）：一行 = 一次动作的开始或结束，元数据 only。
     *
     * <p><b>它不跟着 run 存储一起装</b>：审计与 run 是两件事——run 只是一轮的句柄，
     * 而审计要回答"谁在什么时候让谁做了什么"。所以它自己的开关是 {@code claw4j.audit.enabled}，
     * 自己的上限是 30 天 / 10 万行，不随 {@code claw4j.persistence.enabled} 一起消失。
     */
    @Bean
    @ConditionalOnMissingBean(AuditLedger.class)
    public AuditLedger auditLedger(JdbcTemplate jdbc, AuditProperties properties,
                                   ObjectProvider<MeterRegistry> registry) {
        return new AuditLedger(jdbc, registry.getIfAvailable(), properties.isEnabled());
    }

    /**
     * 审计的规则层：什么时候该记一条什么事件、审批与执行怎么配对。
     *
     * <p>它缺席时两个接缝拿到的都是 null——判定照常，只是事后查不到
     * （与审批、预算、投递同一个判断：能力不在场，而不是假装它在）。
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallAudit.class)
    public ToolCallAudit toolCallAudit(AuditLedger ledger) {
        return new ToolCallAudit(ledger);
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
     * 入站事件的落点（票 04）：先落库、再 ACK，重启之后没跑完的那条有人接着跑。
     *
     * <p><b>它跟着这一层进出</b>：没有 DataSource 就没有可写的表，入站退回到内存实现
     * （{@code InboundDispatcher} 在拿不到这个 bean 时自己用内存那份，并 WARN 一句）。
     * 两条路共用同一份 {@code accept} 逻辑——端口只有一套方法，调度层里没有
     * {@code if (jdbc) … else …}，两套分支迟早会不一致。
     *
     * <p><b>它是这一层里唯一在入站主路径上的东西</b>：审计、审批、调度都是"事后"或"到点"，
     * 而入站是每条消息都过一次。所以库慢了会直接体现在"消息没人接"上，
     * 启动日志里那一句 {@code 入站事件落库} 就是给运维对这条因果用的。
     */
    @Bean
    @ConditionalOnMissingBean(InboundStore.class)
    public InboundStore inboundStore(JdbcTemplate jdbc) {
        return new JdbcInboundStore(jdbc);
    }

    /**
     * 审批门：Advisor 只认这两个动作（兑现、登记）。
     *
     * <p>它只做两件事：登记一条、把卡片推到那段对话里。**它不停住任何东西**——
     * 这一轮到此结束，结论之后由 {@code ApprovalService} 在那段对话里接着说（ADR-0027）。
     *
     * <p><b>卡片能力从容器里取</b>（P03）：哪个通道能推卡片是那个通道自己的事
     * （飞书有交互卡片，HTTP 那边入口是接口）。取不到时 {@code cards} 为空——
     * 登记照旧，只是没有那张更方便的按钮。
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalGate.class)
    public ApprovalGate approvalGate(ApprovalLedger ledger, ObjectProvider<ApprovalCards> cards) {
        return new ConversationApprovalGate(ledger, cards.orderedStream().toList());
    }

    /**
     * 审批的"人那一侧"：有人点了批准或拒绝之后发生什么（P02），以及这个人有没有资格点（P03）。
     *
     * <p><b>接着做的那条腿就是 {@link AgentPort} 本身</b>：批准之后组一条入站消息，
     * 走与普通消息同一条路径（会话记忆、预算、投递都在那条路上）。
     * 没有 Agent 的部署里批准照样落得下来，只是没人接着做——那时会如实说出来。
     *
     * <p><b>名单在这里变成值对象</b>：{@code claw4j.approval.approvers} 是配置，
     * {@link ApproverRoster} 是"能不能批"这条判断唯一的落点——判定写在配置对象上的话，
     * 下一次改动会同时改到"读配置"与"做判断"两件事，而它们的测试完全不是一回事。
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalDecider.class)
    public ApprovalDecider approvalDecider(JdbcTemplate jdbc,
                                           ObjectProvider<AgentPort> agentPort,
                                           ApprovalProperties properties) {
        return new ApprovalService(jdbc,
                new ApproverRoster(new LinkedHashSet<>(properties.getApprovers())),
                agentPort.getIfAvailable());
    }

    /**
     * 结果投递与回收（A05）：登记收件地址、按通知策略推、按 id 被人查。
     *
     * <p><b>推送实现从容器里取，不在这一层写死</b>：哪个通道能推是那个通道自己的事
     * （HTTP 不能，它靠轮询），写死等于把"哪些通道支持推"这张表埋进 Agent 模块。
     */
    @Bean
    @ConditionalOnMissingBean(ConversationDelivery.class)
    public ConversationDelivery conversationDelivery(
                                   ObjectProvider<OutboundDelivery> deliveries,
                                   ObjectProvider<MeterRegistry> registry) {
        return new ConversationDelivery(deliveries.orderedStream().toList(), registry.getIfAvailable());
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
        TaskLauncher launcher = port instanceof TaskLauncher candidate ? candidate : null;
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
     * <p>收件地址从工具上下文里取（这一轮从哪个通道、哪个会话来），与审批卡片同一处来源。
     * 取不到时任务照建，只是结果没有可推的地址。
     */
    @Bean
    @ConditionalOnMissingBean(ScheduleTools.class)
    @ConditionalOnProperty(prefix = "claw4j.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ToolCallbackProvider scheduleTools(ScheduleStore schedules,
                                              ScheduleAuthorization authorization) {
        return new ScheduleTools(schedules, authorization);
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

}
