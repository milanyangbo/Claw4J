package io.github.claw4j.agent.session;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 状态行的 JDBC 实现：一条语句完成"读旧行 → 判过期 → 有条件换段 → 刷新时间 → 回段"，
 * 换段时同一句里把 {@code goal_*} 清空（票 06）。
 *
 * <p><b>为什么不用"先 select 再 update"</b>：那是三次往返，而且两次调用之间没有原子性。
 * 更具体的危害在"不换段"这条<b>最常见</b>的路径上：为了省一次写，人们会写成
 * {@code update ... where 过期}，而过期不成立时它返回 0 行、{@code last_interaction_at}
 * 也不刷新。于是空闲判定永远从"这一段的第一句话"算起——120 分钟之后，<b>每一句话都会换段</b>，
 * 而那一刻之前一切正常。
 *
 * <p><b>{@code case} 与 {@code where} 的区别就在这</b>：{@code case} 版本永远更新一行、
 * 永远刷新时间、永远返回段；{@code where} 版本三件事都只在"确实换段"时发生。
 *
 * <p><b>换段与清目标是同一条写</b>：它的 entry 就是"一条记录装着 sessionId 与 goal"，
 * 换 sessionId 时 goal 跟着没了。我们拆过两张表，代价是 {@code /new} 漏了 clear，
 * 旧段的 goal 永久留在库里。现在 17 个 {@code goal_*} 列与 {@code total_tokens} 一起在
 * {@code rotate} 为真时置空，没有第二步可以忘。
 *
 * <p><b>表名写全限定，schema 段来自配置</b>：这个类用的是主连接池，它的 {@code search_path}
 * 里没有会话 schema。会话那边另开一个池是因为 framework 生成的 SQL 不带前缀；
 * 这里 SQL 全在我们手里，为它多开一个池不划算——而且容器里出现第二个 {@code DataSource}
 * 会安静地打掉一整套自动配置。所以只能写全限定名，而<b>schema 名必须与框架那一路同源</b>
 * （都读 {@code claw4j.session.schema}）。
 */
public final class JdbcSessionEntryStore implements SessionEntryStore {

    /**
     * 换段时要把目标整份清掉的那些列。
     *
     * <p>逐列写而不是 {@code set (a, b, c) = (null, null, null)}：后者语法更短，
     * 但漏一列的表现是"换段之后还残留半份目标"，而 {@code session_entry_goal_all_or_nothing}
     * 会把它变成一句约束违反——那时报错指向约束，不指向漏掉的那一列。
     */
    private static final String CLEAR_GOALS = String.join(",\n            ",
            "goal_schema_version = case when {{rotate}} then null else session_entry.goal_schema_version end",
            "goal_id = case when {{rotate}} then null else session_entry.goal_id end",
            "goal_objective = case when {{rotate}} then null else session_entry.goal_objective end",
            "goal_status = case when {{rotate}} then null else session_entry.goal_status end",
            "goal_token_start = case when {{rotate}} then null else session_entry.goal_token_start end",
            "goal_token_start_fresh = case when {{rotate}} then null else session_entry.goal_token_start_fresh end",
            "goal_tokens_used = case when {{rotate}} then null else session_entry.goal_tokens_used end",
            "goal_token_budget = case when {{rotate}} then null else session_entry.goal_token_budget end",
            "goal_continuation_turns = case when {{rotate}} then null else session_entry.goal_continuation_turns end",
            "goal_last_status_note = case when {{rotate}} then null else session_entry.goal_last_status_note end",
            "goal_paused_at = case when {{rotate}} then null else session_entry.goal_paused_at end",
            "goal_blocked_at = case when {{rotate}} then null else session_entry.goal_blocked_at end",
            "goal_completed_at = case when {{rotate}} then null else session_entry.goal_completed_at end",
            "goal_usage_limited_at = case when {{rotate}} then null else session_entry.goal_usage_limited_at end",
            "goal_budget_limited_at = case when {{rotate}} then null else session_entry.goal_budget_limited_at end",
            "goal_created_at = case when {{rotate}} then null else session_entry.goal_created_at end",
            "goal_updated_at = case when {{rotate}} then null else session_entry.goal_updated_at end");

    /**
     * 跨天或空闲，谁先到算谁。
     *
     * <p>判定放在 {@code decided} 这个 CTE 里、读的是<b>更新之前</b>的行：
     * {@code coalesce(..., true)} 的意思是"这一行不存在"——那时 insert 不冲突，判定无所谓。
     *
     * <p><b>跨天比的是时刻</b>：{@code session_started_at < dailyResetAt}。
     * 旧实现存的是"算好的逻辑日"，改 at-hour 之后旧行还带着按旧边界算的日期，
     * 于是该换的段不换。
     */
    private static final String DAILY_AND_IDLE_TEMPLATE = """
            with decided as (
                select coalesce(
                    (select cast(? as boolean)
                            or s.session_started_at < ?
                            or s.last_interaction_at < ?
                     from {{schema}}session_entry s
                     where s.session_key = ?),
                    true) as rotate
            )
            insert into {{schema}}session_entry
                   (session_key, owner, channel, chat_id, segment, session_started_at,
                    last_interaction_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (session_key) do update set
                segment = case when {{rotate}} then excluded.segment
                               else session_entry.segment end,
                session_started_at = case when {{rotate}} then excluded.session_started_at
                                          else session_entry.session_started_at end,
                previous_segment = case when {{rotate}} then session_entry.segment
                                        else session_entry.previous_segment end,
                last_interaction_at = excluded.last_interaction_at,
                total_tokens = case when {{rotate}} then 0 else session_entry.total_tokens end,
                {{clear_goals}}
            returning segment
            """;

    /** 只看空闲（{@code mode: idle}）。与上面同构，少掉日历那一半。 */
    private static final String IDLE_ONLY_TEMPLATE = """
            with decided as (
                select coalesce(
                    (select cast(? as boolean) or s.last_interaction_at < ?
                     from {{schema}}session_entry s
                     where s.session_key = ?),
                    true) as rotate
            )
            insert into {{schema}}session_entry
                   (session_key, owner, channel, chat_id, segment, session_started_at,
                    last_interaction_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (session_key) do update set
                segment = case when {{rotate}} then excluded.segment
                               else session_entry.segment end,
                session_started_at = case when {{rotate}} then excluded.session_started_at
                                          else session_entry.session_started_at end,
                previous_segment = case when {{rotate}} then session_entry.segment
                                        else session_entry.previous_segment end,
                last_interaction_at = excluded.last_interaction_at,
                total_tokens = case when {{rotate}} then 0 else session_entry.total_tokens end,
                {{clear_goals}}
            returning segment
            """;

    private final JdbcTemplate jdbc;

    /** 两条语句的 schema 前缀已在构造时展开：{@code claw4j.session.schema} 改了它们跟着改。 */
    private final String dailyAndIdle;
    private final String idleOnly;

    /**
     * @param schema 会话表所在的 schema，与框架那一路同源（{@code claw4j.session.schema}）。
     *               本类在主连接池上跑，只能写全限定名，所以它必须由参数决定
     */
    public JdbcSessionEntryStore(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        String qualifier = SessionSchema.qualifier(schema);
        this.dailyAndIdle = DAILY_AND_IDLE_TEMPLATE
                .replace("{{schema}}", qualifier)
                .replace("{{rotate}}", "(select rotate from decided)")
                .replace("{{clear_goals}}", CLEAR_GOALS.replace("{{rotate}}", "(select rotate from decided)"));
        this.idleOnly = IDLE_ONLY_TEMPLATE
                .replace("{{schema}}", qualifier)
                .replace("{{rotate}}", "(select rotate from decided)")
                .replace("{{clear_goals}}", CLEAR_GOALS.replace("{{rotate}}", "(select rotate from decided)"));
    }

    @Override
    public Optional<String> segmentOf(Key key, Rotation rotation) {
        Timestamp now = Timestamp.from(rotation.now());
        Timestamp idleBefore = Timestamp.from(rotation.idleBefore());
        boolean forced = rotation.forced();
        String segment = rotation.dailyGate()
                ? jdbc.queryForObject(dailyAndIdle, String.class,
                forced, Timestamp.from(rotation.dailyResetAt()), idleBefore, key.sessionKey(),
                key.sessionKey(), key.owner(), key.channel(), key.chatId(),
                rotation.freshSegment(), now, now, now)
                : jdbc.queryForObject(idleOnly, String.class,
                forced, idleBefore, key.sessionKey(),
                key.sessionKey(), key.owner(), key.channel(), key.chatId(),
                rotation.freshSegment(), now, now, now);
        return Optional.ofNullable(segment);
    }
}
