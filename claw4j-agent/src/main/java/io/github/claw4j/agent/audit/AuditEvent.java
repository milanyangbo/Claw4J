package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.audit.MessageDirection;
import io.github.claw4j.domain.audit.MessageOutcome;
import io.github.claw4j.domain.principal.Principal;

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
 * @param toolName 工具名；运行类事件为空
 * @param argsDigest 参数指纹；运行类事件为空
 * @param approvalId 放行这次调用的审批编号；没有审批时为空
 * @param sourceId 来源键：同一件事的多次投递共用它（{@code run:<runId>} 或
 *     {@code tool:<runId>:<工具>:<指纹>}）。与 {@code sourceSequence} 组成唯一键，
 *     重复投递靠 {@code on conflict do nothing} 只留第一行（OpenClaw 同名同义，
 *     {@code audit-event-store.ts:81-116}）
 * @param sourceSequence 来源内序号：1 = started，2 = finished
 * @param message 消息专有的那一组（方向、结果、假名引用）；运行与工具事件为空
 */
public record AuditEvent(Scope scope, AuditAction action, AuditStatus status,
                         AuditErrorCode errorCode, String toolName, String argsDigest,
                         String approvalId, String sourceId, int sourceSequence,
                         Message message) {

    /**
     * 这一轮是谁、在哪段会话、从哪个通道来。
     *
     * <p>只有 {@code runId} 是必需的：没有它这一行落不下去（{@link AuditLedger} 直接跳过），因为它
     * 不属于任何一次运行——按 {@code runId} 查永远查不到，留着只会让计数虚高。OpenClaw 的
     * {@code AuditEventSchema.runId} 同样是必填
     * （{@code packages/gateway-protocol/src/schema/audit.ts:56}）。
     *
     * <p>其余四个字段可空：审计不能因为取不到身份就不写——「有个工具跑了」本身就是事实，只是那一格
     * 没人。为一行缺字段的审计去拒绝整个工具调用，是把记账当成了前置条件。
     */
    public record Scope(String runId, String sessionKey, String agentId,
                        String actor, String channel) {

        /**
         * 从身份与位置组一份。{@code principal} 为空时只留位置。
         *
         * @param runId 这一轮的编号
         * @param sessionKey 会话键
         * @param principal 身份，可为 null
         * @param channel 通道名，可为 null
         * @return 事件作用域
         */
        /** 没接上 agent 归属时的写法：写 {@code unknown} 而不是 null——null 让「谁干的」不可查。 */
        public static final String UNKNOWN_AGENT = "unknown";

        public static Scope of(String runId, String sessionKey, Principal principal, String channel) {
            return of(runId, sessionKey, UNKNOWN_AGENT, principal, channel);
        }

        /**
         * 带 agent 归属的那一份。
         *
         * <p>{@code agentId} 为空白时落成 {@code unknown}：与 OpenClaw 的 {@code agentId} 必填
         * （{@code audit.ts:53}）同一个要求，取不到也要留一个可数的值，而不是一格没人。
         *
         * @param runId 这一轮的编号
         * @param sessionKey 会话键
         * @param agentId 哪个 agent 干的
         * @param principal 身份，可为 null
         * @param channel 通道名，可为 null
         * @return 事件作用域
         */
        public static Scope of(String runId, String sessionKey, String agentId, Principal principal,
                               String channel) {
            return new Scope(runId, sessionKey,
                    agentId == null || agentId.isBlank() ? UNKNOWN_AGENT : agentId,
                    principal == null ? null : principal.userId(), channel);
        }

        /**
         * 什么都没有：仍然写，「发生过」比「字段齐全」重要。
         *
         * @return 全空的作用域
         */
        public static Scope empty() {
            return new Scope(null, null, null, null, null);
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
                null, null, null, null, runSource(scope), 1, null);
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
                errorCode, null, null, null, runSource(scope), 2, null);
    }

    /**
     * 一个工具开始执行。被策略挡住的调用没有这一条——它根本没开始。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @return 开始事件
     */
    public static AuditEvent toolStarted(Scope scope, String toolName, String argsDigest) {
        return new AuditEvent(scope, AuditAction.TOOL_ACTION_STARTED, AuditStatus.STARTED,
                null, toolName, argsDigest, null, toolSource(scope, toolName, argsDigest), 1, null);
    }

    /**
     * 一个工具结束。成功、失败、被拒、超时都走这一条，用 {@code status} 分开。
     *
     * @param scope 这一轮的身份与位置
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @param status 结局
     * @param errorCode 归一化错误码，成功时为 null
     * @param approvalId 放行这次调用的审批编号，没有审批时为 null
     * @return 结束事件
     */
    public static AuditEvent toolFinished(Scope scope, String toolName, String argsDigest,
                                          AuditStatus status, AuditErrorCode errorCode, String approvalId) {
        return new AuditEvent(scope, AuditAction.TOOL_ACTION_FINISHED, status,
                errorCode, toolName, argsDigest, approvalId,
                toolSource(scope, toolName, argsDigest), 2, null);
    }

    /**
     * 运行类事件的来源键。没有 runId 时给一个空归属的键——账本本来就会跳过这种行
     * （{@code AuditLedger} 见 runId 为空即计 skipped），键长什么样都不影响。
     */
    private static String runSource(Scope scope) {
        String runId = scope == null ? null : scope.runId();
        return "run:" + (runId == null ? "" : runId);
    }

    /**
     * 工具类事件的来源键：{@code tool:<runId>:<工具名>:<指纹>}。
     *
     * <p>同参数连调两次会得到同一个键（框架拿不到调用 id，见 ADR-0037 的 blocked 项），
     * 于是第二对 started/finished 会被唯一键挡成重复——这是有意的：宁可少记一行，
     * 也不让同一件事在账本里数成两次。
     */
    private static String toolSource(Scope scope, String toolName, String argsDigest) {
        String runId = scope == null ? null : scope.runId();
        return "tool:" + (runId == null ? "" : runId) + ':' + toolName + ':' + argsDigest;
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
     * @param scope 这一轮的身份与位置（可能没有 runId：入站可能在开跑之前就结束了）
     * @param status 通用结局：succeeded / blocked / failed
     * @param errorCode 失败时的归一化错误码
     * @param message 消息专有的一组
     * @param sourceId 这条消息的来源键
     * @return 入站事件
     */
    public static AuditEvent inboundMessage(Scope scope, AuditStatus status, AuditErrorCode errorCode,
                                            Message message, String sourceId) {
        return new AuditEvent(scope, AuditAction.MESSAGE_INBOUND_PROCESSED, status, errorCode,
                null, null, null, sourceId, 1, message);
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
                null, null, null, sourceId, 1, message);
    }
}
