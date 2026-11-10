package io.github.claw4j.agent.goal;

import io.github.claw4j.agent.session.SessionSchema;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 目标的 JDBC 落点：它占 {@code session_nodes.entry_json} 的一个键（ADR-0029）。
 *
 * <p>不再是十七个 {@code goal_*} 列。加一个字段不用改表，代价是每次改动都要整份读出来、
 * 改一个值、再整份写回去——所以每个写方法都在一个事务里先 {@code select ... for update}
 * 锁住那一行。不锁的话，"读到还没超预算"与"加完这一笔"之间会插进另一条消息，结果是超了却
 * 没进受限状态，而那个错不会自己恢复。
 *
 * <p>判定从 SQL 搬到了 Java：{@code where goal_id = ? and goal_status <> 'complete'} 这种条件
 * 现在是方法里的一个 if。这样做的理由是 JSON 里没法一行写完七种状态的迁移，而把状态机摊在
 * 七条 {@code case when} 里读过一遍的人不会再想读第二遍。原子性由行锁保证，与写在 SQL 里等价。
 *
 * <p>所有写方法在没写成时返回空，不抛：目标写不进去不该让一整轮回答变成 500。调用方拿到空之后
 * 再 {@link #of} 读一次，用它区分"没有目标""目标已经换了""已经达成了"与"库连不上"。
 */
public final class JdbcSessionGoalStore implements SessionGoalStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionGoalStore.class);

    /** 读写失败的计数名。它不为 0 就说明目标功能当前没生效。 */
    static final String ERRORS = "claw4j.goal.failures";

    private static final String READ = """
            select (entry_json ->> 'totalTokens')::bigint as total_tokens,
                   entry_json -> 'goal' as goal
            from %1$ssession_nodes where session_key = ?
            """;

    /** 同一条读，加行锁。写路径用它。 */
    private static final String READ_FOR_UPDATE = READ + " for update";

    private static final String WRITE_WITH_GOAL = """
            update %1$ssession_nodes
               set entry_json = jsonb_set(jsonb_set(entry_json, '{goal}', ?::jsonb),
                                          '{totalTokens}', to_jsonb(?::bigint)),
                   updated_at = now()
             where session_key = ?
            """;

    private static final String WRITE_WITHOUT_GOAL = """
            update %1$ssession_nodes
               set entry_json = jsonb_set(entry_json - 'goal', '{totalTokens}', to_jsonb(?::bigint)),
                   updated_at = now()
             where session_key = ?
            """;

    private final JdbcTemplate jdbc;

    private final MeterRegistry registry;

    private final TransactionTemplate transaction;

    private final String read;
    private final String readForUpdate;
    private final String writeWithGoal;
    private final String writeWithoutGoal;

    /** 只在"从坏到好"与"从好到坏"的边沿打日志，不在每条失败上重复。 */
    private volatile boolean degraded;

    /**
     * 构造。
     *
     * @param jdbc 主连接池
     * @param schema 会话表所在的 schema。从 {@code claw4j.session.schema} 取，与框架那一路同源：
     *     本类在主连接池上跑（{@code search_path} 里没有会话 schema），只能写全限定名，
     *     于是"落在哪个 schema"必须由构造参数决定，不能由字面量决定
     * @param registry 指标登记表；null 表示不报错次数
     */
    public JdbcSessionGoalStore(JdbcTemplate jdbc, String schema, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        // 没有 DataSource 的 JdbcTemplate 只出现在单测；生产里它装配不起来。
        this.transaction = jdbc.getDataSource() == null
                ? null
                : new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        String qualifier = SessionSchema.qualifier(schema);
        this.read = READ.formatted(qualifier);
        this.readForUpdate = READ_FOR_UPDATE.formatted(qualifier);
        this.writeWithGoal = WRITE_WITH_GOAL.formatted(qualifier);
        this.writeWithoutGoal = WRITE_WITHOUT_GOAL.formatted(qualifier);
    }

    @Override
    public Optional<SessionGoal> of(String sessionKey) {
        return guard("读一个会话的目标", () -> {
            State state = load(read, sessionKey);
            return state == null ? null : state.goal();
        });
    }

    @Override
    public Optional<SessionGoal> start(String sessionKey, String objective, Long tokenBudget) {
        return mutate("新建一个目标", sessionKey, before -> {
            if (before.goal() != null) {
                return null;
            }
            Instant now = Instant.now();
            // 预算窗口从建目标那一刻的会话累计用量算起；fresh 恒为 true——这个数一直在这里带着，
            // 不存在"还没有新鲜快照"。只有迁移回填的历史行是 false。
            return new State(before.totalTokens(), new SessionGoal(
                    sessionKey, SessionGoal.SCHEMA_VERSION, UUID.randomUUID(), objective.trim(),
                    GoalStatus.ACTIVE, now, now, before.totalTokens(), true, 0L, tokenBudget, 0,
                    null, null, null, null, null, null));
        });
    }

    @Override
    public Optional<SessionGoal> edit(String sessionKey, UUID goalId, String objective) {
        return mutate("改写一个目标", sessionKey, before -> {
            SessionGoal goal = before.goal();
            // 达成之后不能改：目标必须先被显式 resume（原文的排错表）。
            if (goal == null || !goal.id().equals(goalId) || goal.status() == GoalStatus.COMPLETE) {
                return null;
            }
            return new State(before.totalTokens(), new SessionGoal(
                    goal.sessionKey(), goal.schemaVersion(), goal.id(), objective.trim(),
                    goal.status(), goal.createdAt(), Instant.now(), goal.tokenStart(),
                    goal.tokenStartFresh(), goal.tokensUsed(), goal.tokenBudget(),
                    goal.continuationTurns(), goal.note(), goal.pausedAt(), goal.blockedAt(),
                    goal.completedAt(), goal.usageLimitedAt(), goal.budgetLimitedAt()));
        });
    }

    @Override
    public Optional<SessionGoal> setStatus(String sessionKey, UUID goalId, GoalStatus status,
                                           String note) {
        return mutate("改一个目标的状态", sessionKey, before -> {
            SessionGoal goal = before.goal();
            if (goal == null || !goal.id().equals(goalId)) {
                return null;
            }
            // 达成的可以再 complete（幂等），但不能直接 pause 或 block——要先 resume。
            if (goal.status() == GoalStatus.COMPLETE && status != GoalStatus.COMPLETE) {
                return null;
            }
            Instant now = Instant.now();
            return new State(before.totalTokens(), new SessionGoal(
                    goal.sessionKey(), goal.schemaVersion(), goal.id(), goal.objective(), status,
                    goal.createdAt(), now, goal.tokenStart(), goal.tokenStartFresh(),
                    goal.tokensUsed(), goal.tokenBudget(), goal.continuationTurns(), note,
                    status == GoalStatus.PAUSED ? now : goal.pausedAt(),
                    status == GoalStatus.BLOCKED ? now : goal.blockedAt(),
                    // 重复 complete 保留首次完成时间（goals-transitions.ts:129）。
                    status == GoalStatus.COMPLETE
                            ? (goal.completedAt() == null ? now : goal.completedAt())
                            : goal.completedAt(),
                    status == GoalStatus.USAGE_LIMITED ? now : goal.usageLimitedAt(),
                    status == GoalStatus.BUDGET_LIMITED ? now : goal.budgetLimitedAt()));
        });
    }

    @Override
    public Optional<SessionGoal> resume(String sessionKey, UUID goalId, String note) {
        return mutate("恢复一个目标", sessionKey, before -> {
            SessionGoal goal = before.goal();
            // 本来就活跃的没有可恢复的。完成的也能恢复，所以这里没有 COMPLETE 的排除。
            if (goal == null || !goal.id().equals(goalId) || goal.status() == GoalStatus.ACTIVE) {
                return null;
            }
            // 只有受限状态才开新窗口：暂停一下就能白拿一份额度是错的。
            boolean freshWindow = goal.status() == GoalStatus.BUDGET_LIMITED
                    || goal.status() == GoalStatus.USAGE_LIMITED;
            return new State(before.totalTokens(), new SessionGoal(
                    goal.sessionKey(), goal.schemaVersion(), goal.id(), goal.objective(),
                    GoalStatus.ACTIVE, goal.createdAt(), Instant.now(),
                    freshWindow ? before.totalTokens() : goal.tokenStart(),
                    freshWindow || goal.tokenStartFresh(),
                    freshWindow ? 0L : goal.tokensUsed(),
                    goal.tokenBudget(), goal.continuationTurns(), note, goal.pausedAt(),
                    goal.blockedAt(),
                    // 从 complete 回来时清掉完成时刻：它属于上一次达成，再次达成要记新的。
                    // 从暂停或受限回来时不动它——那时它本来就是空的。
                    goal.status() == GoalStatus.COMPLETE ? null : goal.completedAt(),
                    freshWindow ? null : goal.usageLimitedAt(),
                    freshWindow ? null : goal.budgetLimitedAt()));
        });
    }

    @Override
    public Optional<SessionGoal> account(String sessionKey, Long deltaTokens) {
        if (deltaTokens == null) {
            // 拿不到用量：不重算。传 0 进来会让"没有数"伪装成"没有花费"，预算因此永远差一截。
            return of(sessionKey);
        }
        return mutate("给一个目标记用量", sessionKey, before -> {
            SessionGoal goal = before.goal();
            if (goal == null) {
                return null;
            }
            long total = before.totalTokens() + deltaTokens;
            // 起点还没被采纳过：这一轮把它定在"当前累计"上，旧用量保持——建目标那一刻还没有
            // 新鲜快照，之前的花费不算给它。
            long start = goal.tokenStartFresh() ? goal.tokenStart() : total;
            // 用量按快照差重算而不是累加：漏记一轮下一轮补回来。
            long used = goal.tokenStartFresh()
                    ? Math.max(goal.tokensUsed(), total - goal.tokenStart())
                    : goal.tokensUsed();
            boolean overBudget = goal.tokenBudget() != null && used >= goal.tokenBudget()
                    && goal.status() != GoalStatus.COMPLETE
                    && goal.status() != GoalStatus.BUDGET_LIMITED
                    && goal.status() != GoalStatus.USAGE_LIMITED;
            return new State(total, new SessionGoal(
                    goal.sessionKey(), goal.schemaVersion(), goal.id(), goal.objective(),
                    overBudget ? GoalStatus.BUDGET_LIMITED : goal.status(),
                    goal.createdAt(), Instant.now(), start,
                    // 记账之后起点一定是真实的：原来不是的那一份，这一轮已经被采纳成当前累计。
                    true,
                    used, goal.tokenBudget(), goal.continuationTurns(), goal.note(),
                    goal.pausedAt(), goal.blockedAt(), goal.completedAt(), goal.usageLimitedAt(),
                    overBudget ? Instant.now() : goal.budgetLimitedAt()));
        });
    }

    @Override
    public boolean clear(String sessionKey, UUID goalId) {
        return guard("删掉一个目标", () -> inTransaction(() -> {
            State before = load(readForUpdate, sessionKey);
            if (before == null || before.goal() == null || !before.goal().id().equals(goalId)) {
                return 0;
            }
            jdbc.update(writeWithoutGoal, before.totalTokens(), sessionKey);
            return 1;
        })).orElse(0) > 0;
    }

    /** 有事务就开，没有就直接跑（只在单测的 mock 上发生）。 */
    private <T> T inTransaction(Supplier<T> work) {
        return transaction == null ? work.get() : transaction.execute(status -> work.get());
    }

    /**
     * 锁行、读、改、写。
     *
     * @param what   出问题时日志里说的那句话
     * @param key    会话节点键
     * @param change 拿旧状态算新状态；返回 {@code null} 表示条件不满足，什么都不写
     * @return 写下去的那份目标；空表示没有节点、条件不满足、或没写进去
     */
    private Optional<SessionGoal> mutate(String what, String key, Function<State, State> change) {
        return guard(what, () -> inTransaction(() -> {
            State before = load(readForUpdate, key);
            if (before == null) {
                return null;
            }
            State after = change.apply(before);
            if (after == null) {
                return null;
            }
            if (after.goal() == null) {
                jdbc.update(writeWithoutGoal, after.totalTokens(), key);
            }
            else {
                jdbc.update(writeWithGoal, SessionGoalJson.toJson(after.goal()),
                        after.totalTokens(), key);
            }
            return after.goal();
        }));
    }

    private State load(String sql, String sessionKey) {
        List<State> rows = jdbc.query(sql, (rs, rowNum) -> new State(
                rs.getLong("total_tokens"),
                SessionGoalJson.fromJson(sessionKey, rs.getString("goal"))), sessionKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> Optional<T> guard(String what, Supplier<T> work) {
        try {
            T result = work.get();
            if (degraded) {
                degraded = false;
                log.info("目标存储已恢复：{}", what);
            }
            return Optional.ofNullable(result);
        }
        catch (RuntimeException failure) {
            Counter counter = registry == null ? null : Counter.builder(ERRORS)
                    .description("目标读写失败的次数").register(registry);
            if (counter != null) {
                counter.increment();
            }
            if (!degraded) {
                degraded = true;
                log.warn("目标存储不可用，这一轮当作没有目标：{}（之后同类失败不再逐条打日志）",
                        what, failure);
            }
            return Optional.empty();
        }
    }

    /**
     * 一行节点上目标相关的状态。
     *
     * @param totalTokens {@code entry_json.totalTokens}，预算按它算差
     * @param goal        目标；{@code null} 表示这一段没有目标
     */
    private record State(long totalTokens, SessionGoal goal) {
    }
}
