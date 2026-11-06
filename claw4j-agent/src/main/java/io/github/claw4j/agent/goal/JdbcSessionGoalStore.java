package io.github.claw4j.agent.goal;

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
import java.util.function.Supplier;

/**
 * 目标落在 {@code claw4j_session.conversation_goal} 上（ADR-0027 决定三）。
 *
 * <p><b>主键就是"一个会话一个目标"</b>：不另加"当前目标"标志位。有标志位的版本里，
 * "两行都是当前"与"一行都不是"都写得出来，而修它要靠一条没人会写的唯一索引。
 *
 * <p><b>每次改动都走一条语句</b>：{@code insert ... on conflict do nothing}、
 * {@code update ... where status <> 'complete'} 各自把条件写在 SQL 里。
 * "先读再判再写"在同会话并发下会同时通过读，然后两边都写——
 * 而目标的 {@code revision} 正是给"谁最后改的"留的痕迹，它不该被两个并发写各加一次。
 *
 * <p><b>库不可用时降级为空</b>：目标读不到的表现是"这一轮没有目标"，而不是整轮 500。
 * 代价要说清——降级期间模型看不到目标，于是"接着推进"会退化成普通一轮；
 * 计数（{@value #ERRORS}）不为 0 就说明这段时间目标一直是瞎的。
 */
public final class JdbcSessionGoalStore implements SessionGoalStore {

    /** 读写失败的计数。它不是 0 就说明这段时间的目标一直没生效。 */
    public static final String ERRORS = "claw4j.goal.errors";

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionGoalStore.class);

    private static final String COLUMNS = "select session_key, status, objective, status_note,"
            + " token_budget, tokens_used, revision, created_at, updated_at"
            + " from claw4j_session.conversation_goal";

    private static final String RETURNING = " returning session_key, status, objective, status_note,"
            + " token_budget, tokens_used, revision, created_at, updated_at";

    /**
     * 新建。{@code on conflict do nothing} 而不是 {@code do update}：
     * "已经有一个目标了"在这里是一个<b>正常结果</b>（返回空，调用方去说那句话），
     * 不是一次覆盖。
     */
    private static final String START = "insert into claw4j_session.conversation_goal"
            + " (session_key, status, objective, token_budget) values (?, 'active', ?, ?)"
            + " on conflict (session_key) do nothing" + RETURNING;

    /**
     * 改写：只动 objective 与 revision。状态与用量一个字都不碰。
     *
     * <p>{@code status <> 'complete'} 是照 OpenAI 的排错表：{@code goal is already complete}
     * 的意思是"目标必须先被显式 resume 才能编辑、暂停或阻塞"。
     * 少了这一句，一次误触就能把一件已经收了尾的事改回"还在做的样子"，
     * 而它的用量与完成时间都留在原处——那种行看起来正常，对不上账。
     */
    private static final String EDIT = "update claw4j_session.conversation_goal"
            + " set objective = ?, revision = revision + 1, updated_at = now()"
            + " where session_key = ? and status <> 'complete'" + RETURNING;

    /**
     * 改状态。{@code status <> 'complete'} 写在 SQL 里：达成之后不能直接 pause / block，
     * 要先 resume（原文的排错表：{@code goal is already complete}）。
     */
    private static final String SET_STATUS = "update claw4j_session.conversation_goal"
            + " set status = ?, status_note = ?, revision = revision + 1, updated_at = now()"
            + " where session_key = ? and status <> 'complete'" + RETURNING;

    /**
     * 恢复：状态回 active；**只有 {@code budget_limited} 才把用量清零**（OpenClaw：resume 开新窗口）。
     *
     * <p>{@code status = 'budget_limited'} 判的是更新之前的那一行：PostgreSQL 保证同一条
     * update 里所有 set 表达式读的都是同一份旧快照，所以这一句与 {@code status = 'active'}
     * 不会互相看到对方刚写进去的值。
     *
     * <p><b>没有 {@code status <> 'complete'} 那一句</b>：完成的也能 resume。
     * 原文的状态表把 {@code complete} 写成"保持达成，直到你显式地用 {@code /goal resume}
     * 再次推进同一个目标"；命令表里也写着 resume "恢复已暂停、已阻塞、受限或<b>已完成</b>的目标"。
     * 别把另一句读成"不能恢复"：那句"终止状态"讲的是
     * <b>不能直接 edit / pause / block</b>（见 {@link #EDIT} 与 {@link #SET_STATUS}）。
     */
    private static final String RESUME = "update claw4j_session.conversation_goal"
            + " set tokens_used = case when status = 'budget_limited' then 0 else tokens_used end,"
            + " status = 'active', status_note = ?, revision = revision + 1, updated_at = now()"
            + " where session_key = ? and status <> 'active'" + RETURNING;

    /**
     * 记账，并在撞上预算的同一条语句里进 {@code budget_limited}。
     *
     * <p>{@code token_budget is not null and tokens_used + ? >= token_budget} 里的
     * {@code tokens_used} 是<b>更新之前</b>的值，所以这一句回答的是"加完这一笔有没有到"。
     * 拆成"先加再读再改状态"会有两步之间的窗口：并发的两条消息各自看到没超，
     * 结果超了却没进受限状态。
     */
    private static final String ADD_TOKENS = "update claw4j_session.conversation_goal"
            + " set tokens_used = tokens_used + ?,"
            + " status = case when status not in ('complete', 'budget_limited')"
            + "               and token_budget is not null and tokens_used + ? >= token_budget"
            + "          then 'budget_limited' else status end,"
            + " revision = revision + case when status not in ('complete', 'budget_limited')"
            + "               and token_budget is not null and tokens_used + ? >= token_budget"
            + "          then 1 else 0 end,"
            + " updated_at = now()"
            + " where session_key = ?" + RETURNING;

    private static final String CLEAR = "delete from claw4j_session.conversation_goal"
            + " where session_key = ?";

    private final JdbcTemplate jdbc;

    private final MeterRegistry registry;

    /** 只在"从坏到好"与"从好到坏"的边沿打日志，不在每条失败上重复。 */
    private volatile boolean degraded;

    public JdbcSessionGoalStore(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Override
    public Optional<SessionGoal> of(String sessionKey) {
        return guard("读一个会话的目标", () -> jdbc.query(COLUMNS + " where session_key = ?",
                ROW, sessionKey).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> start(String sessionKey, String objective, Long tokenBudget) {
        return guard("新建一个目标", () -> jdbc.query(START, ROW,
                sessionKey, objective, tokenBudget).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> edit(String sessionKey, String objective) {
        return guard("改写一个目标", () -> jdbc.query(EDIT, ROW,
                objective, sessionKey).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> setStatus(String sessionKey, GoalStatus status, String note) {
        return guard("改一个目标的状态", () -> jdbc.query(SET_STATUS, ROW,
                status.column(), note, sessionKey).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> resume(String sessionKey, String note) {
        return guard("恢复一个目标", () -> jdbc.query(RESUME, ROW,
                note, sessionKey).stream().findFirst().orElse(null));
    }

    @Override
    public Optional<SessionGoal> addTokens(String sessionKey, long tokens) {
        return guard("给一个目标记用量", () -> jdbc.query(ADD_TOKENS, ROW,
                tokens, tokens, tokens, sessionKey).stream().findFirst().orElse(null));
    }

    @Override
    public boolean clear(String sessionKey) {
        return guard("删掉一个目标", () -> jdbc.update(CLEAR, sessionKey)).orElse(0) > 0;
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
                GoalStatus.fromColumn(rs.getString("status")),
                rs.getString("objective"),
                rs.getString("status_note"),
                rs.getObject("token_budget", Long.class),
                rs.getLong("tokens_used"),
                rs.getInt("revision"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
