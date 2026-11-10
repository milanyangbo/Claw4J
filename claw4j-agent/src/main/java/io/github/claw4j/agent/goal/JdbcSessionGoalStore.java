package io.github.claw4j.agent.goal;

import io.github.claw4j.agent.session.SessionSchema;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 目标落在 {@code claw4j_session.session_entry} 的 {@code goal_*} 列上（票 06）。
 *
 * <p>不给目标单独建表：一条 session entry 同时装着 sessionId 与 goal，换段时 goal 跟着那一条
 * 一起没了。两张表的话，{@code /new} 只换段、没清目标，旧段的行会永久留在库里，还会被
 * {@code where status = 'active'} 的部分索引算进「现在有几个目标在推进」。合并之后，清目标
 * 在换段的那条 update 里，没有第二步可以忘。
 *
 * <p>每次改动都走一条语句，条件写在 SQL 里（{@code goal_id = ?}、
 * {@code goal_status <> 'complete'}）。先读再判再写在同会话并发下会同时通过读，然后两边
 * 都写；而 {@code goal_id} 正是「我改的是我看到的那一版目标」的凭据。
 *
 * <p>行由 {@code SessionEntryStore} 保证存在，本类只做 update。{@code /goal} 命令与模型工具
 * 都在一轮对话里执行，那一轮的入口已经 upsert 过这一行；行不在时写方法返回空，调用方回
 * 「目标没写进去」，而不会去 insert 一行缺 segment 的残行。
 *
 * <p>库不可用时降级为空：目标读不到的表现是这一轮没有目标，而不是整轮 500。代价是降级期间
 * 模型看不到目标，「接着推进」会退化成普通一轮；计数（{@value #ERRORS}）不为 0 就说明这段
 * 时间目标一直是瞎的。
 */
public final class JdbcSessionGoalStore implements SessionGoalStore {

    /** 读写失败的计数。它不是 0 就说明这段时间的目标一直没生效。 */
    public static final String ERRORS = "claw4j.goal.errors";

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionGoalStore.class);

    /**
     * 目标那一段列。顺序与 {@code SessionGoal} 的字段一致，select / returning 共用。
     */
    private static final String GOAL_COLUMNS = "goal_schema_version, goal_id, goal_objective,"
            + " goal_status, goal_token_start, goal_token_start_fresh, goal_tokens_used,"
            + " goal_token_budget, goal_continuation_turns, goal_last_status_note,"
            + " goal_paused_at, goal_blocked_at, goal_completed_at, goal_usage_limited_at,"
            + " goal_budget_limited_at, goal_created_at, goal_updated_at";

    private static final String RETURNING = " returning session_key, " + GOAL_COLUMNS;

    /**
     * SQL 模板里的 {@code %1$s} 是 schema 前缀，构造时由 {@link SessionSchema#qualifier} 展开。
     *
     * <p>不留字面量 {@code claw4j_session}：{@code claw4j.session.schema} 是可配的，而本类用的
     * 是主连接池（{@code search_path} 里没有会话 schema），只能写全限定名。写死的话改配之后
     * 这里每条语句都会报 {@code BadSqlGrammarException}，而那个异常被 {@link #guard} 降级成
     * 「这一轮没有目标」——表现是目标功能无声消失。
     */
    private static final String SELECT_TEMPLATE = "select session_key, " + GOAL_COLUMNS
            + " from %1$ssession_entry where session_key = ? and goal_id is not null";

    /**
     * 新建。{@code goal_id is null} 是「还没有目标」的唯一判据。
     *
     * <p>{@code goal_token_start = total_tokens}：预算窗口从建目标那一刻的会话累计用量算起。
     * {@code goal_token_start_fresh = true}：这个数一直由 {@code total_tokens} 带着，不存在
     * 「还没有新鲜快照」；只有迁移回填的历史行是 false。
     *
     * <p>不静默覆盖：已经有一个目标时 0 行，调用方去说「已经有目标了」。
     */
    private static final String START_TEMPLATE = "update %1$ssession_entry set"
            + " goal_schema_version = 1,"
            + " goal_id = gen_random_uuid(),"
            + " goal_objective = ?,"
            + " goal_status = 'active',"
            + " goal_token_start = total_tokens,"
            + " goal_token_start_fresh = true,"
            + " goal_tokens_used = 0,"
            + " goal_token_budget = ?,"
            + " goal_continuation_turns = 0,"
            + " goal_last_status_note = null,"
            + " goal_paused_at = null, goal_blocked_at = null, goal_completed_at = null,"
            + " goal_usage_limited_at = null, goal_budget_limited_at = null,"
            + " goal_created_at = now(), goal_updated_at = now()"
            + " where session_key = ? and goal_id is null" + RETURNING;

    /**
     * 改写：只动 objective 与 updated_at。状态、用量、五个时刻一个字都不碰。
     *
     * <p>{@code goal_status <> 'complete'} 是照它的排错表：{@code goal is already complete}
     * 的意思是"目标必须先被显式 resume 才能编辑、暂停或阻塞"。
     */
    private static final String EDIT_TEMPLATE = "update %1$ssession_entry set"
            + " goal_objective = ?, goal_updated_at = now()"
            + " where session_key = ? and goal_id = ? and goal_status <> 'complete'" + RETURNING;

    /**
     * 改状态。时间戳分开写：
     *
     * <ul>
     *   <li>{@code paused} / {@code blocked} / {@code usage_limited} / {@code budget_limited}
     *       记这一次进入该状态的时刻，再次进入要覆盖，因为那是新一次；
     *   <li>{@code complete} 用 {@code coalesce(goal_completed_at, now())}：重复 complete
     *       保留首次完成时间，这是 {@code goals-transitions.ts:129} 的规则。
     * </ul>
     *
     * <p>{@code goal_status <> 'complete'} 写在 SQL 里：达成之后不能直接 pause 或 block，
     * 要先 resume（原文的排错表）。resume 那条没有这个条件，完成的也能恢复。
     */
    private static final String SET_STATUS_TEMPLATE = "update %1$ssession_entry set"
            + " goal_status = ?,"
            + " goal_last_status_note = ?,"
            + " goal_paused_at = case when ? = 'paused' then now() else goal_paused_at end,"
            + " goal_blocked_at = case when ? = 'blocked' then now() else goal_blocked_at end,"
            + " goal_completed_at = case when ? = 'complete'"
            + "   then coalesce(goal_completed_at, now()) else goal_completed_at end,"
            + " goal_usage_limited_at = case when ? = 'usage_limited'"
            + "   then now() else goal_usage_limited_at end,"
            + " goal_budget_limited_at = case when ? = 'budget_limited'"
            + "   then now() else goal_budget_limited_at end,"
            + " goal_updated_at = now()"
            + " where session_key = ? and goal_id = ?"
            // 达成的可以再 complete（幂等、保留首次时间），但不能直接 pause 或 block：
            // OpenClaw 只挡「complete → 别的非 active 状态」（goals-transitions.ts:108-114）。
            + " and (goal_status <> 'complete' or goal_status = ?)" + RETURNING;

    /**
     * 恢复：状态回 active。
     *
     * <p>只有受限状态才开新窗口（{@code budget_limited} / {@code usage_limited}）：起点改成当前
     * 会话累计、用量清零、两个受限时刻清掉。暂停一下就能白拿一份额度是错的；而从
     * {@code budget_limited} 恢复后继续拿旧起点算，第一轮立刻又停在同一个地方。
     *
     * <p>从 complete 恢复时清掉 {@code completed_at}：完成属于上一次达成，再次达成要记新的
     * 时刻（{@code goals-transitions.ts:131-134} 就是这个行为）。内容、创建时间、用量都不动。
     *
     * <p>{@code goal_status <> 'active'}：本来就活跃的没有可恢复的，返回 0 行。完成的也能恢复，
     * 所以这里没有 {@code <> 'complete'}。
     */
    private static final String RESUME_TEMPLATE = "update %1$ssession_entry set"
            + " goal_status = 'active',"
            + " goal_token_start = case when goal_status in ('budget_limited', 'usage_limited')"
            + "   then total_tokens else goal_token_start end,"
            + " goal_token_start_fresh = case when goal_status in ('budget_limited', 'usage_limited')"
            + "   then true else goal_token_start_fresh end,"
            + " goal_tokens_used = case when goal_status in ('budget_limited', 'usage_limited')"
            + "   then 0 else goal_tokens_used end,"
            + " goal_budget_limited_at = case when goal_status in ('budget_limited', 'usage_limited')"
            + "   then null else goal_budget_limited_at end,"
            + " goal_usage_limited_at = case when goal_status in ('budget_limited', 'usage_limited')"
            + "   then null else goal_usage_limited_at end,"
            + " goal_completed_at = case when goal_status = 'complete' then null else goal_completed_at end,"
            + " goal_last_status_note = ?,"
            + " goal_updated_at = now()"
            + " where session_key = ? and goal_id = ? and goal_status <> 'active'" + RETURNING;

    /**
     * 对账：把这一轮的用量加进会话累计，再按快照差重算目标用量
     * （OpenClaw 的 {@code accountSessionGoalUsage}）。
     *
     * <p>三个 {@code case} 是同一件事的三面：
     * <ol>
     *   <li>{@code goal_token_start_fresh} 为假 → 这一轮把起点采纳成当前累计加这一轮，
     *       旧用量保持：建目标那一刻还没有新鲜快照，之前的花费不算给它；
     *   <li>为真 → {@code tokens_used = max(旧值, 累计 - 起点)}，漏记一轮下一轮补回来；
     *   <li>重算完达到预算 → 状态变 {@code budget_limited} 并记时刻。
     * </ol>
     *
     * <p>三个 {@code case} 都基于更新之前的行（PostgreSQL 保证同一 update 的所有 set 表达式
     * 读同一份旧快照），所以它们不会互相看到刚写进去的值；也正因为如此，每一处都要自己算一遍
     * 「加完这一笔之后的用量」——重复的是表达式，不是状态。
     *
     * <p>{@code goal_status not in ('complete', 'budget_limited', 'usage_limited')}：已经收尾
     * 或已经受限的目标只记账不改状态，把它改成「额度用完」等于把一件事重新打开。
     */
    private static final String ACCOUNT_TEMPLATE = "update %1$ssession_entry set"
            + " total_tokens = total_tokens + ?,"
            + " goal_token_start = case when goal_token_start_fresh then goal_token_start"
            + "   else total_tokens + ? end,"
            + " goal_token_start_fresh = true,"
            + " goal_tokens_used = case when goal_token_start_fresh"
            + "   then greatest(goal_tokens_used, total_tokens + ? - goal_token_start)"
            + "   else goal_tokens_used end,"
            + " goal_status = case when goal_status not in ('complete', 'budget_limited', 'usage_limited')"
            + "   and goal_token_budget is not null"
            + "   and (case when goal_token_start_fresh"
            + "             then greatest(goal_tokens_used, total_tokens + ? - goal_token_start)"
            + "             else goal_tokens_used end) >= goal_token_budget"
            + "   then 'budget_limited' else goal_status end,"
            + " goal_budget_limited_at = case when goal_status not in ('complete', 'budget_limited', 'usage_limited')"
            + "   and goal_token_budget is not null"
            + "   and (case when goal_token_start_fresh"
            + "             then greatest(goal_tokens_used, total_tokens + ? - goal_token_start)"
            + "             else goal_tokens_used end) >= goal_token_budget"
            + "   then now() else goal_budget_limited_at end,"
            + " goal_updated_at = now()"
            + " where session_key = ? and goal_id is not null" + RETURNING;

    /** 清空目标的 17 个列。行留着——它是会话节点，不是目标。 */
    private static final String CLEAR_TEMPLATE = "update %1$ssession_entry set"
            + " goal_schema_version = null, goal_id = null, goal_objective = null, goal_status = null,"
            + " goal_token_start = null, goal_token_start_fresh = null, goal_tokens_used = null,"
            + " goal_token_budget = null, goal_continuation_turns = null, goal_last_status_note = null,"
            + " goal_paused_at = null, goal_blocked_at = null, goal_completed_at = null,"
            + " goal_usage_limited_at = null, goal_budget_limited_at = null,"
            + " goal_created_at = null, goal_updated_at = null"
            + " where session_key = ? and goal_id = ?";

    private final JdbcTemplate jdbc;

    private final MeterRegistry registry;

    /** 七条语句的 schema 前缀已在构造时展开：{@code claw4j.session.schema} 改了它们跟着改。 */
    private final String select;
    private final String start;
    private final String edit;
    private final String setStatus;
    private final String resume;
    private final String account;
    private final String clear;

    /** 只在"从坏到好"与"从好到坏"的边沿打日志，不在每条失败上重复。 */
    private volatile boolean degraded;

    /**
     * 构造。
     *
     * @param jdbc 主连接池
     * @param schema 会话表所在的 schema。从 {@code claw4j.session.schema} 取，与框架那一路同源：
     *     本类在主连接池上跑（{@code search_path} 里没有会话 schema），只能写全限定名，
     *     于是「落在哪个 schema」必须由构造参数决定，不能由字面量决定
     * @param registry 指标登记表；null 表示不报错次数
     */
    public JdbcSessionGoalStore(JdbcTemplate jdbc, String schema, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        String qualifier = SessionSchema.qualifier(schema);
        this.select = SELECT_TEMPLATE.formatted(qualifier);
        this.start = START_TEMPLATE.formatted(qualifier);
        this.edit = EDIT_TEMPLATE.formatted(qualifier);
        this.setStatus = SET_STATUS_TEMPLATE.formatted(qualifier);
        this.resume = RESUME_TEMPLATE.formatted(qualifier);
        this.account = ACCOUNT_TEMPLATE.formatted(qualifier);
        this.clear = CLEAR_TEMPLATE.formatted(qualifier);
    }

    @Override
    public Optional<SessionGoal> of(String sessionKey) {
        return guard("读一个会话的目标", () -> jdbc.query(select, ROW, sessionKey)
                .stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> start(String sessionKey, String objective, Long tokenBudget) {
        return guard("新建一个目标", () -> jdbc.query(start, ROW,
                objective, tokenBudget, sessionKey).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> edit(String sessionKey, UUID goalId, String objective) {
        return guard("改写一个目标", () -> jdbc.query(edit, ROW,
                objective, sessionKey, goalId).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> setStatus(String sessionKey, UUID goalId, GoalStatus status,
                                           String note) {
        String column = status.column();
        return guard("改一个目标的状态", () -> jdbc.query(setStatus, ROW,
                column, note, column, column, column, column, column,
                sessionKey, goalId, column).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> resume(String sessionKey, UUID goalId, String note) {
        return guard("恢复一个目标", () -> jdbc.query(resume, ROW,
                note, sessionKey, goalId).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> account(String sessionKey, Long deltaTokens) {
        if (deltaTokens == null) {
            // 拿不到用量：不重算。传 0 进来会让「没有数」伪装成「没有花费」，
            // 而预算因此永远差一截。
            return of(sessionKey);
        }
        return guard("给一个目标记用量", () -> jdbc.query(account, ROW,
                deltaTokens, deltaTokens, deltaTokens, deltaTokens, deltaTokens,
                sessionKey).stream().findFirst().orElse(null));
    }

    @Override
    public boolean clear(String sessionKey, UUID goalId) {
        return guard("删掉一个目标", () -> jdbc.update(clear, sessionKey, goalId)).orElse(0) > 0;
    }

    private <T> Optional<T> guard(String what, Supplier<T> work) {
        try {
            T result = work.get();
            if (degraded) {
                degraded = false;
                log.info("目标存储已恢复：{}", what);
            }
            return Optional.ofNullable(result);
        } catch (RuntimeException failure) {
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

    private static final RowMapper<SessionGoal> ROW = JdbcSessionGoalStore::map;

    private static SessionGoal map(ResultSet rs, int rowNum) throws SQLException {
        return new SessionGoal(
                rs.getString("session_key"),
                rs.getInt("goal_schema_version"),
                rs.getObject("goal_id", UUID.class),
                rs.getString("goal_objective"),
                GoalStatus.fromColumn(rs.getString("goal_status")),
                instant(rs, "goal_created_at"),
                instant(rs, "goal_updated_at"),
                rs.getLong("goal_token_start"),
                rs.getBoolean("goal_token_start_fresh"),
                rs.getLong("goal_tokens_used"),
                rs.getObject("goal_token_budget", Long.class),
                rs.getInt("goal_continuation_turns"),
                rs.getString("goal_last_status_note"),
                instant(rs, "goal_paused_at"),
                instant(rs, "goal_blocked_at"),
                instant(rs, "goal_completed_at"),
                instant(rs, "goal_usage_limited_at"),
                instant(rs, "goal_budget_limited_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
