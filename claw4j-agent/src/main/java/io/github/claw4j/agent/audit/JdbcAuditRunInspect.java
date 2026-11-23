package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.port.AuditRunInspect;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 run 的全景（ADR-0037 票 14）：把三张表里的回执合成一页。
 *
 * <p>三类生产者，各自一张表：
 *
 * <ul>
 *   <li>**审批**（{@code approval_request}）：人点了什么、谁点的时刻。它是唯一一条"真改了结果"的闸，
 *       所以覆盖度是 {@code enforced}——没有这条批准，那次调用根本不会跑。</li>
 *   <li>**投递**（{@code audit_event} 里 {@code kind=message, direction=outbound} 的终态行）：送出去了
 *       还是被抑制。它只留记录、不改结果，覆盖度 {@code attribution-only}。</li>
 *   <li>**通用事实**（{@code execution_decision_fact}）：某次执行依据了哪条批准。同样只留记录；
 *       它在被取走时删除，所以查到的都是"还没被消费的"。它的 {@code producer} 不在它的闭集里，
 *       报文里落 {@code unverified}——拿不到"谁产的"就说不知道，不编一个。</li>
 * </ul>
 *
 * <p>顺序照它的合并约定：先按时刻倒序，同一时刻按 admission → approval → message → generic → cron
 * 排（{@code docs/cli/audit.md:359-362}）。我们只有中间三类有生产者，位置先留着——将来补上
 * admission 与 cron 时不用改这个比较器。
 *
 * <p>游标是上一页最后一条的 {@code selectorId}：它是不透明的、由属主行决定，所以别人的一行新增
 * 不会让下一页错位。认不出来时回空（调用方据此 400），与它"游标的属主行过期就拒"同一个态度
 * （{@code docs/cli/audit.md:368-370}）。
 */
public class JdbcAuditRunInspect implements AuditRunInspect {

    /**
     * 这一跑的审批回执。
     *
     * <p>两处都要查，因为**审批行挂的是"发起那一轮"，不是"执行那一轮"**：被拦下来的那次请求写在
     * run A，人批了之后执行发生在 run B（{@code approval_request.run_id} 是发起方的编号）。
     * 只按 {@code run_id} 查的话，run B 的全景会显示"这一跑没有任何审批"——而它明明是被批过才跑的。
     * 第二段就是从执行行上的 {@code approval_id} 接回去。
     */
    private static final String APPROVALS = """
            select distinct a.id, a.tool_name, a.status, a.requested_at,
                   coalesce(a.resolved_at, a.requested_at) as at
              from claw4j_run.approval_request a
             where a.run_id = ?
                or a.id in (select e.approval_id
                              from claw4j_run.audit_event e
                             where e.run_id = ? and e.approval_id is not null)
            """;

    private static final String OUTBOUND = """
            select seq, at, message_outcome, reason_code
              from claw4j_run.audit_event
             where run_id = ? and kind = 'message' and direction = 'outbound'
            """;

    private static final String FACTS = """
            select tool_name, created_at
              from claw4j_run.execution_decision_fact
             where run_id = ?
            """;

    private static final String RUN_KNOWN = """
            select exists (select 1 from claw4j_run.audit_event where run_id = ?)
                or exists (select 1 from claw4j_run.approval_request where run_id = ?)
            """;

    /** 同一时刻的先后：它的合并顺序。数字小的先。 */
    private static final List<String> FAMILY_ORDER = List.of("admission", "approval", "message",
            "generic", "cron");

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 主连接池
     */
    public JdbcAuditRunInspect(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Report> inspect(String runId, int decisionLimit, String decisionCursor) {
        if (runId == null || runId.isBlank()) {
            return Optional.of(new Report(false, List.of(), null));
        }
        List<Decision> all = new ArrayList<>();
        all.addAll(approvals(runId));
        all.addAll(outboundMessages(runId));
        all.addAll(facts(runId));
        all.sort(ORDER);
        return page(runId, all, decisionLimit, decisionCursor);
    }

    /**
     * 按游标切一页。
     *
     * @param runId 这一轮
     * @param all 合并后的全部回执
     * @param decisionLimit 每页条数
     * @param decisionCursor 上一页最后一条的 selectorId
     * @return 一页；游标找不到时为空
     */
    private Optional<Report> page(String runId, List<Decision> all, int decisionLimit,
                                  String decisionCursor) {
        int from = 0;
        if (decisionCursor != null && !decisionCursor.isBlank()) {
            int index = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).selectorId().equals(decisionCursor)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                // 它的语义：游标的属主行过期了就拒。静默从头查会让客户端把同一页看成新的一页。
                return Optional.empty();
            }
            from = index + 1;
        }
        int limit = Math.min(Math.max(decisionLimit, 1), MAX_LIMIT);
        int to = Math.min(from + limit, all.size());
        String next = to < all.size() && to > from ? all.get(to - 1).selectorId() : null;
        return Optional.of(new Report(runKnown(runId), List.copyOf(all.subList(from, to)), next));
    }

    private boolean runKnown(String runId) {
        Boolean known = jdbc.queryForObject(RUN_KNOWN, Boolean.class, runId, runId);
        return Boolean.TRUE.equals(known);
    }

    /**
     * 审批回执。
     *
     * <p>结果映射：{@code allowed} → {@code allowed}，{@code denied} → {@code denied}，其余三态
     * （{@code pending} / {@code expired} / {@code cancelled}）→ {@code unknown}——它们记的是
     * "没人做决定"，与"有人拒绝了"是两件事（V5 的注释里就是这个区分）。
     */
    private List<Decision> approvals(String runId) {
        return jdbc.query(APPROVALS, (rs, rowNum) -> approvalDecision(rs), runId, runId);
    }

    private static Decision approvalDecision(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String status = rs.getString("status");
        String outcome = switch (status == null ? "" : status) {
            case "allowed" -> "allowed";
            case "denied" -> "denied";
            default -> "unknown";
        };
        return new Decision("approval-decision:" + id, instant(rs, "at"), "approval",
                rs.getString("tool_name"), outcome, status, "enforced", "operator-approval");
    }

    private List<Decision> outboundMessages(String runId) {
        return jdbc.query(OUTBOUND, (rs, rowNum) -> outboundDecision(rs), runId);
    }

    private static Decision outboundDecision(ResultSet rs) throws SQLException {
        String outcome = rs.getString("message_outcome");
        String decision = switch (outcome == null ? "" : outcome) {
            case "sent" -> "allowed";
            case "suppressed" -> "denied";
            default -> "unknown";
        };
        String reason = rs.getString("reason_code");
        return new Decision("message-decision:" + rs.getLong("seq"), instant(rs, "at"), "message",
                "delivery", decision, reason == null ? outcome : reason, "attribution-only",
                "message-delivery");
    }

    private List<Decision> facts(String runId) {
        return jdbc.query(FACTS, (rs, rowNum) -> new Decision(
                "generic-decision:" + rs.getString("tool_name"), instant(rs, "created_at"),
                "generic", rs.getString("tool_name"), "allowed", "approval_consumed",
                "attribution-only", null), runId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    /**
     * 时间倒序，同一时刻按它的合并顺序，再按 selectorId 倒序——最后这一档是为了让顺序**完全确定**：
     * 两条同一毫秒的回执若顺序不定，翻页就会漏一条或重一条。
     */
    private static final Comparator<Decision> ORDER = Comparator
            .comparing(Decision::occurredAt, Comparator.reverseOrder())
            .thenComparing(decision -> {
                int index = FAMILY_ORDER.indexOf(decision.family());
                return index < 0 ? FAMILY_ORDER.size() : index;
            })
            .thenComparing(Decision::selectorId, Comparator.reverseOrder());
}
