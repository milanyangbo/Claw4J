package io.github.claw4j.channel.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.claw4j.domain.port.AuditQuery;

/**
 * 一条审计事件的**报文形状**（ADR-0037 第三批）：四个变体的并集，空字段不出现在 JSON 里。
 *
 * <p>为什么要单独一层而不是直接序列化 {@link AuditQuery.Row}：
 *
 * <ul>
 *   <li>OpenClaw 的四个变体都是 {@code additionalProperties: false} 的闭对象
 *       （{@code audit-activity.ts:135-142}）。多带一个字段（我们的 {@code argsDigest} /
 *       {@code approvalId} / 嵌套的 {@code message}）在严格客户端那里就是解析失败；
 *       而"空字段写 null 也算多带一个字段"，所以必须整个省掉。</li>
 *   <li>名字与类型要对上：它的 {@code sequence} / {@code occurredAt}（Unix 毫秒整数）/
 *       {@code actor}（{@code {type,id}} 对象）/ {@code outcome}，与库里的 {@code seq} /
 *       {@code at}（timestamptz）/ {@code actor_type}+{@code actor} / {@code message_outcome}
 *       不是同一个形状。翻译只在这一处发生。</li>
 * </ul>
 *
 * <p>两类字段**故意不外带**：{@code args_digest} 与 {@code approval_id} 是我们的扩展列
 * （OpenClaw 用 execution decision 那一层回答"这次执行是哪次批准的"），留在库里供 SQL 追。
 * 把它们塞进报文，就等于让这个接口不再是它那份 schema 的实例。
 *
 * @param eventType 变体判别：{@code agent_run} / {@code tool_action} / {@code inbound_message} /
 *     {@code outbound_message}
 * @param schemaVersion 恒为 1
 * @param eventId 事件编号
 * @param sequence 账本序号（倒序翻页用）
 * @param sourceSequence 来源内序号：1 = started，2 = finished
 * @param occurredAt 发生时刻，Unix 毫秒
 * @param kind {@code agent_run} / {@code tool_action} / {@code message}
 * @param action 动作
 * @param status 结局
 * @param errorCode 归一化错误码，成功时没有这一格
 * @param actor 执行者
 * @param agentId 哪个 agent
 * @param sessionKey 会话键（不含段）
 * @param sessionId 那一段
 * @param runId 报文里的执行编号。**这一格沿线格式叫 {@code runId}**：它的 agent_run / tool_action
 *     变体里 {@code runId} 是必填的（{@code audit-activity.ts:112-120}），闭对象又不允许多一格，
 *     所以库里叫 {@code execution_id}（ADR-0038）、报文里仍叫 {@code runId}——翻译只在这一处
 * @param toolCallId 调用编号（已哈希）
 * @param toolName 工具名
 * @param channel 通道名；只有消息行
 * @param direction inbound / outbound；只有消息行
 * @param conversationKind direct / group / channel / unknown
 * @param outcome 消息的具体结果
 * @param reasonCode 原因码
 * @param deliveryKind text / media / other
 * @param failureStage queue / platform_send / unknown
 * @param durationMs 处理耗时
 * @param resultCount 一条入站产出几条回复
 * @param accountRef 发送者假名
 * @param conversationRef 会话地址假名
 * @param messageRef 消息编号假名
 * @param targetRef 投递目标假名
 * @param redaction 恒为 {@code metadata_only}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record AuditEventView(String eventType, int schemaVersion, String eventId, long sequence,
                      int sourceSequence, long occurredAt, String kind, String action,
                      String status, String errorCode, ActorView actor, String agentId,
                      String sessionKey, String sessionId, String runId, String toolCallId,
                      String toolName, String channel, String direction, String conversationKind,
                      String outcome, String reasonCode, String deliveryKind, String failureStage,
                      Integer durationMs, Integer resultCount, String accountRef,
                      String conversationRef, String messageRef, String targetRef, String redaction) {

    /**
     * {@code actor} 是对象，不是字符串（{@code audit-activity.ts:84-102}）：运行与工具行是
     * {@code {type: agent|system, id}}，入站消息是 {@code {type: channel_sender, id: hmac…}}。
     *
     * @param type agent / system / channel_sender
     * @param id agent 行是 agentId，通道来的人是假名
     */
    record ActorView(String type, String id) {
    }

    /**
     * 把一行库记录翻成报文。
     *
     * @param row 读出来的一行
     * @return 报文形状
     */
    static AuditEventView of(AuditQuery.Row row) {
        AuditQuery.MessageRow message = row.message();
        return new AuditEventView(
                row.eventType(),
                row.schemaVersion(),
                row.eventId(),
                row.seq(),
                row.sourceSequence(),
                row.at().toEpochMilli(),
                row.kind(),
                row.action(),
                row.status(),
                row.errorCode(),
                actorOf(row),
                row.agentId(),
                row.sessionKey(),
                row.sessionId(),
                row.executionId(),
                row.toolCallId(),
                row.toolName(),
                row.channel(),
                message == null ? null : message.direction(),
                message == null ? null : message.conversationKind(),
                message == null ? null : message.outcome(),
                message == null ? null : message.reasonCode(),
                message == null ? null : message.deliveryKind(),
                message == null ? null : message.failureStage(),
                message == null ? null : message.durationMs(),
                message == null ? null : message.resultCount(),
                message == null ? null : message.accountRef(),
                message == null ? null : message.conversationRef(),
                message == null ? null : message.messageRef(),
                message == null ? null : message.targetRef(),
                row.redaction());
    }

    /**
     * 归属两格。老行（这次对齐之前写的）没有 {@code actor_type}，按 OpenClaw 的同一条规则补：
     * 有 agentId 就是 {@code agent}，否则 {@code system}——补出来的形状与它读自己历史行时一样。
     *
     * @param row 读出来的一行
     * @return 报文里的 actor
     */
    private static ActorView actorOf(AuditQuery.Row row) {
        String agentId = row.agentId();
        boolean hasAgent = agentId != null && !agentId.isBlank();
        String type = row.actorType() == null || row.actorType().isBlank()
                ? (hasAgent ? "agent" : "system") : row.actorType();
        String id = row.actor() == null || row.actor().isBlank()
                ? (hasAgent ? agentId : "unknown") : row.actor();
        return new ActorView(type, id);
    }
}
