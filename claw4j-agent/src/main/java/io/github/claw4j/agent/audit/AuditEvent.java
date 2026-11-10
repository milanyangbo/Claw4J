package io.github.claw4j.agent.audit;

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
 */
public record AuditEvent(Scope scope, AuditAction action, AuditStatus status,
                         AuditErrorCode errorCode, String toolName, String argsDigest,
                         String approvalId) {

    /**
     * 这一轮是谁、在哪段会话、从哪个通道来。
     *
     * <p>只有 {@code runId} 是必需的：没有它这一行落不下去（{@link AuditLedger} 直接跳过），因为它
     * 不属于任何一次运行——按 {@code runId} 查永远查不到，留着只会让计数虚高。OpenClaw 的
     * {@code AuditEventSchema.runId} 同样是必填
     * （{@code packages/gateway-protocol/src/schema/audit.ts:56}）。
     *
     * <p>其余五个字段可空：审计不能因为取不到身份就不写——「有个工具跑了」本身就是事实，只是那一格
     * 没人。为一行缺字段的审计去拒绝整个工具调用，是把记账当成了前置条件。
     */
    public record Scope(String runId, String sessionKey, String agentId,
                        String actor, String actorRole, String channel) {

        /**
         * 从身份与位置组一份。{@code principal} 为空时只留位置。
         *
         * @param runId 这一轮的编号
         * @param sessionKey 会话键
         * @param principal 身份，可为 null
         * @param channel 通道名，可为 null
         * @return 事件作用域
         */
        public static Scope of(String runId, String sessionKey, Principal principal, String channel) {
            return new Scope(runId, sessionKey, null,
                    principal == null ? null : principal.userId(),
                    principal == null ? null : principal.role(),
                    channel);
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
                null, null, null, null);
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
                errorCode, null, null, null);
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
                null, toolName, argsDigest, null);
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
                errorCode, toolName, argsDigest, approvalId);
    }
}
