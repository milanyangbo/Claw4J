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

    /** 照 OpenClaw：30 天。 */
    public static final Duration RETENTION = Duration.ofDays(30);

    /** 照 OpenClaw：10 万行。 */
    public static final int MAX_ROWS = 100_000;

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
                (event_id, kind, action, status, error_code, run_id, session_key, agent_id,
                 actor, actor_role, channel, tool_name, args_digest, approval_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        if (scope.runId() == null || scope.runId().isBlank()) {
            countSkipped();
            return;
        }
        try {
            jdbc.update(INSERT,
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
                    scope.agentId(),
                    scope.actor(),
                    scope.actorRole(),
                    scope.channel(),
                    event.toolName(),
                    event.argsDigest(),
                    event.approvalId());
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
        prune();
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
}
