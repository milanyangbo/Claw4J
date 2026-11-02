package io.github.claw4j.agent.run;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
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
    public Optional<Run> start(String owner, String principalDigest, String goal, NotifyPolicy notifyPolicy) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        return guard("开一个 run", () -> {
            jdbc.update("insert into claw4j_run.agent_run"
                            + " (id, owner, principal_digest, goal, status, notify_policy, budget_used,"
                            + " created_at, updated_at)"
                            + " values (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                    id, owner, principalDigest, goal, RunStatus.RUNNING.column(),
                    notifyPolicy == null ? NotifyPolicy.DEFAULT.column() : notifyPolicy.column(),
                    timestamp(now), timestamp(now));
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
    public void attachPrincipal(String runId, Principal principal) {
        if (runId == null || principal == null) {
            return;
        }
        guard("记下 run 的发起人", () -> jdbc.update(
                "update claw4j_run.agent_run set principal_snapshot = ? where id = ?",
                PrincipalSnapshot.encode(principal), runId));
    }

    /**
     * 接上一个 run：只改状态，不插新行——"接上"不是"再来一次"。
     *
     * <p>{@code owner} 进 where 而不是先查后判：拿着别人的 runId 来"继续"，
     * 落到的是另开一个 run，而不是把别人的事继续下去。多一次往返换来的
     * 只是能把"不存在"和"不是你的"区分开，而调用方对两者的处理完全一样。
     *
     * <p>{@code status <> suspended} 同理：等审批的 run 只由审批放行，不由"继续"放行。
     */
    @Override
    public Optional<Run> resume(String runId, String owner) {
        return guard("接上一个 run", () -> {
            int updated = jdbc.update("update claw4j_run.agent_run set status = ?, updated_at = ?"
                            + " where id = ? and owner = ? and status <> ?",
                    RunStatus.RUNNING.column(), timestamp(Instant.now()), runId, owner,
                    RunStatus.SUSPENDED.column());
            if (updated == 0) {
                count("resume_rejected");
                return null;
            }
            count("resumed");
            return jdbc.query(RUN_COLUMNS + " where id = ?", this::runOf, runId)
                    .stream().findFirst().orElse(null);
        });
    }

    /**
     * 追加一步。审计那几样（P07）跟着同一行进去——它们不是另一张表的事，
     * 而"另建一张审计表"正是这一票明确不要的东西（ADR-0023）。
     *
     * <p>{@code audit} 为空时六列全写 null：模型轮与计划那两类 step 与审计无关，
     * 给它们编一个默认值只会让"这一行有没有审计"变得看不出来。
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
    public Optional<Run> reopen(String runId) {
        return guard("审批放行后接着做", () -> {
            int updated = jdbc.update("update claw4j_run.agent_run set status = ?, updated_at = ?"
                            + " where id = ? and status = ?",
                    RunStatus.RUNNING.column(), timestamp(Instant.now()), runId,
                    RunStatus.SUSPENDED.column());
            if (updated == 0) {
                count("reopen_missed");
                return null;
            }
            count("reopened");
            return jdbc.query(RUN_COLUMNS + " where id = ?", this::runOf, runId)
                    .stream().findFirst().orElse(null);
        });
    }

    /**
     * 收尾，顺手写上这一轮的回答全文。
     *
     * <p>{@code result} 为 null 时用 {@code coalesce} 留着旧值：失败与"额度用尽"那两条路
     * 没有新结果，而它们不该把上一轮的答案抹掉——"这件事最后做出来的是什么"
     * 在那些时候正是要被问的问题。
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

    /**
     * 停在这里等人批（P02）。
     *
     * <p>{@code and status = 'running'} 不是防御式编程：一条迟到一小时的批准
     * 不该把一件已经做完的事拉回 suspended，而那种状态会让人再去"继续"它一遍。
     */
    @Override
    public void suspend(String runId) {
        guard("把 run 停下来等审批", () -> {
            int updated = jdbc.update("update claw4j_run.agent_run set status = ?, updated_at = ?"
                            + " where id = ? and status = ?",
                    RunStatus.SUSPENDED.column(), timestamp(Instant.now()), runId,
                    RunStatus.RUNNING.column());
            if (updated == 0) {
                count("suspend_missed");
            } else {
                count("suspended");
            }
            return null;
        });
    }

    @Override
    public Optional<Principal> principalOf(String runId) {
        return guard("读 run 的发起人", () -> {
            String snapshot = jdbc.queryForObject(
                    "select principal_snapshot from claw4j_run.agent_run where id = ?",
                    String.class, runId);
            return PrincipalSnapshot.decode(snapshot).orElse(null);
        });
    }

    /**
     * 把等审批的那一步改成结论（P02）。
     *
     * <p>条件里带 {@code status = 'awaiting'}：这条语句只该改"正等着的那一步"。
     * 去掉它就会把同一个 run 里历史上所有审批步骤一起改写，
     * 而表现是"上次被拒的那一步忽然变成已做"。
     */
    @Override
    public void settleApprovalStep(String runId, StepStatus status) {
        guard("把等审批的那一步改成结论", () -> {
            int updated = jdbc.update("update claw4j_run.agent_step set status = ?"
                            + " where run_id = ? and kind = ? and status = ?",
                    status.column(), runId, StepKind.APPROVAL.column(), StepStatus.AWAITING.column());
            if (updated == 0) {
                count("settle_missed");
            }
            return null;
        });
    }

    /**
     * 读用量。三列一次读完：判"还能不能继续"要同时看三项，
     * 分三次读的话，中间那次读到的可能是已经被别人改动过的数。
     */
    @Override
    public Optional<Budget> spent(String runId) {
        return guard("读一个 run 的用量", () -> jdbc.query(
                "select budget_used, budget_tokens, budget_ms from claw4j_run.agent_run where id = ?",
                this::budgetOf, runId).stream().findFirst().orElse(null));
    }

    /**
     * 加一笔用量。
     *
     * <p><b>在 SQL 里累加而不是"读出来、加了、写回去"</b>：后一种写法在两轮并发时
     * 会用旧值覆盖新值，表现是"额度凭空多出来"——而预算最要紧的性质就是它只会变少。
     *
     * <p>加完再读一次是为了把结果交回去：调用方要用它判"这一轮是不是刚好撞线"，
     * 让它自己拿旧值加一遍等于把算术推回给调用方。
     */
    @Override
    public Optional<Budget> charge(String runId, int toolCalls, long tokens, long millis) {
        return guard("记一笔用量", () -> {
            jdbc.update("update claw4j_run.agent_run set budget_used = budget_used + ?,"
                            + " budget_tokens = budget_tokens + ?, budget_ms = budget_ms + ?, updated_at = ?"
                            + " where id = ?",
                    toolCalls, tokens, millis, timestamp(Instant.now()), runId);
            count("charged");
            return jdbc.query(
                    "select budget_used, budget_tokens, budget_ms from claw4j_run.agent_run where id = ?",
                    this::budgetOf, runId).stream().findFirst().orElse(null);
        });
    }

    @Override
    public List<Step> stepsOf(String runId) {
        return guard("读一个 run 的 step", () -> jdbc.query(
                STEP_COLUMNS + " where run_id = ? order by seq",
                this::stepOf, runId)).orElseGet(List::of);
    }

    /**
     * 当前这版计划：从 {@code plan_ref} 记的那个起始 seq 往后取。
     *
     * <p>{@code plan_ref} 为空就是"还没写过计划"——这时候直接返回空，
     * 而不是退化成"全部 plan 行"：后者在计划被重写过之后会把旧版算回来，
     * 于是"还剩什么没做"多了几条早就决定不做的事。
     */
    @Override
    public List<Step> planOf(String runId) {
        return guard("读一个 run 的计划", () -> {
            Integer from = planFrom(runId);
            if (from == null) {
                return List.<Step>of();
            }
            return jdbc.query(STEP_COLUMNS
                            + " where run_id = ? and kind = ? and seq >= ? order by seq",
                    this::stepOf, runId, StepKind.PLAN.column(), from);
        }).orElseGet(List::of);
    }

    @Override
    public Optional<List<Step>> setPlan(String runId, List<String> items) {
        Objects.requireNonNull(items, "items");
        return guard("重写一个 run 的计划", () -> {
            List<Step> written = new ArrayList<>();
            for (String item : items) {
                // 每一步都走 append：序号只有一处分配（max(seq) + 1），
                // 在这里另算一遍就是并发下撞号的老路。
                append(new StepInput(runId, StepKind.PLAN, item, null, null, StepStatus.PENDING))
                        .ifPresent(written::add);
            }
            if (written.isEmpty()) {
                return written;
            }
            // 起始 seq 记在 run 上，读的时候靠它把"当前这一版"切出来。
            jdbc.update("update claw4j_run.agent_run set plan_ref = ?, updated_at = ? where id = ?",
                    String.valueOf(written.getFirst().seq()), timestamp(Instant.now()), runId);
            count("plan_set", "steps", String.valueOf(items.size()));
            return written;
        });
    }

    @Override
    public Optional<Step> markPlan(String runId, int ordinal, StepStatus status) {
        return guard("改一步计划的状态", () -> {
            List<Step> plan = planOf(runId);
            if (ordinal < 1 || ordinal > plan.size()) {
                count("plan_mark_missed");
                return null;
            }
            Step target = plan.get(ordinal - 1);
            jdbc.update("update claw4j_run.agent_step set status = ? where run_id = ? and seq = ?",
                    status.column(), runId, target.seq());
            count("plan_marked", "status", status.column());
            return new Step(target.runId(), target.seq(), target.kind(), target.input(),
                    target.toolNames(), target.resultSummary(), status, target.createdAt());
        });
    }

    /** @return 当前这版计划的起始 seq；没写过计划时是 null */
    private Integer planFrom(String runId) {
        String value = jdbc.queryForObject(
                "select plan_ref from claw4j_run.agent_run where id = ?", String.class, runId);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        }
        catch (NumberFormatException notANumber) {
            // 手里这个值不是序号，就是"没有当前计划"——不要靠抛异常把一次提问打成 500。
            log.warn("run {} 的 plan_ref 不是一个序号：{}", runId, value);
            return null;
        }
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
