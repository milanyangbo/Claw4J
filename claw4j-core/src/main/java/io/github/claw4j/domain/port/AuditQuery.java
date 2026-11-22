package io.github.claw4j.domain.port;

import java.time.Instant;
import java.util.List;

/**
 * 审计的只读查询（ADR-0037 票 03）。
 *
 * <p>端口放在 domain 而不是 agent：通道模块只依赖 core（ADR-0010），而「事后查得到」这件事
 * 必须能被 HTTP 入口读——与 {@link Approvals} 同一个安排：端口在 domain、实现在 agent、
 * 入口在通道。
 *
 * <p>形状照 OpenClaw 的 {@code AuditListParamsSchema}（{@code packages/gateway-protocol/
 * src/schema/audit.ts:63-73}）：按 {@code seq} 倒序、游标翻页、{@code limit} 1–500、六个等值过滤。
 * 只读：不提供导出、不提供"为什么放行"的解释（那要 decision receipts，见 ADR-0037 的缺口）。
 */
public interface AuditQuery {

    /**
     * 查一页。
     *
     * @param filter 过滤与游标
     * @return 这一页与下一页游标
     */
    Page query(Filter filter);

    /**
     * 过滤条件。全为 null 时是"什么都看"。
     *
     * @param runId 这一轮的编号
     * @param sessionKey 会话键
     * @param agentId 哪个 agent 干的
     * @param kind {@code agent_run} / {@code tool_action} / {@code message}
     * @param direction {@code inbound} / {@code outbound}；只有消息行有方向
     *     （照 OpenClaw 的 {@code audit.activity.list}，{@code docs/gateway/protocol/ledgers.md:120-131}）
     * @param status 结局
     * @param channel 通道名
     * @param beforeSeq 游标：只取比它小的（翻页用，来自上一页的 {@link Page#nextCursor()}）
     * @param limit 每页条数，1–500，越界按 {@code audit.ts:71} 截断
     */
    record Filter(String runId, String sessionKey, String agentId, String kind, String direction,
                  String status, String channel, Long beforeSeq, int limit) {

        /** 与 OpenClaw 的 {@code DEFAULT_AUDIT_LIST_LIMIT} 同一个数（{@code audit.ts:70}）。 */
        public static final int DEFAULT_LIMIT = 100;

        /** 与 {@code MAX_AUDIT_LIST_LIMIT} 同一个数（{@code audit.ts:71}）。 */
        public static final int MAX_LIMIT = 500;

        public Filter {
            limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        }

        /**
         * 只要游标与每页条数。
         *
         * @param beforeSeq 游标
         * @param limit 每页条数
         * @return 不带过滤条件的一页请求
         */
        public static Filter page(Long beforeSeq, int limit) {
            return new Filter(null, null, null, null, null, null, null, beforeSeq, limit);
        }
    }

    /**
     * 一行审计。字段与表一一对应，不做加工——加工放在读的人那边。
     *
     * @param seq 账本序号，翻页游标就是它
     * @param eventId 事件编号
     * @param kind {@code agent_run} / {@code tool_action}
     * @param action 四个动作之一
     * @param status 结局
     * @param errorCode 归一化错误码，成功时为 null
     * @param runId 这一轮的编号
     * @param sessionKey 会话键
     * @param agentId 哪个 agent 干的
     * @param actor 谁让这件事发生
     * @param channel 通道名
     * @param toolName 工具名，运行类事件为 null
     * @param argsDigest 参数指纹，运行类事件为 null
     * @param approvalId 放行这次调用的审批编号
     * @param at 发生时刻
     * @param message 消息专有的那一组；运行与工具事件为 null
     */
    record Row(long seq, String eventId, String kind, String action, String status,
               String errorCode, String runId, String sessionKey, String agentId,
               String actor, String channel, String toolName, String argsDigest,
               String approvalId, Instant at, MessageRow message) {
    }

    /**
     * 消息行专有的那些列（ADR-0037 票 04）。
     *
     * <p>单开一个 record 而不是往 {@link Row} 上加十二个字段：运行与工具行一个都用不上它们，
     * 而"永远为 null 的十二个字段"会让每一条读出来的行都多十二格没人看的东西。
     *
     * <p>四个 {@code *Ref} 是假名，不是原文——读接口拿到的就是假名，原文不在库里。
     *
     * @param direction inbound / outbound
     * @param outcome completed / skipped / sent / suppressed / failed / unknown
     * @param reasonCode 原因码
     * @param conversationKind direct / group / channel / unknown
     * @param deliveryKind text / media / other
     * @param failureStage queue / platform_send / unknown
     * @param durationMs 从到达（或开始投递）到收尾的毫秒数
     * @param resultCount 一条入站产出几条回复
     * @param actorType channel_sender / agent / system
     * @param accountRef 发送者账号的假名
     * @param conversationRef 会话地址的假名
     * @param messageRef 消息编号的假名
     * @param targetRef 投递目标的假名
     */
    record MessageRow(String direction, String outcome, String reasonCode, String conversationKind,
                      String deliveryKind, String failureStage, Integer durationMs,
                      Integer resultCount, String actorType, String accountRef,
                      String conversationRef, String messageRef, String targetRef) {
    }

    /**
     * 一页结果。
     *
     * @param rows 这一页，按 seq 倒序
     * @param nextCursor 下一页游标；没有更多时为 null
     */
    record Page(List<Row> rows, Long nextCursor) {
    }
}
