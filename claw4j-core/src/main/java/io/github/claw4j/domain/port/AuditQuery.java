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
     * <p>有两组参数互斥（OpenClaw 的 {@code findAuditActivityFilterConflict}，
     * {@code audit-activity.ts:30-53}）：消息行没有会话键，运行/工具行没有方向与通道。组合错了
     * 它**报错**而不是返回空页——空页在客户端看来是"这段时间没有审计"，而真相是"这两个参数不该一起用"。
     * 判据在 {@link #conflict()}。
     *
     * @param executionId 这一轮的编号
     * @param sessionKey 会话键
     * @param agentId 哪个 agent 干的
     * @param kind {@code agent_run} / {@code tool_action} / {@code message}
     * @param direction {@code inbound} / {@code outbound}；只有消息行有方向
     *     （照 OpenClaw 的 {@code audit.activity.list}，{@code docs/gateway/protocol/ledgers.md:120-131}）
     * @param status 结局
     * @param channel 通道名；只有消息行有（OpenClaw 按 kind 强制 null，{@code audit-event-store.ts:103}）
     * @param after 起始时刻（Unix 毫秒，含）；与 OpenClaw 的 {@code after} 同义（{@code ledgers.md:25-26}）
     * @param before 截止时刻（Unix 毫秒，含）
     * @param beforeSeq 游标：只取比它小的（翻页用，来自上一页的 {@link Page#nextCursor()}）
     * @param limit 每页条数，1–500，越界按 {@code audit.ts:71} 截断
     */
    record Filter(String executionId, String sessionKey, String agentId, String kind, String direction,
                  String status, String channel, Long after, Long before, Long beforeSeq,
                  int limit) {

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
            return new Filter(null, null, null, null, null, null, null, null, null, beforeSeq, limit);
        }

        /**
         * 这组参数是不是自相矛盾。
         *
         * <p>三条判据与 OpenClaw 的 {@code findAuditActivityFilterConflict} 逐条相同：消息行不会有
         * 会话键，运行/工具行不会有方向与通道。它把这三条当参数错误返回给调用方，我们照做——
         * 静默返回空页是最坏的一种"对齐"：字段对了，行为还是两套。
         *
         * @return 非空时是要回给调用方的那句话；空表示参数可用
         */
        public java.util.Optional<String> conflict() {
            boolean messageOnly = direction != null || channel != null;
            if ("message".equals(kind) && sessionKey != null) {
                return java.util.Optional.of("kind=message 与 sessionKey 不能同时用："
                        + "消息行没有会话键（只有 agent_run / tool_action 有）");
            }
            if (kind != null && !"message".equals(kind) && messageOnly) {
                return java.util.Optional.of("kind=" + kind + " 不能带 direction 或 channel："
                        + "那两格只属于消息行");
            }
            if (kind == null && sessionKey != null && messageOnly) {
                return java.util.Optional.of("sessionKey 与 direction/channel 不能同时用："
                        + "前者要运行或工具行，后者要消息行，一次查不出两种");
            }
            return java.util.Optional.empty();
        }
    }

    /**
     * 这一行属于 OpenClaw 的哪一个读侧变体（ADR-0037 票 07）。
     *
     * <p>四个变体各有自己的 schema，靠 {@code eventType} 判别：{@code agent_run} / {@code tool_action} /
     * {@code inbound_message} / {@code outbound_message}
     * （{@code packages/gateway-protocol/src/schema/audit-activity.ts:183,215,249,323}）。
     * {@code kind} 与消息的 {@code direction} 照样留在响应里——它们负责过滤与展示，判别交给这个字段
     * （{@code docs/gateway/protocol/ledgers.md:31-33}）。
     *
     * <p>它是<b>算出来的</b>，不是一列：库里只有 {@code kind} 与 {@code direction}，OpenClaw 的持久投影
     * 也一样（{@code audit-event-store.ts:94-114}），{@code eventType} 是读的时候按这两格拼的。
     * 所以推导只此一处，任何"再写一份"的分支都会漂。
     *
     * @param kind {@code agent_run} / {@code tool_action} / {@code message}，不可为 null
     * @param direction 消息方向 {@code inbound} / {@code outbound}；非消息行传 null
     * @return 四个判别值之一；{@code kind} 不认识时返回 null（库里被 CHECK 约束挡着，走不到）
     */
    public static String eventTypeOf(String kind, String direction) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "agent_run" -> "agent_run";
            case "tool_action" -> "tool_action";
            case "message" -> "inbound".equals(direction) ? "inbound_message"
                    : "outbound".equals(direction) ? "outbound_message" : null;
            default -> null;
        };
    }

    /**
     * 一行审计。字段与表一一对应，不做加工——加工放在读的人那边。
     *
     * <p>唯一的例外是 {@code eventType}：它是 {@link #eventTypeOf} 从 {@code kind} 与消息方向推出来的
     * 判别字段，表里没有这一列（票 07）。它留在这一层而不是交给每个读的人自己拼，是因为"进还是出"
     * 与"是运行还是工具"这两格一旦各拼一次，两边迟早对不上，而那种不一致不会报错，只会让客户端
     * 少认出一个变体。
     *
     * @param seq 账本序号，翻页游标就是它
     * @param schemaVersion 记录格式版本，恒为 1（与 OpenClaw 的 {@code schemaVersion} 同义）
     * @param eventId 事件编号
     * @param sourceSequence 来源内序号：1 = started，2 = finished
     * @param eventType 读侧变体判别，由 {@code kind} + 消息方向推出（票 07）
     * @param kind {@code agent_run} / {@code tool_action} / {@code message}
     * @param action 四个动作之一
     * @param status 结局
     * @param errorCode 归一化错误码，成功时为 null
     * @param redaction 恒为 {@code metadata_only}：让读的人一眼看见"这行没有内容"
     * @param executionId 这一轮的编号
     * @param sessionKey 会话键（不含段）
     * @param sessionId 那一段（含段的 id）；与 OpenClaw 的同名两列一致
     * @param agentId 哪个 agent 干的
     * @param actorType 执行者的种类：{@code agent} / {@code system} / {@code channel_sender}
     * @param actor 执行者的 id；通道来的人是假名（与 {@code accountRef} 同值）
     * @param channel 通道名；只有消息行有
     * @param toolCallId 这次调用的编号（票 06）；只有工具行有值，取不到时 null。与 OpenClaw 的
     *     {@code toolCallId} 同名同义（{@code audit-activity.ts:219}）
     * @param toolName 工具名，运行类事件为 null
     * @param argsDigest 参数指纹，运行类事件为 null。**扩展字段**：OpenClaw 没有这一列，不进读接口
     * @param approvalId 放行这次调用的审批编号。同样是扩展字段，不进读接口
     * @param at 发生时刻
     * @param message 消息专有的那一组；运行与工具事件为 null
     */
    record Row(long seq, int schemaVersion, String eventId, int sourceSequence, String eventType,
               String kind, String action, String status, String errorCode, String redaction,
               String executionId, String sessionKey, String sessionId, String agentId,
               String actorType, String actor, String channel,
               String toolCallId, String toolName, String argsDigest, String approvalId,
               Instant at, MessageRow message) {

        public Row {
            // 判别字段是推出来的，就不允许它和 kind + direction 说两套话：那种行看起来正常，
            // 客户端却会按错的变体去解析。写入侧有 not-null 与闭集约束，这里再钉一次读侧那份。
            String direction = message == null ? null : message.direction();
            String derived = eventTypeOf(kind, direction);
            if (!java.util.Objects.equals(eventType, derived)) {
                throw new IllegalArgumentException("eventType 由 kind + 方向推出，不是一列："
                        + kind + " / " + direction + " 应当是 " + derived + "，实际是 " + eventType);
            }
        }
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
     * @param accountRef 发送者账号的假名
     * @param conversationRef 会话地址的假名
     * @param messageRef 消息编号的假名
     * @param targetRef 投递目标的假名
     */
    record MessageRow(String direction, String outcome, String reasonCode, String conversationKind,
                      String deliveryKind, String failureStage, Integer durationMs,
                      Integer resultCount, String accountRef,
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
