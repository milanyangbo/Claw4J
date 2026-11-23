package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.AuditQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 审计的只读入口（ADR-0037 票 03）：{@code GET /api/audit}。
 *
 * <p>形状照 OpenClaw 的 {@code audit.activity.list}（{@code packages/gateway-protocol/src/schema/
 * audit.ts:63-79} 与 {@code audit-activity.ts}）：按 {@code sequence} 倒序、{@code cursor} 翻页、
 * 时间窗 {@code after}/{@code before}（Unix 毫秒，闭区间）、{@code limit} 1–500、六个等值过滤，
 * 返回 {@code {events, nextCursor}}。
 *
 * <p>参数自相矛盾时回 400 而不是空页（{@code audit-activity.ts:30-53} 的
 * {@code findAuditActivityFilterConflict}）：消息行没有会话键，运行/工具行没有方向与通道。
 * 空页在客户端看来是"这段时间没有审计"，而真相是"这两个参数不该一起用"——两种结论，
 * 排查方向完全不同。
 *
 * <p>只读：没有导出、没有"为什么放行"的解释——那要 decision receipts（ADR-0037 的缺口）。
 * 不认字也不写：这一层只回答"库里有什么"，判定与鉴权在别处。
 *
 * <p>没有装配查询时回 404 而不是 500：那是能力不在场（与 {@code /api/approvals} 没装审批时同一个判断），
 * 客户端据此知道该去找部署，而不是以为自己参数写错了。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final ObjectProvider<AuditQuery> queries;

    public AuditController(ObjectProvider<AuditQuery> queries) {
        this.queries = queries;
    }

    /**
     * 查一页审计。
     *
     * @param runId 这一轮的编号
     * @param sessionKey 会话键
     * @param agentId 哪个 agent 干的
     * @param kind {@code agent_run} / {@code tool_action} / {@code message}
     * @param direction {@code inbound} / {@code outbound}（只有消息行有）
     * @param status 结局
     * @param channel 通道名（只有消息行有）
     * @param after 起始时刻，Unix 毫秒，含
     * @param before 截止时刻，Unix 毫秒，含
     * @param cursor 游标：上一页的 {@code nextCursor}（不透明字符串，与 OpenClaw 同形）
     * @param limit 每页条数，1–500（越界在 {@link AuditQuery.Filter} 里钳位）
     * @return 200 加一页；参数矛盾或游标不认识时 400；没有装配查询时 404
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionKey", required = false) String sessionKey,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "after", required = false) Long after,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "" + AuditQuery.Filter.DEFAULT_LIMIT) int limit) {
        AuditQuery query = queries.getIfAvailable();
        if (query == null) {
            return ResponseEntity.notFound().build();
        }
        Long beforeSeq = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                beforeSeq = Long.valueOf(cursor.trim());
            }
            catch (NumberFormatException notACursor) {
                return ResponseEntity.badRequest()
                        .body(new Problem("cursor 必须是上一页返回的 nextCursor：" + cursor));
            }
        }
        AuditQuery.Filter filter = new AuditQuery.Filter(runId, sessionKey, agentId, kind, direction,
                status, channel, after, before, beforeSeq, limit);
        Optional<String> conflict = filter.conflict();
        if (conflict.isPresent()) {
            return ResponseEntity.badRequest().body(new Problem(conflict.get()));
        }
        AuditQuery.Page page = query.query(filter);
        List<AuditEventView> events = page.rows().stream().map(AuditEventView::of).toList();
        return ResponseEntity.ok(new AuditView(events,
                page.nextCursor() == null ? null : String.valueOf(page.nextCursor())));
    }

    /**
     * 一页审计。
     *
     * @param events 这一页，按 {@code sequence} 倒序
     * @param nextCursor 下一页游标（字符串，与请求里的 {@code cursor} 同一个值）；没有更多时不存在
     */
    public record AuditView(List<AuditEventView> events, String nextCursor) {
    }

    /**
     * 参数错误。
     *
     * @param error 人话说明哪两个参数冲突、哪个值不认识
     */
    public record Problem(String error) {
    }
}
