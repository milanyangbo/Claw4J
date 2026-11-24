package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.port.AuditQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 审计读侧的实现（ADR-0037 票 03）：一条 SQL、一个游标。
 *
 * <p>翻页用 {@code seq} 而不是 {@code at}：同一毫秒里可以有好几条，按时间排会重复或漏行
 * （{@code V5__run.sql:379-381} 就是这么定的）。索引也都在 {@code (维度, seq desc)} 上，
 * 所以各个过滤条件任意组合都不需要新建索引（时间窗走的是 `ix_audit_event_time`，
 * 它正好是 {@code (at desc, seq desc)}）。
 *
 * <p>没有缓存、没有视图：这是"事后查一次"的路径，不是热路径。给它加缓存，第一个代价是
 * "刚发生的事查不到"——而那正是查它的理由。
 */
public class JdbcAuditQuery implements AuditQuery {

    private static final String COLUMNS = "seq, schema_version, event_id, source_sequence, kind,"
            + " action, status, error_code, redaction, execution_id, session_key, session_id, agent_id,"
            + " actor_type, actor, channel, tool_call_id, tool_name, args_digest, approval_id, at,"
            + " direction, message_outcome, reason_code, conversation_kind, delivery_kind,"
            + " failure_stage, duration_ms, result_count,"
            + " account_ref, conversation_ref, message_ref, target_ref";

    /**
     * 行映射。{@code eventType} 在这里算：它是 {@code kind} 与消息方向的函数，库里没有那一列
     * （票 07）。放在映射里而不是让每个读的人自己拼，是因为"进还是出"只该有一处判断。
     */
    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> {
        String kind = rs.getString("kind");
        MessageRow message = messageOf(rs);
        return new Row(
                rs.getLong("seq"),
                rs.getInt("schema_version"),
                rs.getString("event_id"),
                rs.getInt("source_sequence"),
                AuditQuery.eventTypeOf(kind, message == null ? null : message.direction()),
                kind,
                rs.getString("action"),
                rs.getString("status"),
                rs.getString("error_code"),
                rs.getString("redaction"),
                rs.getString("execution_id"),
                rs.getString("session_key"),
                rs.getString("session_id"),
                rs.getString("agent_id"),
                rs.getString("actor_type"),
                rs.getString("actor"),
                rs.getString("channel"),
                rs.getString("tool_call_id"),
                rs.getString("tool_name"),
                rs.getString("args_digest"),
                rs.getString("approval_id"),
                rs.getTimestamp("at").toInstant(),
                message);
    };

    /**
     * 消息那几列。不是 message 行时整组为 null——读的人一眼就知道这一行没有那种字段，
     * 而不是面对十二个空格去猜"是没记还是记了空"。
     */
    private static MessageRow messageOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        String direction = rs.getString("direction");
        if (direction == null) {
            return null;
        }
        return new MessageRow(direction, rs.getString("message_outcome"),
                rs.getString("reason_code"), rs.getString("conversation_kind"),
                rs.getString("delivery_kind"), rs.getString("failure_stage"),
                rs.getObject("duration_ms", Integer.class),
                rs.getObject("result_count", Integer.class),
                rs.getString("account_ref"), rs.getString("conversation_ref"),
                rs.getString("message_ref"), rs.getString("target_ref"));
    }

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 主连接池
     */
    public JdbcAuditQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page query(Filter filter) {
        Filter effective = filter == null ? Filter.page(null, Filter.DEFAULT_LIMIT) : filter;
        // 前缀不能挂在 StringJoiner 上：一个条件都没有时它的 toString() 仍是 emptyValue
        // （默认就是 prefix + suffix），拼出来是 `… audit_event where  order by`——语法错、500。
        // 空过滤恰恰是最常用的那一种（I 节第一条就是不带参数打开这一页）。
        StringJoiner where = new StringJoiner(" and ");
        List<Object> args = new ArrayList<>();
        eq(where, args, "execution_id", effective.executionId());
        eq(where, args, "session_key", effective.sessionKey());
        eq(where, args, "agent_id", effective.agentId());
        eq(where, args, "kind", effective.kind());
        eq(where, args, "direction", effective.direction());
        eq(where, args, "status", effective.status());
        eq(where, args, "channel", effective.channel());
        // 时间窗是闭区间，单位是 Unix 毫秒（与 OpenClaw 的 after/before 同义，
        // ledgers.md:25-26）。at 是 timestamptz，所以把毫秒换算过去再比，而不是在 Java 里算时刻——
        // 算在库里，seq 索引与时间条件才落在同一条语句里。
        if (effective.after() != null) {
            where.add("at >= to_timestamp(?::double precision / 1000)");
            args.add(effective.after());
        }
        if (effective.before() != null) {
            where.add("at <= to_timestamp(?::double precision / 1000)");
            args.add(effective.before());
        }
        if (effective.beforeSeq() != null) {
            where.add("seq < ?");
            args.add(effective.beforeSeq());
        }
        // 多取一条判"还有没有下一页"：比 count(*) 便宜，也不会在两页之间被新写入骗到。
        args.add(effective.limit() + 1);
        String whereClause = where.length() == 0 ? "" : " where " + where;
        List<Row> rows = jdbc.query(
                "select " + COLUMNS + " from claw4j_run.audit_event" + whereClause
                        + " order by seq desc limit ?",
                MAPPER, args.toArray());
        if (rows.size() <= effective.limit()) {
            return new Page(rows, null);
        }
        List<Row> page = rows.subList(0, effective.limit());
        return new Page(List.copyOf(page), page.getLast().seq());
    }

    private static void eq(StringJoiner where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.add(column + " = ?");
            args.add(value);
        }
    }
}
