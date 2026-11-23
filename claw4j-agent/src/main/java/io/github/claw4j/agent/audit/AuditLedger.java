package io.github.claw4j.agent.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计事件的账本（ADR-0027 决定二）：只做两件事——写一行、按上限清旧行。
 *
 * <p>它是元数据账本，不是内容账本：这一层不知道参数长什么样，也没有能力存下来。上游
 * （{@link ToolCallAudit}）给什么就写什么，而 {@link AuditEvent} 里根本没有装内容的字段。把「不存
 * 内容」做成「没有地方存」，比写成一条注释可靠。
 *
 * <p>30 天与 10 万行照 OpenClaw 的固定上限（{@code docs/gateway/protocol/ledgers.md:17}），不是部署
 * 参数。做成可配的那一刻，「留存有上限」就变成一句取决于某个 yaml 的话。
 *
 * <p>清理的时机与 OpenClaw 略有不同：它写的是「启动时、每小时维护、以及之后的写入」，而它的写入走
 * 一个有界的后台批处理。这里的写入是同步的，每条 INSERT 后面跟两条 DELETE（其中一条要按 seq 偏移
 * 10 万行）会让每次工具调用多两次往返。所以同一个策略换了调度：启动一次、每小时一次、每
 * {@value #PRUNE_EVERY} 次写入一次。上限仍然成立——最多多留 {@value #PRUNE_EVERY} 行，不影响任何
 * 查询的答案。
 *
 * <p>写失败不抛：审计是记账，不是回答的前置条件。降级的后果是「这一行没记上」，由
 * {@code claw4j.audit.write_errors} 暴露——一个不为 0 的数就说明这些天缺了记录。
 */
public class AuditLedger implements AuditSink {

    /** 写失败计数。不为 0 就说明审计有洞。 */
    public static final String WRITE_ERRORS = "claw4j.audit.write_errors";

    /** 清掉的记录数，按原因分：{@code expired}（30 天）与 {@code over_limit}（10 万行）。 */
    public static final String PURGED = "claw4j.audit.purged";

    /**
     * 因为拿不到轮次编号而没落库的事件数。
     *
     * <p>与 {@link #WRITE_ERRORS} 分开：那个数是"该记却没记上"的故障，
     * 这个是"本来就不该记"的口径（没有轮次的事件不属于任何一次运行）。
     * 合成一个数的话，一次正常的子链调用会被看成一次故障。
     */
    public static final String SKIPPED = "claw4j.audit.skipped";

    /**
     * 因为来源键已经存在而没有落库的事件数（票 02）。
     *
     * <p>与 {@link #WRITE_ERRORS} 分开：重复投递不是故障，是幂等在起作用。合成一个数会让
     * 一次正常重放看起来像库坏了；与 {@link #SKIPPED} 也分开——那个是"没有归属"，
     * 这个是"同一件事已经记过了"。
     */
    public static final String DUPLICATES = "claw4j.audit.duplicates";

    /** 消息行那类 kind 的落库写法；与 {@code AuditAction} 的 {@code message} 一致。 */
    private static final String ACTION_KIND_MESSAGE = "message";

    /** 照 OpenClaw：30 天。 */
    public static final Duration RETENTION = Duration.ofDays(30);

    /** 照 OpenClaw：10 万行。 */
    public static final int MAX_ROWS = 100_000;

    /**
     * 超过多久没下文算孤儿（ADR-0037 票 01）。
     *
     * <p>30 分钟是估的：一轮正常几十秒，最长受 {@code turnTimeout} 约束。这个数上游没有对应窗口
     * （OpenClaw 用两行原子落库避开孤儿行），票 01 的缺口里写明它是估的。
     */
    public static final Duration ORPHAN_AGE = Duration.ofMinutes(30);

    /**
     * 每写多少条清一次。
     *
     * <p>取 500 是「够小以至于上限看起来是硬的，又够大以至于清理不是热路径」。改成 1 也能跑，代价是
     * 每次工具调用多两条 DELETE。
     */
    static final int PRUNE_EVERY = 500;

    private static final Logger log = LoggerFactory.getLogger(AuditLedger.class);

    private static final String INSERT = """
            insert into claw4j_run.audit_event
                (event_id, kind, action, status, error_code, run_id, session_key, session_id, agent_id,
                 actor_type, actor, requested_by, channel, tool_call_id, tool_name, args_digest,
                 approval_id, source_id, source_sequence,
                 direction, conversation_kind, message_outcome, reason_code, delivery_kind,
                 failure_stage, duration_ms, result_count,
                 account_ref, conversation_ref, message_ref, target_ref, at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (source_id, source_sequence) do nothing
            """;

    private static final String PURGE_AGE =
            "delete from claw4j_run.audit_event where at < now() - (? * interval '1 millisecond')";

    /**
     * 10 万行上限。
     *
     * <p>写成「删掉 seq 小于等于第 100001 大那个」而不是先 {@code count(*)} 再删：后者在并发写入下
     * 会多删或少删，而「上限」本来该是一个确定的位置。行数不足时子查询返回 null，
     * {@code seq <= null} 为 null，一条都不删。
     */
    private static final String PURGE_OVER_LIMIT =
            "delete from claw4j_run.audit_event where seq <= ("
                    + "select seq from claw4j_run.audit_event order by seq desc"
                    + " offset " + MAX_ROWS + " limit 1)";

    /**
     * 孤儿扫尾：给「有 started、没有第 2 行、而且已经过了 {@code age}」的来源补一条终态
     * （ADR-0037 票 01）。
     *
     * <p>写成一条 insert-select：判孤儿（{@code status = 'started'} 且超过窗口）与判「来源内没有
     * 终态」都在 SQL 里，读-判-写之间不会有另一条线插进来。{@code event_id} 由来源键算出来
     * （{@code md5}）而不是随机：这条语句被跑两遍时，第二遍会被唯一键挡掉，而不是补出第二行。
     */
    private static final String SWEEP_ORPHANS = """
            insert into claw4j_run.audit_event
                (event_id, kind, action, status, error_code, run_id, session_key, session_id, agent_id,
                 actor_type, actor, requested_by, channel, tool_call_id, tool_name, args_digest,
                 approval_id, source_id, source_sequence)
            select 'orphan-' || md5(s.source_id || ':' || s.source_sequence),
                   s.kind,
                   case s.kind when 'agent_run' then 'agent.run.finished'
                               else 'tool.action.finished' end,
                   'unknown',
                   case s.kind when 'agent_run' then null else 'tool_outcome_unknown' end,
                   s.run_id, s.session_key, s.session_id, s.agent_id,
                   s.actor_type, s.actor, s.requested_by, s.channel,
                   -- 编号跟着 started 那一行走：补出来的终态说的是「同一次调用没了下文」，
                   -- 换一个编号就成了另一次调用
                   s.tool_call_id, s.tool_name, s.args_digest, null,
                   s.source_id, 2
              from claw4j_run.audit_event s
             where s.status = 'started'
               and s.at < now() - (? * interval '1 millisecond')
               and not exists (select 1 from claw4j_run.audit_event f
                                where f.source_id = s.source_id and f.source_sequence = 2)
            on conflict (source_id, source_sequence) do nothing
            """;

    private final JdbcTemplate jdbc;

    /** 为 null 表示没装 actuator：照常写，只是没有计数可报。 */
    private final MeterRegistry registry;

    /** 写失败只打一次日志。每一条都打会把一次库故障变成一整页刷屏。 */
    private volatile boolean warned;

    private final AtomicLong writes = new AtomicLong();

    /** 关掉之后不写新事件，但照常清旧行（OpenClaw 同：关的是采集，不是留存）。 */
    private final boolean enabled;

    /**
     * 用默认开关的账本。
     *
     * @param jdbc 主连接池
     * @param registry 指标注册表，为 null 时只写不计数
     */
    public AuditLedger(JdbcTemplate jdbc, MeterRegistry registry) {
        this(jdbc, registry, true);
    }

    /**
     * 账本。
     *
     * @param jdbc 主连接池，必填
     * @param registry 指标注册表，为 null 时只写不计数
     * @param enabled 是否采集新事件；false 时仍照常清旧行
     */
    public AuditLedger(JdbcTemplate jdbc, MeterRegistry registry, boolean enabled) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = registry;
        this.enabled = enabled;
    }

    /**
     * 写一条事件。
     *
     * <p>没有轮次编号就不写：OpenClaw 的 {@code AuditEventSchema.runId} 是必填
     * （{@code packages/gateway-protocol/src/schema/audit.ts:56}），事件必须属于一次已准入的运行。
     * 一行查不到轮次的审计，按 {@code runId} 查永远命中不了，留着只会让「这个月跑了多少次」的数字比
     * 能查到的多。它们计入 {@value #SKIPPED}（不是 {@value #WRITE_ERRORS}，那不是写失败，是没有归属）。
     *
     * @param event 要落库的事件
     */
    @Override
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        if (!enabled) {
            return;
        }
        AuditEvent.Scope scope = event.scope() == null ? AuditEvent.Scope.empty() : event.scope();
        // 消息行可以没有 runId（ADR-0037 票 04）：入站消息在开跑之前就到了，而它可能根本没开跑。
        // 运行与工具事件仍然必须有——那一格空着的行按 runId 永远命中不了，留着只让计数虚高。
        if (event.action().kind() != ACTION_KIND_MESSAGE
                && (scope.runId() == null || scope.runId().isBlank())) {
            countSkipped();
            return;
        }
        AuditEvent.Message message = event.message();
        // 归属按 OpenClaw 的规则算一次（见 attributionOf）：事件里记执行者，不记人。
        Attribution attribution = attributionOf(message, scope.agentId());
        try {
            int written = jdbc.update(INSERT,
                    // event_id 单独生成而不是复用主键：OpenClaw 用它去重（同一条事件重复投递只留一行）。
                    // 这里的写入是同步单次的，没有重放路径，所以它现在只是一个唯一值；
                    // 将来真的需要重放时，调用方带一个稳定的 id 进来就能直接对上，不用改表。
                    UUID.randomUUID().toString(),
                    event.action().kind(),
                    event.action().column(),
                    event.status().column(),
                    // 词表是枚举，落库时才变成字符串——写一个词表外的值，在编译期就不成立。
                    event.errorCode() == null ? null : event.errorCode().column(),
                    scope.runId(),
                    scope.sessionKey(),
                    scope.sessionId(),
                    scope.agentId(),
                    attribution.type(),
                    attribution.id(),
                    scope.requestedBy(),
                    // 通道只属于消息行：OpenClaw 对非消息行强制写 null（audit-event-store.ts:103），
                    // 库里还有 ck_audit_event_channel_message_only 钉着。
                    message == null ? null : scope.channel(),
                    // 工具调用编号（票 06）：只有 tool_action 有值，其余 kind 落 null——
                    // 消息行带它是 OpenClaw 读侧 schema 明确禁止的形状（toolCallId?: never）。
                    event.toolCallId(),
                    event.toolName(),
                    event.argsDigest(),
                    event.approvalId(),
                    event.sourceId(),
                    event.sourceSequence(),
                    message == null ? null : message.direction().column(),
                    message == null ? null : message.conversationKind(),
                    message == null ? null : message.outcome().column(),
                    message == null ? null : message.reasonCode(),
                    message == null ? null : message.deliveryKind(),
                    message == null ? null : message.failureStage(),
                    message == null ? null : message.durationMs(),
                    message == null ? null : message.resultCount(),
                    message == null ? null : message.accountRef(),
                    message == null ? null : message.conversationRef(),
                    message == null ? null : message.messageRef(),
                    message == null ? null : message.targetRef(),
                    // 事件自己的时刻（票 12）：列有默认值 now()，但那是"落库时刻"。
                    // 显式绑上去，一条处理了八秒的入站消息才不会被记成"八秒之后才发生"。
                    java.sql.Timestamp.from(event.occurredAt() == null
                            ? java.time.Instant.now() : event.occurredAt()));
            if (written == 0) {
                // 同一来源已经记过：幂等在起作用，不是故障。计自己的数，然后照常判清理。
                count(DUPLICATES);
            }
            pruneIfDue();
        } catch (RuntimeException failure) {
            count(WRITE_ERRORS);
            if (!warned) {
                warned = true;
                log.warn("审计事件写不进去（{}）。之后同类失败只计数不再打日志：{}",
                        event.action().column(), failure.toString());
            }
        }
    }

    /**
     * 启动时清一次。
     *
     * <p>OpenClaw 的原话是「Expired rows are deleted during Gateway startup, hourly maintenance, and
     * later writes」（{@code docs/gateway/protocol/ledgers.md:17}）。少了启动这一下，一个停了三个月
     * 才重启的实例会先带着一堆过期行跑一阵，而查询本来就该在 30 天处截断——错开的是「库里有多少行」
     * 与「能查到多少行」。
     *
     * <p>表还没建（迁移没跑）时 {@link #prune()} 自己吞掉异常，启动照常。
     */
    @PostConstruct
    void pruneOnStartup() {
        // prune() 里带着孤儿扫尾：一个刚被强杀过的实例，重启这一刻正是补「unknown」的时候。
        prune();
    }

    /**
     * 把「有开始没有结束」的行补成 {@code unknown}（ADR-0037 票 01）。
     *
     * <p>补的是这个形态的固有缺口：我们是「开跑即落一行 {@code started}」，进程在中间被杀（发版、
     * OOM、容器被踢）时，那一行永远不会有下文。OpenClaw 靠「终态时两行原子落库」避免孤儿
     * （{@code src/audit/audit-event-store.ts:327-391}），我们换来了「运行中可见」，代价就是这些孤儿行
     * ——它们看起来像「跑了但没结果」，而真相是「不知道」。
     *
     * <p>为什么是 30 分钟：一轮正常情况下几十秒，最长受 {@code turnTimeout} 约束；比它长一个数量级
     * 才是「进程没了」。这个数是估的（上游没有对应窗口），写在票 01 的缺口里。
     *
     * <p>只补「来源内还没有第 2 行」的那些：同一来源已经有终态的，说明它正常收尾了，不该被改成
     * {@code unknown}。已经补过的不再补——唯一键挡着，重复执行只是白跑一次。
     *
     * <p>工具事件的错误码是 {@code tool_outcome_unknown}（OpenClaw 同名同义）；运行事件没有对应的
     * 错误码，按「不知道」留空。这两条与 {@code V11} 的配对约束一致。
     *
     * @param age 超过多久算孤儿
     * @return 补了多少行
     */
    public int sweepOrphans(Duration age) {
        try {
            int swept = jdbc.update(SWEEP_ORPHANS, age.toMillis());
            if (swept > 0) {
                log.info("审计：把 {} 条超时未收尾的事件补成 unknown", swept);
            }
            return swept;
        } catch (RuntimeException failure) {
            // 表还没建（迁移没跑）或者库连不上：启动照常，下一轮再说。
            log.debug("审计的孤儿扫尾这一轮没跑成", failure);
            return 0;
        }
    }

    /**
     * 清一次：先按 30 天，再按 10 万行。
     *
     * <p>启动时与每小时各一次，另外每 {@value #PRUNE_EVERY} 次写入一次。
     *
     * @return 这一次清掉多少，按原因分开
     */
    @Scheduled(fixedDelayString = "${claw4j.audit.prune-interval:PT1H}")
    public Report prune() {
        int expired = 0;
        int overLimit = 0;
        try {
            expired = jdbc.update(PURGE_AGE, RETENTION.toMillis());
            overLimit = jdbc.update(PURGE_OVER_LIMIT);
        } catch (RuntimeException failure) {
            // 清理失败只是表在长，不该让一个后台任务反复抛栈。库连不上时下一轮再说。
            log.debug("审计的清理这一轮没跑成", failure);
        }
        if (expired > 0) {
            count(PURGED, "expired", expired);
        }
        if (overLimit > 0) {
            count(PURGED, "over_limit", overLimit);
            log.info("审计超过 {} 行上限，清掉最旧的 {} 条", MAX_ROWS, overLimit);
        }
        if (expired > 0) {
            log.info("审计按 30 天保留期清掉 {} 条", expired);
        }
        // 扫尾跟清理同一节拍：两者都是"让表与事实对齐"的维护，分开跑只会让一个任务两套调度。
        sweepOrphans(ORPHAN_AGE);
        return new Report(expired, overLimit);
    }

    /** 写入计数到点就清一次。失败不影响这一条写入——{@link #prune()} 自己吃掉异常。 */
    private void pruneIfDue() {
        if (writes.incrementAndGet() % PRUNE_EVERY == 0) {
            prune();
        }
    }

    private void countSkipped() {
        if (registry == null) {
            return;
        }
        Counter.builder(SKIPPED)
                .description("没有轮次编号、因而没有落库的审计事件数")
                .register(registry)
                .increment();
    }

    private void count(String metric) {
        if (registry == null) {
            return;
        }
        Counter.builder(metric)
                .description("审计事件写失败的次数")
                .register(registry)
                .increment();
    }

    private void count(String metric, String what, int amount) {
        if (registry == null) {
            return;
        }
        Counter.builder(metric)
                .description("按保留期或行数上限清掉的审计事件数")
                .tag("what", what)
                .register(registry)
                .increment(amount);
    }

    /** 一次清理的结果。 */
    public record Report(int expired, int overLimit) {

        /**
         * 两种原因加起来清掉多少条。
         *
         * @return 清理总数
         */
        public int total() {
            return expired + overLimit;
        }
    }

    /**
     * 一行审计的「谁执行的」：{@code type} 与 {@code id} 两格，落 {@code actor_type} / {@code actor}。
     *
     * @param type {@code agent} / {@code system} / {@code channel_sender}
     * @param id agent 行是 agentId，通道来的人是发送者假名
     */
    private record Attribution(String type, String id) {
    }

    /**
     * 按 OpenClaw 的规则算归属（{@code src/audit/agent-event-audit.ts} 的
     * {@code projectExplicitAttribution}：{@code actorType = eventAgentId ? "agent" : "system"}、
     * {@code actorId = agentId}）。
     *
     * <p><b>它记的是执行者，不是提问的人</b>：人在 OpenClaw 那边属于 execution decision 那一层，
     * 我们放在 {@code requested_by}（扩展列，读接口不返回）。这一格以前装的是人，与它同名不同义——
     * 那正是"看起来对齐、字段含义对不上"最难查的一种。
     *
     * <p>消息行按它的消息投影：入站是 {@code channel_sender} + 发送者假名（与 {@code account_ref}
     * 同值），出站是 {@code agent} + agentId；入站却拿不到假名时降级成 {@code system}——它的
     * schema 里 {@code channel_sender} 必须带 hmac 引用，写不出那引用就不能自称是某个通道里的人。
     *
     * @param message 消息行专有的那组；运行与工具行为 null
     * @param agentId 这一轮的 agent
     * @return 归属两格，都不为 null
     */
    private static Attribution attributionOf(AuditEvent.Message message, String agentId) {
        if (message != null) {
            String ref = message.accountRef();
            if (ref != null && !ref.isBlank()) {
                return new Attribution("channel_sender", ref);
            }
            String type = message.actorType();
            if ("channel_sender".equals(type)) {
                return new Attribution("system", "unknown");
            }
            return new Attribution(type == null ? "agent" : type,
                    agentId == null || agentId.isBlank() ? "unknown" : agentId);
        }
        return agentId == null || agentId.isBlank()
                ? new Attribution("system", "unknown")
                : new Attribution("agent", agentId);
    }
}
