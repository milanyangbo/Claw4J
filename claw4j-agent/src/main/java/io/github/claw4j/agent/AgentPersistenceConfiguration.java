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
 * 依赖数据库的那几样东西的装配：审计、审批、入站、投递、定时任务（ADR-0027）。
 *
 * <p>它们同批迁移、同在一个 schema（{@code claw4j_run}），但没有一样需要另一张表做父亲：
 * 一轮只是句柄，不落库。审计是事件流水，审批是请求表，投递只把结果推到这一轮来源的那段对话
 * （地址在入站消息上，不靠表登记），定时任务是一张调度表。
 *
 * <p>用主连接池：这些 SQL 全写全限定名，不依赖连接的 {@code currentSchema}。会话那边另开池，
 * 是因为它的 SQL 由框架生成、不带前缀。少一个 {@code DataSource} 的收益是实的——容器里出现两个，
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} 不再成立，一整套 JDBC 自动配置会安静地
 * 不装配。
 *
 * <p>默认开着，可用 {@code claw4j.persistence.enabled=false} 关掉。迁移默认是关闭的（「没起数据库
 * 也能启动」是硬承诺），那种环境下每句提问都会去撞一张不存在的表。
 *
 * <p>关掉这一层时的退化都是往紧的一侧走：没有审批账本就没人能批准，标了要审批的动作一律被拦；
 * 扫描器与三个调度工具一起不装配，能建任务却没有东西会跑它，等于对用户说「已经定好了」；
 * 审计的两个接缝拿到 null，判定照常，只是事后查不到。保留期（30 天 + 10 万行，照 OpenClaw 的
 * 固定上限）在 {@link AuditLedger} 里，不做成部署参数。
 *
 * <p>自己开 {@code @EnableScheduling}：缓存那边已经开过一次，重复无害；依赖别人开的话，
 * 只装了这一层的部署里 {@code @Scheduled} 会安静地不生效。
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
     * 审计账本（ADR-0027 决定二）：一行 = 一次动作的开始或结束，只存元数据。
     *
     * <p>它不跟着 run 存储一起装：run 只是一轮的句柄，审计要回答「谁在什么时候让谁做了什么」。
     * 开关是 {@code claw4j.audit.enabled}，上限是 30 天 / 10 万行，不随
     * {@code claw4j.persistence.enabled} 一起消失。
     *
     * @param jdbc 主连接池
     * @param properties 审计配置，取采集开关
     * @param registry 指标注册表，可能不存在
     * @return 审计账本
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
     * <p>它缺席时两个接缝拿到的都是 null，判定照常，只是事后查不到。与审批、预算、投递同一个
     * 判断：能力不在场，而不是假装它在。
     *
     * @param ledger 审计账本
     * @return 审计规则层
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallAudit.class)
    public ToolCallAudit toolCallAudit(AuditLedger ledger) {
        return new ToolCallAudit(ledger);
    }

    /**
     * 审批账本（P01、P02）：鉴权 Advisor 拿它兑现「这个人这个工具的这一种用法有没有批准」。
     *
     * <p>跟着 run 存储一起装：审批表与 run 在同一个 schema 下（V10、V11），迁移是同一批，开关也是
     * 同一个。它缺席时 Advisor 退到 {@link ApprovalGate#denyAll()}，标了要审批的动作照样被拦，
     * 只是没人能批准——这正是「这个部署没开审批」应有的样子。
     *
     * <p>有效期（默认十分钟）走 {@code claw4j.approval.ttl}，写在配置里而不写在表上：表上放默认值
     * 会让「改了配置但库里还是十分钟」这种偏差看不见。
     *
     * @param jdbc 主连接池
     * @param properties 审批配置，取 ttl
     * @return 审批账本
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalLedger.class)
    public ApprovalLedger approvalLedger(JdbcTemplate jdbc, ApprovalProperties properties) {
        return new JdbcApprovalLedger(jdbc, properties.getTtl().toMillis());
    }

    /**
     * 入站事件的落点（票 04）：先落库、再 ACK，重启之后没跑完的那条有人接着跑。
     *
     * <p>它跟着这一层进出：没有 DataSource 就没有可写的表，入站退回内存实现
     * （{@code InboundDispatcher} 拿不到这个 bean 时自己用内存那份并 WARN）。两条路共用同一份
     * {@code accept} 逻辑——端口只有一套方法，调度层里没有 {@code if (jdbc) … else …}，
     * 两套分支迟早会不一致。
     *
     * <p>它是这一层里唯一在入站主路径上的东西：审计、审批、调度都在事后或到点，而入站是每条消息
     * 都过一次。库慢了会直接体现为「消息没人接」，启动日志里那句 {@code 入站事件落库} 就是给运维对这条
     * 因果用的。
     *
     * @param jdbc 主连接池
     * @return 入站存储
     */
    @Bean
    @ConditionalOnMissingBean(InboundStore.class)
    public InboundStore inboundStore(JdbcTemplate jdbc) {
        return new JdbcInboundStore(jdbc);
    }

    /**
     * 审批门：Advisor 只认这两个动作——兑现、登记。
     *
     * <p>它只做两件事：登记一条、把卡片推到那段对话里。它不停住任何东西——这一轮到此结束，
     * 结论之后由 {@code ApprovalService} 在那段对话里接着说（ADR-0027）。
     *
     * <p>卡片能力从容器里取（P03）：哪个通道能推卡片是那个通道自己的事（飞书有交互卡片，
     * HTTP 那边入口是接口）。取不到时 {@code cards} 为空，登记照旧，只是没有那张更方便的按钮。
     *
     * @param ledger 审批账本
     * @param cards 容器里的卡片能力，可能为空
     * @return 审批门
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalGate.class)
    public ApprovalGate approvalGate(ApprovalLedger ledger, ObjectProvider<ApprovalCards> cards) {
        return new ConversationApprovalGate(ledger, cards.orderedStream().toList());
    }

    /**
     * 审批的人那一侧：有人点了批准或拒绝之后发生什么（P02），以及这个人有没有资格点（P03）。
     *
     * <p>接着做的那条腿就是 {@link AgentPort} 本身：批准之后组一条入站消息，走与普通消息同一条
     * 路径，会话记忆、预算、投递都在那条路上。没有 Agent 的部署里批准照样落得下来，只是没人接着做，
     * 那时会如实说出来。
     *
     * <p>名单在这里变成值对象：{@code claw4j.approval.approvers} 是配置，{@link ApproverRoster}
     * 是「能不能批」这条判断唯一的落点。判定写在配置对象上的话，下一次改动会同时改到读配置与做判断
     * 两件事，而它们的测试完全不是一回事。
     *
     * @param jdbc 主连接池
     * @param agentPort agent 端口，可能不存在
     * @param properties 审批配置，取审批人名单
     * @return 审批决定者
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
     * <p>推送实现从容器里取，不在这一层写死：哪个通道能推是那个通道自己的事（HTTP 不能，
     * 它靠轮询），写死等于把「哪些通道支持推」这张表埋进 Agent 模块。
     *
     * @param deliveries 容器里的推送实现
     * @param registry 指标注册表，可能不存在
     * @return 投递与回收
     */
    @Bean
    @ConditionalOnMissingBean(ConversationDelivery.class)
    public ConversationDelivery conversationDelivery(
                                   ObjectProvider<OutboundDelivery> deliveries,
                                   ObjectProvider<MeterRegistry> registry) {
        return new ConversationDelivery(deliveries.orderedStream().toList(), registry.getIfAvailable());
    }

    /**
     * 定时任务的落点（P04）。与 run 的落点分成两个类，因为读写方式没有一处相同：
     * 一个是每次提问开一行，一个是到点认领一行、跑完推进它。
     *
     * @param jdbc 主连接池
     * @return 调度存储
     */
    @Bean
    @ConditionalOnMissingBean(ScheduleStore.class)
    public ScheduleStore scheduleStore(JdbcTemplate jdbc) {
        return new ScheduleStore(jdbc);
    }

    /**
     * 到点就跑的那个扫描器（P04）。
     *
     * <p>跑起来的那条腿同样从 {@link AgentPort} 里认，与审批续跑同一个理由：那个实现就是 Agent
     * 的门本身。拿不到时扫描器照常装配，只是每一轮都记失败——那比静悄悄谁都不跑诚实，
     * 三次之后任务会停用并通知它的主人。
     *
     * <p>推送实现从容器里取：停用通知要送到任务登记的那个通道，哪个通道能推是它自己的事
     * （HTTP 不能，它靠轮询）。取不到时只留一条 WARN。
     *
     * @param schedules 调度存储
     * @param agentPort agent 端口，需同时是 {@link TaskLauncher}
     * @param authorization 建任务与到点前的鉴权
     * @param deliveries 容器里的推送实现
     * @param properties 调度配置
     * @param registry 指标注册表，可能不存在
     * @return 定时任务扫描器
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
     * 「这条任务现在还跑得起来吗」（P05）：建任务时与每次到点前问的是同一个对象。
     *
     * <p>角色表从容器里取（{@link RoleDirectory} 在通道那一侧实现）。取不到时照常装配，
     * 只是每次问都答「认不出来」——于是建任务会被当场拒，到点的任务会被停用并通知。
     * 那比「没有角色表就跳过鉴权」安全：跳过的那一侧是永远跑着旧权限。
     *
     * <p>工具登记表是一份列表，与鉴权 Advisor 消费的是同一批 bean：各来源各登记各的，
     * 这里只负责挨个问。
     *
     * @param roles 角色表，可能不存在
     * @param registries 各来源的工具权限登记表
     * @return 调度鉴权
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
     * <p>跟着 {@code claw4j.schedule.enabled} 一起进退：关掉调度时这几个工具也不给。能建任务但没有
     * 东西会跑它，模型给出的「已经定好了」就是一句谎话。与计划工具同一个判断：模型面前少一个工具，
     * 比调了才发现白做好。
     *
     * <p>收件地址从工具上下文里取（这一轮从哪个通道、哪个会话来），与审批卡片同一处来源。
     * 取不到时任务照建，只是结果没有可推的地址。
     *
     * @param schedules 调度存储
     * @param authorization 调度鉴权
     * @return 三个工具的 {@code ToolCallbackProvider}
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
     * <p>与计划工具那条注释同一个理由：不用 {@code @ConditionalOnMissingBean}。这个 bean 跟着
     * {@code claw4j.schedule.enabled} 走，与工具本身同进同出：工具不在场时登记表里多三条也无人问，
     * 少了这三条却会让「模型调了它」变成越权。
     *
     * @return 三个工具的权限登记表
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
     * <p>定时任务属于「把一件事排到以后做」，不碰代码也不碰外部系统本身：它比 {@code MINIMAL}
     * 多一层「这个部署允许自己安排事情」，但不需要跑脚本的能力。
     *
     * <p>与工具本身同一个开关：{@code claw4j.schedule.enabled=false} 时三者一起不在场，
     * 档位表里也不该留着它们。
     *
     * @return 三个工具的档位表
     */
    @Bean
    @ConditionalOnProperty(prefix = "claw4j.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ToolTierRegistry scheduleTierRegistry() {
        return ToolTierRegistry.sameFor(ScheduleTools.permissions().keySet(), ToolTier.MESSAGING);
    }

}
