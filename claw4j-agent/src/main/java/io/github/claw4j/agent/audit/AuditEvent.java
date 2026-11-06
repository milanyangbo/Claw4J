package io.github.claw4j.agent.audit;

import io.github.claw4j.domain.principal.Principal;

/**
 * 一条审计事件（ADR-0027 决定二）。
 *
 * <p><b>它是元数据，不是内容</b>：这里没有提示词、没有消息体、没有工具参数、没有工具结果、
 * 没有原始错误。这不是"暂时没加"，是这张表存在的条件——审计要回答"谁在什么时候让谁做了什么、
 * 成没成"，答那个问题不需要内容，而带上内容就等于给业务数据做了第二份副本。
 *
 * <p><b>分成 {@link Scope} 与事件本身</b>：一次运行里会有很多条事件，它们共享同一份
 * "谁、在哪段会话、从哪个通道来"。合成一个扁平 record 之后，每造一条事件都要把七个
 * 相同的字段再抄一遍，而抄错一格不会有任何编译错误——它会变成一行"看起来正常"的脏数据。
 *
 * @param scope           这一轮的身份与位置
 * @param action          四类之一
 * @param status          闭合词表里的结局
 * @param errorCode       归一化错误码（{@link #POLICY_DENIED} 等），不是异常消息原文
 * @param toolName        工具名；运行类事件为空
 * @param callFingerprint 工具调用 id 的单向指纹；运行类事件为空
 * @param approvalId      放行这次调用的审批编号；没有审批时为空
 */
public record AuditEvent(Scope scope, AuditAction action, AuditStatus status,
                         String errorCode, String toolName, String callFingerprint, String approvalId) {

    /** 策略挡住的那一次。 */
    public static final String POLICY_DENIED = "policy_denied";

    /** 工具自己抛了异常。不细分到异常类型：类型名是开放的，而这一列是闭合口径。 */
    public static final String TOOL_ERROR = "tool_error";

    /** 超时中止。 */
    public static final String TIMEOUT = "timeout";

    /** 被取消（用户 /stop、进程退出）。 */
    public static final String CANCELLED = "cancelled";

    /**
     * 这一轮是谁、在哪段会话、从哪个通道来。
     *
     * <p>六个字段全部可空：审计不能因为"取不到身份"就不写——"有个工具跑了"本身就是事实，
     * 只是那一格没人。为一行缺字段的审计去拒绝整个工具调用，是把记账当成了前置条件。
     */
    public record Scope(String turnId, String sessionKey, String agentId,
                        String actor, String actorRole, String channel) {

        /** 从身份与位置组一份。principal 为空时只留位置。 */
        public static Scope of(String turnId, String sessionKey, Principal principal, String channel) {
            return new Scope(turnId, sessionKey, null,
                    principal == null ? null : principal.userId(),
                    principal == null ? null : principal.role(),
                    channel);
        }

        /** 什么都没有：仍然写，"发生过"比"字段齐全"重要。 */
        public static Scope empty() {
            return new Scope(null, null, null, null, null, null);
        }
    }

    /** 一轮开始。 */
    public static AuditEvent runStarted(Scope scope) {
        return new AuditEvent(scope, AuditAction.AGENT_RUN_STARTED, AuditStatus.STARTED,
                null, null, null, null);
    }

    /** 一轮结束。{@code status} 只能是 succeeded / failed / cancelled / timed_out / unknown。 */
    public static AuditEvent runFinished(Scope scope, AuditStatus status, String errorCode) {
        return new AuditEvent(scope, AuditAction.AGENT_RUN_FINISHED, status,
                errorCode, null, null, null);
    }

    /** 一个工具开始执行。被策略挡住的调用**没有**这一条——它根本没开始。 */
    public static AuditEvent toolStarted(Scope scope, String toolName, String callFingerprint) {
        return new AuditEvent(scope, AuditAction.TOOL_ACTION_STARTED, AuditStatus.STARTED,
                null, toolName, callFingerprint, null);
    }

    /** 一个工具结束（成功、失败、被拒、超时都走这一条，用 {@code status} 分开）。 */
    public static AuditEvent toolFinished(Scope scope, String toolName, String callFingerprint,
                                          AuditStatus status, String errorCode, String approvalId) {
        return new AuditEvent(scope, AuditAction.TOOL_ACTION_FINISHED, status,
                errorCode, toolName, callFingerprint, approvalId);
    }
}
