package io.github.claw4j.agent.session;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * 指针的 JDBC 实现：一条语句完成"读旧行 → 判过期 → 有条件换段 → 刷新时间 → 回段"。
 *
 * <p><b>为什么不用"先 select 再 update"</b>：那是三次往返，而且两次调用之间没有原子性。
 * 更具体的危害在"不换段"这条<b>最常见</b>的路径上：为了省一次写，人们会写成
 * {@code update ... where 过期}，而过期不成立时它返回 0 行、{@code touched_at} 也不刷新。
 * 于是空闲判定永远从"这一段的第一句话"算起——120 分钟之后，<b>每一句话都会换段</b>，
 * 而那一刻之前一切正常。
 *
 * <p><b>{@code case} 与 {@code where} 的区别就在这</b>：{@code case} 版本永远更新一行、
 * 永远刷新 {@code touched_at}、永远返回段；{@code where} 版本三件事都只在"确实换段"时发生。
 *
 * <p><b>表名写全限定</b>（{@code claw4j_session.conversation_pointer}）：这个类用的是主连接池，
 * 它的 {@code search_path} 里没有 {@code claw4j_session}。会话那边另开一个池是因为
 * framework 生成的 SQL 不带前缀；这里 SQL 全在我们手里，为它多开一个池不划算——
 * 而且容器里出现第二个 {@code DataSource} 会安静地打掉一整套自动配置。
 */
public final class JdbcConversationPointerStore implements ConversationPointerStore {

    /**
     * 跨天或空闲，谁先到算谁。
     *
     * <p>判定放在 {@code case} 的 {@code when} 里、读的是 {@code conversation_pointer.}
     * 前缀的那一份，也就是<b>更新之前</b>的旧行。PostgreSQL 保证
     * {@code on conflict do update} 里所有 {@code set} 表达式都基于同一份旧行快照，
     * 所以两个 {@code case} 不会互相看到对方刚写进去的值。
     */
    private static final String DAILY_AND_IDLE = """
            insert into claw4j_session.conversation_pointer
                   (owner, channel, chat_id, segment, touched_at, day_started)
            values (?, ?, ?, ?, ?, ?)
            on conflict (owner, channel, chat_id) do update set
                segment     = case when cast(? as boolean)
                                     or conversation_pointer.day_started <> ?
                                     or conversation_pointer.touched_at < ?
                                   then excluded.segment else conversation_pointer.segment end,
                day_started = case when cast(? as boolean)
                                     or conversation_pointer.day_started <> ?
                                     or conversation_pointer.touched_at < ?
                                   then excluded.day_started else conversation_pointer.day_started end,
                touched_at  = excluded.touched_at
            returning segment
            """;

    /** 只看空闲（{@code mode: idle}）。与上面同构，少掉日历那一半。 */
    private static final String IDLE_ONLY = """
            insert into claw4j_session.conversation_pointer
                   (owner, channel, chat_id, segment, touched_at, day_started)
            values (?, ?, ?, ?, ?, ?)
            on conflict (owner, channel, chat_id) do update set
                segment     = case when cast(? as boolean)
                                     or conversation_pointer.touched_at < ?
                                   then excluded.segment else conversation_pointer.segment end,
                day_started = case when cast(? as boolean)
                                     or conversation_pointer.touched_at < ?
                                   then excluded.day_started else conversation_pointer.day_started end,
                touched_at  = excluded.touched_at
            returning segment
            """;

    private final JdbcTemplate jdbc;

    public JdbcConversationPointerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> segmentOf(Key key, Rotation rotation) {
        Timestamp now = Timestamp.from(rotation.now());
        Timestamp idleBefore = Timestamp.from(rotation.idleBefore());
        Date today = Date.valueOf(rotation.today());
        boolean forced = rotation.forced();
        String segment = rotation.dailyGate()
                ? jdbc.queryForObject(DAILY_AND_IDLE, String.class,
                key.owner(), key.channel(), key.chatId(), rotation.freshSegment(),
                now, today, forced, today, idleBefore, forced, today, idleBefore)
                : jdbc.queryForObject(IDLE_ONLY, String.class,
                key.owner(), key.channel(), key.chatId(), rotation.freshSegment(),
                now, today, forced, idleBefore, forced, idleBefore);
        return Optional.ofNullable(segment);
    }
}
