package io.github.claw4j.agent;

import io.github.claw4j.agent.approval.AgentExecApprovalPort;
import io.github.claw4j.agent.approval.ApprovalRequiredTools;
import io.github.claw4j.agent.approval.ApprovalProperties;
import io.github.claw4j.agent.approval.JdbcApprovals;
import io.github.claw4j.agent.approval.JdbcExecAllowlist;
import io.github.claw4j.agent.audit.AuditLedger;
import io.github.claw4j.agent.audit.AuditProperties;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.capability.exec.ExecApprovalPort;
import io.github.claw4j.capability.exec.ExecProperties;
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
import io.github.claw4j.domain.port.Approvals;
import io.github.claw4j.domain.port.OutboundDelivery;
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
     * 审批：请求、裁决、记录三层都在 {@link JdbcApprovals} 一处（票 10）。
     *
     * <p>它原来分成四个 bean（审批门、账本、决定者、卡片门）加两个端口。合并的理由不是"少几个类"，
     * 是那四件共享同一张表和同一个"还挂着且没过期"的判据，拆开之后出现过"卡片推出去了但登记失败"、
     * "登记成功了但没人判能不能长期放行"这两类错，而它们都不报错。
     *
     * <p>接着做的那条腿是 {@link AgentPort} 本身：批准之后组一条入站消息，走与普通消息同一条路径，
     * 会话记忆、预算、投递都在那条路上。没有 Agent 的部署里批准照样落得下来，只是没人接着做，
     * 结论里会如实说出来。
     *
     * <p>名单在这里变成值对象：{@code claw4j.approval.approvers} 是配置，{@link ApproverRoster}
     * 是「能不能批」这条判断唯一的落点。
     *
     * @param jdbc 主连接池
     * @param agentPort agent 端口，可能不存在
     * @param properties 审批配置（有效期、审批人）
     * @param allowlist exec 的持久放行表，可能不存在（没装 exec 的部署）
     * @param exec exec 配置，可能不存在：{@code ask=always} 时要靠它拒收"以后都放行"
     * @param cards 容器里的卡片能力，可能为空
     * @return 审批
     */
    @Bean
    @ConditionalOnMissingBean(Approvals.class)
    public Approvals approvals(JdbcTemplate jdbc,
                               ObjectProvider<AgentPort> agentPort,
                               ApprovalProperties properties,
                               ObjectProvider<JdbcExecAllowlist> allowlist,
                               ObjectProvider<ExecProperties> exec,
                               ObjectProvider<ApprovalCards> cards) {
        return new JdbcApprovals(jdbc,
                new ApproverRoster(new LinkedHashSet<>(properties.getApprovers())),
                agentPort.getIfAvailable(),
                allowlist.getIfAvailable(),
                exec.getIfAvailable(),
                cards.orderedStream().toList(),
                properties.getTtl().toMillis());
    }

    /**
     * {@code allow-always} 的落点（票 05）：{@code exec_allowlist} 上的条目。
     *
     * <p>与审批表同一个 schema、同一批迁移（基线），所以跟着这一层进出。它缺席时 exec 的审批端口
     * 也装配不起来，于是需要人批的命令一律按 {@code askFallback} 处置——默认拒。
     *
     * @param jdbc 主连接池
     * @return 持久放行表
     */
    @Bean
    @ConditionalOnMissingBean(JdbcExecAllowlist.class)
    public JdbcExecAllowlist execAllowlist(JdbcTemplate jdbc) {
        return new JdbcExecAllowlist(jdbc);
    }

    /**
     * exec 那一侧的审批端口（票 05）：把"问人"交给审批与持久放行表。
     *
     * <p>放在这一层而不是能力层：审批要落库、要推卡片、要在结论之后回那段对话——三样都在 agent。
     * 能力层只认 {@link ExecApprovalPort} 这个接口。
     *
     * @param approvals 审批
     * @param allowlist 持久放行表
     * @param exec exec 配置，可能不存在
     * @param audit 审计落点，可能不存在：带上它，exec 执行那一行才有审批编号
     * @return 审批端口
     */
    @Bean
    @ConditionalOnMissingBean(ExecApprovalPort.class)
    public ExecApprovalPort execApprovalPort(Approvals approvals, JdbcExecAllowlist allowlist,
                                             ObjectProvider<ExecProperties> exec,
                                             ObjectProvider<ToolCallAudit> audit) {
        return new AgentExecApprovalPort(approvals, allowlist, exec.getIfAvailable(),
                audit.getIfAvailable());
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
     * <p>判据只剩一条——要人批的工具进不了定时任务（见 {@link ScheduleAuthorization} 的类注释）。
     * 名单来自 {@link ApprovalRequiredTools}，那是 ADR-0030 之后"哪些工具要人批"唯一的落点：
     * 业务权限登记表与角色表都下去了。
     *
     * @param approvals 要人批的工具名单，必填（默认为空名单）
     * @return 调度鉴权
     */
    @Bean
    @ConditionalOnMissingBean(ScheduleAuthorization.class)
    public ScheduleAuthorization scheduleAuthorization(ApprovalRequiredTools approvals) {
        return new ScheduleAuthorization(approvals);
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
     * 三个定时任务工具的画像：只有 {@code coding}。
     *
     * <p>定时任务是<b>自主行动</b>——到点没人在场，它替这个部署做一件事。OpenClaw 把 {@code cron}
     * 只放进 {@code coding}，我们照抄。旧值 {@code MESSAGING} 会坏掉两件事：一是阶梯语义下它
     * 顺带进了 coding（看起来对，其实是巧合）；二是改成集合之后，客服型部署会拿到"到点自己干活"
     * 的能力，而那正是 {@code messaging} 承诺不碰的东西。
     *
     * <p>与工具本身同一个开关：{@code claw4j.schedule.enabled=false} 时三者一起不在场，
     * 画像表里也不该留着它们。
     *
     * @return 三个工具的画像登记表
     */
    @Bean
    @ConditionalOnProperty(prefix = "claw4j.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ToolTierRegistry scheduleTierRegistry() {
        return ToolTierRegistry.sameFor(ScheduleTools.toolNames(), ToolTier.CODING);
    }

}
