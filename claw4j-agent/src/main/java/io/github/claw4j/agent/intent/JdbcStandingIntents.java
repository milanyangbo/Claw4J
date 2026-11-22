package io.github.claw4j.agent.intent;

import io.github.claw4j.agent.session.SessionSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 意图的唯一实现：一张表 + 五条 SQL（ADR-0036）。
 *
 * <p>表名靠 {@link SessionSchema#qualifier(String)} 拼，不写死 schema——写死之后改配会继续指向
 * 旧 schema，而症状是功能悄悄失效。
 *
 * <p>{@link #fire} 是一条 {@code update ... returning 影响行数}：判据全在 {@code where} 里
 * （状态、过期、冷却、预算），所以两个并发的同一条消息不可能都记上。先 select 再 update 的写法
 * 在并发下会双触发，而用户的感受是"它说了两遍"。
 */
public final class JdbcStandingIntents implements StandingIntents {

    private final String insert;

    private final String selectByOwner;

    private final String selectEligible;

    private final String cancel;

    private final String fire;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc   主连接池
     * @param schema 会话 schema 名（与进度卡同一个来源）
     */
    public JdbcStandingIntents(JdbcTemplate jdbc, String schema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        String table = SessionSchema.qualifier(schema) + "standing_intents";
        this.insert = """
                insert into %s
                    (id, owner, description, trigger_keywords, channel_scope, sender_scope,
                     creator_sender, status, expires_at, max_fires, fire_count, cooldown_seconds,
                     created_at, source_session_id)
                values (?, ?, ?, ?, ?, ?, ?, 'armed', ?, ?, 0, ?, ?, ?)
                """.formatted(table);
        this.selectByOwner = """
                select * from %s where owner = ? order by created_at desc
                """.formatted(table);
        this.selectEligible = """
                select * from %s
                 where owner = ?
                   and status in ('armed', 'fired')
                   and expires_at > ?
                   and fire_count < max_fires
                   and (last_fired_at is null
                        or last_fired_at + make_interval(secs => cooldown_seconds) <= ?)
                   and (channel_scope is null or channel_scope = coalesce(?, channel_scope))
                   and (sender_scope is null or sender_scope = coalesce(?, sender_scope))
                 order by created_at
                 limit ?
                """.formatted(table);
        this.cancel = """
                update %s set status = 'cancelled'
                 where owner = ? and id = ? and status in ('pending', 'armed', 'fired')
                """.formatted(table);
        this.fire = """
                update %s
                   set fire_count = fire_count + 1,
                       last_fired_at = ?,
                       status = case when fire_count + 1 >= max_fires then 'done' else 'fired' end
                 where id = ?
                   and status in ('armed', 'fired')
                   and expires_at > ?
                   and fire_count < max_fires
                   and (last_fired_at is null
                        or last_fired_at + make_interval(secs => cooldown_seconds) <= ?)
                """.formatted(table);
    }

    @Override
    public StandingIntent create(New spec) {
        Objects.requireNonNull(spec, "spec");
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                .toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        jdbc.update(insert, id, spec.owner(), spec.description(), spec.triggerKeywords(),
                spec.channelScope(), spec.senderScope(), spec.creatorSender(),
                Timestamp.from(spec.expiresAt()), spec.maxFires(), spec.cooldownSeconds(),
                Timestamp.from(now), spec.sourceSessionId());
        return new StandingIntent(id, spec.owner(), spec.description(), spec.triggerKeywords(),
                spec.channelScope(), spec.senderScope(), spec.creatorSender(),
                StandingIntent.Status.ARMED, spec.expiresAt(), spec.maxFires(), 0,
                spec.cooldownSeconds(), null, now, spec.sourceSessionId());
    }

    @Override
    public List<StandingIntent> list(String owner) {
        return jdbc.query(selectByOwner, mapper(), owner);
    }

    @Override
    public boolean cancel(String owner, String id) {
        if (owner == null || id == null) {
            return false;
        }
        return jdbc.update(cancel, owner, id) > 0;
    }

    @Override
    public List<StandingIntent> eligible(String owner, String channel, String sender,
                                         Instant now, int limit) {
        return jdbc.query(selectEligible, mapper(), owner, Timestamp.from(now),
                Timestamp.from(now), channel, sender, limit);
    }

    @Override
    public boolean fire(String id, Instant now) {
        if (id == null) {
            return false;
        }
        return jdbc.update(fire, Timestamp.from(now), id, Timestamp.from(now),
                Timestamp.from(now)) > 0;
    }

    private static RowMapper<StandingIntent> mapper() {
        return (rs, row) -> new StandingIntent(
                rs.getString("id"),
                rs.getString("owner"),
                rs.getString("description"),
                rs.getString("trigger_keywords"),
                rs.getString("channel_scope"),
                rs.getString("sender_scope"),
                rs.getString("creator_sender"),
                StandingIntent.Status.of(rs.getString("status")),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                rs.getInt("max_fires"),
                rs.getInt("fire_count"),
                rs.getInt("cooldown_seconds"),
                instant(rs.getObject("last_fired_at", OffsetDateTime.class)),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getString("source_session_id"));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
