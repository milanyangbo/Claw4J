package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.audit.MessageDirection;
import io.github.claw4j.domain.audit.MessageOutcome;
import io.github.claw4j.domain.principal.Principal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 一条审计事件（ADR-0027 决定二）。
 *
 * <p>它是元数据，不是内容：这里没有提示词、没有消息体、没有工具参数、没有工具结果、没有原始错误。
 * 这不是「暂时没加」，是这张表存在的条件——审计要回答「谁在什么时候让谁做了什么、成没成」，
 * 答那个问题不需要内容，而带上内容就等于给业务数据做了第二份副本。
 *
 * <p>拆成 {@link Scope} 与事件本身：一次运行里会有很多条事件，它们共享同一份「谁、在哪段会话、从哪个
 * 通道来」。合成一个扁平 record 之后，每造一条事件都要把七个相同字段再抄一遍，而抄错一格不会有任何
 * 编译错误——它会变成一行看起来正常的脏数据。
 *
 * @param scope 这一轮的身份与位置
 * @param action 四类之一
 * @param status 闭合词表里的结局
 * @param errorCode 归一化错误码（{@link AuditErrorCode}），不是异常消息原文
 * @param toolCallId 这次调用在模型侧的编号，**落库形状是 {@code sha256:<64 hex>}**（票 06）：
 *     原值只在 {@code ToolContext} 里流转，进账本前哈希，照 OpenClaw 的
 *     {@code auditToolCallId}（provider 的字节不进我们的档案）。只有工具类事件有值；
 *     取不到时为空。它标识「这一次」调用，而 {@code argsDigest} 标识「这组参数」
 * @param toolName 工具名；运行类事件为空
 * @param argsDigest 参数指纹；运行类事件为空
 * @param approvalId 放行这次调用的审批编号；没有审批时为空
 * @param sourceId 来源键：同一件事的多次投递共用它（{@code exec:<executionId>} 或
 *     {@code tool:<executionId>:<工具>:<指纹>}）。与 {@code sourceSequence} 组成唯一键，
 *     重复投递靠 {@code on conflict do nothing} 只留第一行（OpenClaw 同名同义，
 *     {@code audit-event-store.ts:81-116}）
 * @param sourceSequence 来源内序号：1 = started，2 = finished
 * @param message 消息专有的那一组（方向、结果、假名引用）；运行与工具事件为空
 * @param occurredAt 这件事**发生**的时刻，由工厂在构造时取（票 12）：入站消息是"到达那一刻"
 *     （{@code now - durationMs}，那个字段量的就是处理耗时），其余是构造那一刻。它与
 *     OpenClaw 的 {@code occurred_at} 同一个语义——绑的是事件自己的时钟，
 *     {@code audit-event-store.ts:89} 直接拿 {@code input.occurredAt} 落库。写成"落库时刻"的话，
 *     一条处理了八秒的消息会显示成"八秒之后才发生"，而 30 天保留期也按落库时刻算
 */
public record AuditEvent(Scope scope, AuditAction action, AuditStatus status,
                         AuditErrorCode errorCode, String toolCallId, String toolName,
                         String argsDigest, String approvalId, String sourceId,
                         int sourceSequence, Message message, Instant occurredAt) {

    /**
     * 这一轮是谁、在哪段会话、从哪个通道来。
     *
     * <p>只有 {@code executionId} 是必需的：没有它这一行落不下去（{@link AuditLedger} 直接跳过），因为它
     * 不属于任何一次运行——按 {@code executionId} 查永远查不到，留着只会让计数虚高。它的事件 schema
     * 里这一格同样是必填（{@code packages/gateway-protocol/src/schema/audit.ts:56}）。
     *
     * <p>其余四个字段可空：审计不能因为取不到身份就不写——「有个工具跑了」本身就是事实，只是那一格
     * 没人。为一行缺字段的审计去拒绝整个工具调用，是把记账当成了前置条件。
     *
     * @param executionId 这一轮的编号
     * @param sessionKey 会话键（不含段）
     * @param sessionId 那一段（含段的 id）；与 OpenClaw 的同名两列一致（{@code audit-event-store.ts:97-98}）
     * @param agentId 哪个 agent 干的
     * @param requestedBy 触发这一轮的人。**扩展字段**：OpenClaw 的事件里没有它（人放在 execution
     *     decision 那一层），我们放在库里供 SQL 追；读接口不返回
     * @param channel 通道名；只有消息行会落库（{@code audit-event-store.ts:103}）
     */
    public record Scope(String executionId, String sessionKey, String sessionId, String agentId,
                        String requestedBy, String channel) {

        /** 没接上 agent 归属时的写法：写 {@code unknown} 而不是 null——null 让「谁干的」不可查。 */
        public static final String UNKNOWN_AGENT = "unknown";

        /**
         * 从身份与位置组一份（没有段号）。{@code principal} 为空时只留位置。
         *
         * @param executionId 这一轮的编号
         * @param sessionKey 会话键
         * @param principal 身份，可为 null
         * @param channel 通道名，可为 null
         * @return 事件作用域
         */
        public static Scope of(String executionId, String sessionKey, Principal principal, String channel) {
            return of(executionId, sessionKey, null, UNKNOWN_AGENT, principal, channel);
        }

        /**
         * 带 agent 归属的那一份。
         *
         * <p>{@code agentId} 为空白时落成 {@code unknown}：与 OpenClaw 的 {@code agentId} 必填
         * （{@code audit.ts:53}）同一个要求，取不到也要留一个可数的值，而不是一格没人。
         *
         * @param executionId 这一轮的编号
         * @param sessionKey 会话键
         * @param agentId 哪个 agent 干的
         * @param principal 身份，可为 null
         * @param channel 通道名，可为 null
         * @return 事件作用域
         */
        public static Scope of(String executionId, String sessionKey, String agentId, Principal principal,
                               String channel) {
            return of(executionId, sessionKey, null, agentId, principal, channel);
        }

        /**
         * 完整的一份：两把会话键都在。
         *
         * @param executionId 这一轮的编号
         * @param sessionKey 会话键（不含段）
         * @param sessionId 那一段（含段的 id）
         * @param agentId 哪个 agent 干的
         * @param principal 身份，可为 null（消息行没有"请求的人"这一说，它是通道来的人）
         * @param channel 通道名，可为 null
         * @return 事件作用域
         */
        public static Scope of(String executionId, String sessionKey, String sessionId, String agentId,
                               Principal principal, String channel) {
            return new Scope(executionId, sessionKey, sessionId,
                    agentId == null || agentId.isBlank() ? UNKNOWN_AGENT : agentId,
                    principal == null ? null : principal.userId(), channel);
        }

        /**
         * 什么都没有：仍然写，「发生过」比「字段齐全」重要。
         *
         * @return 全空的作用域
         */
        public static Scope empty() {
            return new Scope(null, null, null, null, null, null);
        }
    }

    /**
     * 一轮开始。
     *
     * @param scope 这一轮的身份与位置
     * @return 开始事件
     */
    public static AuditEvent runStarted(Scope scope) {
        return new AuditEvent(scope, AuditAction.AGENT_RUN_STARTED, AuditStatus.STARTED,
                null, null, null, null, null, executionSource(scope), 1, null, Instant.now());
    }

    /**
     * 一轮结束。
     *
     * @param scope 这一轮的身份与位置
     * @param status 结局，只能是 succeeded / failed / cancelled / timed_out / unknown
     * @param errorCode 归一化错误码，成功时为 null
     * @return 结束事件
     */
    public static AuditEvent runFinished(Scope scope, AuditStatus status, AuditErrorCode errorCode) {
        return new AuditEvent(scope, AuditAction.AGENT_RUN_FINISHED, status,
                errorCode, null, null, null, null, executionSource(scope), 2, null, Instant.now());
    }

    /**
     * 一个工具开始执行。被策略挡住的调用没有这一条——它根本没开始。
     *
     * @param scope 这一轮的身份与位置
     * @param toolCallId 这次调用的编号，可为 null（没开观测 / 非标准 provider）
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @return 开始事件
     */
    public static AuditEvent toolStarted(Scope scope, String toolCallId, String toolName,
                                         String argsDigest) {
        return new AuditEvent(scope, AuditAction.TOOL_ACTION_STARTED, AuditStatus.STARTED,
                null, hashedToolCallId(toolCallId), toolName, argsDigest, null,
                toolSource(scope, toolName, argsDigest, toolCallId), 1, null, Instant.now());
    }

    /**
     * 一个工具结束。成功、失败、被拒、超时都走这一条，用 {@code status} 分开。
     *
     * @param scope 这一轮的身份与位置
     * @param toolCallId 这次调用的编号，可为 null
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @param status 结局
     * @param errorCode 归一化错误码，成功时为 null
     * @param approvalId 放行这次调用的审批编号，没有审批时为 null
     * @return 结束事件
     */
    public static AuditEvent toolFinished(Scope scope, String toolCallId, String toolName,
                                          String argsDigest, AuditStatus status,
                                          AuditErrorCode errorCode, String approvalId) {
        return new AuditEvent(scope, AuditAction.TOOL_ACTION_FINISHED, status,
                errorCode, hashedToolCallId(toolCallId), toolName, argsDigest, approvalId,
                toolSource(scope, toolName, argsDigest, toolCallId), 2, null, Instant.now());
    }

    /**
     * 运行类事件的来源键。没有 executionId 时给一个空归属的键——账本本来就会跳过这种行
     * （{@code AuditLedger} 见 executionId 为空即计 skipped），键长什么样都不影响。
     */
    private static String executionSource(Scope scope) {
        String executionId = scope == null ? null : scope.executionId();
        return "exec:" + (executionId == null ? "" : executionId);
    }

    /**
     * 工具类事件的来源键：{@code tool:<executionId>:<调用编号的哈希>}。
     *
     * <p>形状照 OpenClaw 的 {@code legacyAuditSourceId}（{@code src/audit/agent-event-audit.ts}）：
     * 它是 {@code <executionId>:<sourceSequence>:<occurredAt>:<action>}——**不含工具名、不含参数**，
     * 唯一的成分是"哪一轮 + 流里的第几个事件 + 什么时候 + 什么动作"。两次不同的调用之所以分得开，
     * 靠的是事件流的序号与生产时刻。
     *
     * <p>我们没有那条事件流：审计是在工具回调里直接写的，没有稳定 seq，{@code occurredAt} 是
     * 写库那一刻（重投时不同，带上它反而会破坏去重）。所以把它的 {@code sourceSequence} 那一段
     * 换成**这次调用的编号**——作用相同：同一次调用重投是同一个值，两次不同调用必然不同。
     *
     * <p>取不到编号时退回 {@code <工具>:<指纹>}：那时只能按"一类事"近似，代价是同参数两次合并成
     * 一行（票 02 的缺口，仍然记着）。
     */
    private static String toolSource(Scope scope, String toolName, String argsDigest,
                                     String toolCallId) {
        String executionId = scope == null ? null : scope.executionId();
        String call = hashedToolCallId(toolCallId);
        return "tool:" + (executionId == null ? "" : executionId) + ':'
                + (call == null ? toolName + ':' + argsDigest : call);
    }

    /**
     * 调用编号进库前的形状：{@code sha256:<hex>}。
     *
     * <p>照 OpenClaw 的 {@code auditToolCallId}（{@code agent-event-audit.ts}），它的原话是
     * "call ids remain useful for correlation, but their provider-owned bytes are not operator
     * metadata and must never enter the ledger verbatim"——编号是 provider 的字节，能用来对账，
     * 但不是我们的档案，原样落库等于把第三方内部标识当成了自己的数据。
     *
     * <p>于是同一个编号在两处形状不同：{@code ToolContext} 里是原值（工具与将来的审批要用它去对），
     * 账本里是哈希。这不是两套语义，是同一件事在两个边界上的两种表示。
     *
     * @param toolCallId 调用编号，可为 null
     * @return {@code sha256:<64 hex>}；没有编号时返回 null
     */
    private static String hashedToolCallId(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toolCallId.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException impossible) {
            // 每个 JRE 都必须有 SHA-256；没有的话整个审批链都不该继续。
            throw new IllegalStateException("JRE 不支持 SHA-256", impossible);
        }
    }

    /**
     * 消息专有的那一组（ADR-0037 票 04）。
     *
     * <p>为什么单开一个 record 而不是往主 record 上加十三个字段：运行与工具事件一个都用不上它们，
     * 而"永远为空的十三个字段"会让每一条 {@code runStarted} 的构造都多十三个 {@code null}——
     * 抄错一格不会有编译错误，只会多一行看起来正常、其实带了别人方向的数据。
     *
     * <p>四个 {@code *Ref} 是 HMAC 假名，不是原文（{@link AuditPseudonyms}）：审计要能回答"是不是同一个人
     * 反复没收到"，而回答那个问题不需要知道他是谁。形状照 OpenClaw 的
     * {@code hmac-sha256:v1:<keyId>:<digest>}（{@code docs/gateway/protocol/ledgers.md:41-48}）。
     *
     * @param direction 进来还是出去
     * @param outcome 具体结果（completed / skipped / sent / suppressed / failed / unknown）
     * @param reasonCode 为什么是这个结果，闭集见 {@link MessageReason}
     * @param conversationKind 私聊 / 群 / 频道 / 不知道
     * @param deliveryKind 文本 / 媒体 / 其它
     * @param failureStage 失败发生在排队还是在平台发送
     * @param durationMs 从到达（或开始投递）到收尾的毫秒数
     * @param resultCount 一条入站产出几条回复；出站为空
     * @param actorType 谁在说这句话（channel_sender / agent / system）
     * @param accountRef 发送者账号的假名
     * @param conversationRef 会话地址的假名
     * @param messageRef 这条消息编号的假名
     * @param targetRef 投递目标的假名
     */
    public record Message(MessageDirection direction, MessageOutcome outcome, String reasonCode,
                          String conversationKind, String deliveryKind, String failureStage,
                          Integer durationMs, Integer resultCount, String actorType,
                          String accountRef, String conversationRef, String messageRef,
                          String targetRef) {
    }

    /**
     * 一条入站消息处理完了。
     *
     * <p>来源键用消息自己的编号（{@code message_ref} 的原文）：平台重投同一条消息时，这个键相同，
     * 唯一键于是把第二次投递挡成重复——那正是"平台重推三次，我们只记一次"的落点。
     *
     * @param scope 这一轮的身份与位置（可能没有 executionId：入站可能在开跑之前就结束了）
     * @param status 通用结局：succeeded / blocked / failed
     * @param errorCode 失败时的归一化错误码
     * @param message 消息专有的一组
     * @param sourceId 这条消息的来源键
     * @return 入站事件
     */
    public static AuditEvent inboundMessage(Scope scope, AuditStatus status, AuditErrorCode errorCode,
                                            Message message, String sourceId) {
        // 到达时刻 = 处理完那一刻往前退它自己量到的耗时：这个字段本来就是"从进来到出去"的毫秒数。
        // 写成落库时刻的话，一条处理了八秒的消息在账本上会晚八秒，而保留期也按那个时刻算。
        return new AuditEvent(scope, AuditAction.MESSAGE_INBOUND_PROCESSED, status, errorCode,
                null, null, null, null, sourceId, 1, message, arrivalOf(message));
    }

    /**
     * 入站那条消息的到达时刻。
     *
     * @param message 消息那一组
     * @return {@code now - durationMs}
     */
    private static Instant arrivalOf(Message message) {
        Integer duration = message == null ? null : message.durationMs();
        return Instant.now().minusMillis(duration == null || duration < 0 ? 0 : duration);
    }

    /**
     * 一条出站消息投递结束。
     *
     * @param scope 这一轮的身份与位置
     * @param status 通用结局：succeeded / blocked / failed / unknown
     * @param errorCode 失败时的归一化错误码
     * @param message 消息专有的一组
     * @param sourceId 这条投递的来源键
     * @return 出站事件
     */
    public static AuditEvent outboundMessage(Scope scope, AuditStatus status, AuditErrorCode errorCode,
                                             Message message, String sourceId) {
        return new AuditEvent(scope, AuditAction.MESSAGE_OUTBOUND_FINISHED, status, errorCode,
                null, null, null, null, sourceId, 1, message, Instant.now());
    }
}
