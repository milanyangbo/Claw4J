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

/**
 * 审计的只读入口（ADR-0037 票 03）：{@code GET /api/audit}。
 *
 * <p>形状照 OpenClaw 的 {@code audit.list}（{@code packages/gateway-protocol/src/schema/audit.ts:63-79}）：
 * 按 {@code seq} 倒序、{@code cursor} 翻页、{@code limit} 1–500、六个等值过滤，
 * 返回 {@code {events, nextCursor}}。
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
     * @param channel 通道名
     * @param cursor 游标：上一页的 {@code nextCursor}
     * @param limit 每页条数，1–500（越界在 {@link AuditQuery.Filter} 里钳位）
     * @return 200 加一页；没有装配查询时 404
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuditView> list(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionKey", required = false) String sessionKey,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "" + AuditQuery.Filter.DEFAULT_LIMIT) int limit) {
        AuditQuery query = queries.getIfAvailable();
        if (query == null) {
            return ResponseEntity.notFound().build();
        }
        AuditQuery.Page page = query.query(
                new AuditQuery.Filter(runId, sessionKey, agentId, kind, direction, status, channel,
                        cursor, limit));
        return ResponseEntity.ok(new AuditView(page.rows(), page.nextCursor()));
    }

    /**
     * 一页审计。
     *
     * @param events 这一页，按 {@code seq} 倒序
     * @param nextCursor 下一页游标；没有更多时为 null
     */
    public record AuditView(List<AuditQuery.Row> events, Long nextCursor) {
    }
}
