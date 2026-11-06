package io.github.claw4j.agent.run;

import io.github.claw4j.domain.NotifyPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * run / step 的 JDBC 落点，表在 {@code claw4j_run} 这个 schema 下（V6）。
 *
 * <p><b>SQL 一律写全限定名</b>：这张表的语句全在我们手里，不需要靠连接的
 * {@code currentSchema} 指定落点，也就不需要第二个连接池——
 * 少一个 {@code DataSource} 的意义不小，容器里出现两个会让
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} 安静地不成立。
 *
 * <p><b>写失败降级而不是抛出</b>：run 是记账与审计的挂载点，不是回答的前置条件。
 * 表没迁、库没起的时候，一次提问仍然要能答上来，只是这一件"事"没被记下——
 * 与会话记忆（{@code FaultTolerantSessionService}）同一个判断。
 * 降级的次数记在 {@code claw4j.run.errors} 上，别让它变成静默。
 *
 * <p><b>只记一次 warn</b>：库挂掉之后每一次提问都会失败，逐条打 ERROR 会让日志
 * 被同一句话淹没，真正的新问题反而看不见。恢复成功时再记一条 INFO。
 */
public class JdbcRunStore implements RunStore {

    /** run 指标的前缀。与 {@code claw4j.tool.calls} 同一条判定纪律：指标回读，不问措辞。 */
    public static final String RUN_METRIC = "claw4j.run";

    /** step 的查询列。三类读法共用一份，改列只要改一处。 */
    private static final String STEP_COLUMNS =
            "select run_id, seq, kind, input, tool_names, result_summary, status, created_at"
                    + " from claw4j_run.agent_step";

    /**
     * run 的查询列。三个读法共用一份。
     *
     * <p>用量那三列（{@code budget_used / budget_tokens / budget_ms}，A04）每一次读 run
     * 都要带上：停在 {@code budget_exhausted} 的 run 只能靠它们回答"为什么停"。
     */
    private static final String RUN_COLUMNS =
            "select id, owner, principal_digest, goal, status, notify_policy, result,"
                    + " budget_used, budget_tokens, budget_ms, created_at"
                    + " from claw4j_run.agent_run";

    private static final Logger log = LoggerFactory.getLogger(JdbcRunStore.class);

    private final JdbcTemplate jdbc;

    /** 为 null 表示没装 actuator：照常落库，只是没有指标可报。 */
    private final MeterRegistry registry;

    private volatile boolean degraded;

    public JdbcRunStore(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    public JdbcRunStore(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = registry;
    }

    @Override
    public Optional<Run> start(String owner, String principalDigest, String goal,
                               NotifyPolicy notifyPolicy, String sessionId) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        return guard("开一个 run", () -> {
            jdbc.update("insert into claw4j_run.agent_run"
                            + " (id, owner, principal_digest, goal, status, notify_policy, budget_used,"
                            + " session_id, created_at, updated_at)"
                            + " values (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
                    id, owner, principalDigest, goal, RunStatus.RUNNING.column(),
                    notifyPolicy == null ? NotifyPolicy.DEFAULT.column() : notifyPolicy.column(),
                    sessionId, timestamp(now), timestamp(now));
            count("created", "policy", notifyPolicy == null
                    ? NotifyPolicy.DEFAULT.column() : notifyPolicy.column());
            return new Run(id, owner, principalDigest, goal, RunStatus.RUNNING,
                    notifyPolicy, Budget.NONE, null, now);
        });
    }

    /**
     * 存发起人的身份快照（P02）。
     *
     * <p>单独一条 update 而不是并进 insert：只有"可能被服务端叫醒"的那些 run 需要它，
     * 而"这件事有没有主"和"这件事是什么"是两个不同的问题。
     */
    @Override
    public Optional<Step> append(StepInput input) {
        return guard("追加一步", () -> {
            int seq = nextSeq(input.runId());
            Instant now = Instant.now();
            StepAudit audit = input.audit();
            jdbc.update("insert into claw4j_run.agent_step"
                            + " (id, run_id, seq, kind, input, tool_names, result_summary, status, created_at,"
                            + " actor, actor_role, outcome, args_digest, args_summary, approval_id)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), input.runId(), seq, input.kind().column(),
                    input.input(), input.toolNames(), input.resultSummary(), input.status().column(),
                    timestamp(now),
                    audit == null ? null : audit.actor(),
                    audit == null ? null : audit.role(),
                    audit == null ? null : audit.outcome(),
                    audit == null ? null : audit.argsDigest(),
                    audit == null ? null : audit.argsSummary(),
                    audit == null ? null : audit.approvalId());
            count("steps", "kind", input.kind().column());
            return new Step(input.runId(), seq, input.kind(), input.input(), input.toolNames(),
                    input.resultSummary(), input.status(), now);
        });
    }

    /**
     * 审批放行之后由服务端续跑（P02）。与 {@link #resume} 的唯一区别是<b>只认 suspended</b>：
     * 这条路上的唤醒理由是"有人批了"，它不该顺手把一件正在跑或已经跑完的事再拉起来一次。
     */
    @Override
    public void finish(String runId, RunStatus status, String result) {
        guard("收尾一个 run", () -> {
            jdbc.update("update claw4j_run.agent_run set status = ?, result = coalesce(?, result),"
                            + " updated_at = ? where id = ?",
                    status.column(), result, timestamp(Instant.now()), runId);
            count("finished", "status", status.column());
            return null;
        });
    }

    @Override
    public Optional<Run> find(String runId) {
        return guard("读一个 run", () -> jdbc.query(RUN_COLUMNS + " where id = ?",
                this::runOf, runId).stream().findFirst().orElse(null));
    }

    @Override
    public List<Step> stepsOf(String runId) {
        return guard("读一个 run 的 step", () -> jdbc.query(
                STEP_COLUMNS + " where run_id = ? order by seq",
                this::stepOf, runId)).orElseGet(List::of);
    }

    /** 序号由存储分配：调用方算会在并发下撞号，而撞号的表现是"第几步"有两个答案。 */
    private int nextSeq(String runId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(seq), 0) from claw4j_run.agent_step where run_id = ?",
                Integer.class, runId);
        return (max == null ? 0 : max) + 1;
    }

    private Run runOf(ResultSet row, int index) throws SQLException {
        return new Run(row.getString("id"), row.getString("owner"), row.getString("principal_digest"),
                row.getString("goal"), RunStatus.fromColumn(row.getString("status")),
                NotifyPolicy.fromColumn(row.getString("notify_policy")),
                new Budget(row.getInt("budget_used"), row.getLong("budget_tokens"),
                        row.getLong("budget_ms")),
                row.getString("result"), instant(row, "created_at"));
    }

    private Budget budgetOf(ResultSet row, int index) throws SQLException {
        return new Budget(row.getInt("budget_used"), row.getLong("budget_tokens"),
                row.getLong("budget_ms"));
    }

    private Step stepOf(ResultSet row, int index) throws SQLException {
        return new Step(row.getString("run_id"), row.getInt("seq"),
                StepKind.fromColumn(row.getString("kind")), row.getString("input"),
                row.getString("tool_names"), row.getString("result_summary"),
                StepStatus.fromColumn(row.getString("status")), instant(row, "created_at"));
    }

    /**
     * 时间用 {@code OffsetDateTime} 而不是 {@code Instant}：pgjdbc 不认 {@code Instant}
     * （报错是 "Can't infer the SQL type"，而它会被降级逻辑吞掉，表现是"run 没开出来"）。
     * 存 UTC 是因为这张表要能按时间查——存本地时区会在换机器的那天出现一段对不上的窗口。
     */
    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * 一次降级，一次 warn。
     *
     * <p>抛出去的后果是对话 500，而 500 与"这次没记账"是两件代价完全不同的事——
     * 前者用户看得见且无法自救，后者只有审计会少一行。
     */
    private <T> Optional<T> guard(String what, Supplier<T> work) {
        try {
            T result = work.get();
            if (degraded) {
                degraded = false;
                log.info("run 落库已恢复：{}", what);
            }
            return Optional.ofNullable(result);
        } catch (RuntimeException failure) {
            count("errors");
            if (!degraded) {
                degraded = true;
                log.warn("run 落库失败，这一轮降级为“没有 run”：{}（之后同类失败不再逐条打日志）",
                        what, failure);
            }
            return Optional.empty();
        }
    }

    private void count(String suffix) {
        count(suffix, null, null);
    }

    /**
     * 计数。没有 registry 时什么都不做——装不装 actuator 只影响报不报得出去，
     * 不影响该不该落库（与 {@code MeteredToolCallback} 同一个判断）。
     */
    private void count(String suffix, String tagKey, String tagValue) {
        if (registry == null) {
            return;
        }
        Counter.Builder counter = Counter.builder(RUN_METRIC + "." + suffix)
                .description("run 的计数：" + suffix);
        if (tagKey != null) {
            counter = counter.tag(tagKey, tagValue);
        }
        counter.register(registry).increment();
    }
}
