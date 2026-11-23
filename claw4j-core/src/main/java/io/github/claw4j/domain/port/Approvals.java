package io.github.claw4j.domain.port;

import io.github.claw4j.domain.ApprovalChoice;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.principal.Principal;

import java.time.Instant;
import java.util.Optional;

/**
 * 审批的全部内容，按 OpenClaw 的三个概念排：<b>请求</b>（{@link #ask}）、<b>裁决</b>
 * （{@link #decide} / {@link #find}）、<b>记录</b>（{@link #redeem}）。
 *
 * <p>三个概念一个端口：它们共享同一张表、同一个"还挂着且没过期"的判据，拆成多个类型时，
 * 每多一层就多一处"改了一边忘了另一边"——卡片推出去了但没登记、登记了但没判
 * allow-always，都是这种形状的错，而且都不报错。
 *
 * <p>实现在 Agent 模块（要落库、要推卡片、要在结论之后回那段对话开新一轮）；入口在通道
 * （HTTP 与飞书卡片调 {@link #decide}）。放在 {@code domain.port} 是因为 {@code channel → core}
 * 这条边（ADR-0010）：通道不能认识 Agent 的实现类。
 */
public interface Approvals {

    /**
     * 兑现一条同人、同工具、同参数的批准，并当场把它标成用掉。
     *
     * @param owner 审批人
     * @param toolName 工具名
     * @param argsDigest 参数指纹
     * @return 批准的凭据；空表示没有
     */
    Optional<Grant> redeem(String owner, String toolName, String argsDigest);

    /**
     * 登记一条待批请求，并把它推到那段对话里。
     *
     * @param ask 待批请求
     * @return 审批编号。空表示没记下来——调用方仍然要拦下这次调用，只是没有编号可以回给用户
     */
    Optional<String> ask(Ask ask);

    /**
     * 落一个人的决定：先判他有没有资格，再落终态，最后回那段对话开新一轮。
     *
     * @param approvalId 审批编号
     * @param approver 点按钮的人的 {@code userId}
     * @param choice 结论档位
     * @param reason 拒绝理由，可为 {@code null}
     * @return 结论与回给操作者的那句话
     */
    Result decide(String approvalId, String approver, ApprovalChoice choice, String reason);

    /**
     * 读一条请求现在的样子，给卡片与接口渲染。
     *
     * @param approvalId 审批编号
     * @return 待批视图；编号为空或读不出来时为 empty
     */
    Optional<Pending> find(String approvalId);

    /**
     * 收下一个"批一次 / 拒"的决定。
     *
     * <p>留这个方法是因为绝大多数入口只有两个按钮（接口与脚本），它们不需要表达
     * {@link ApprovalChoice#ALLOW_ALWAYS}。写成默认方法而不是要求每个入口都传三档：
     * 入口越简单，越不该逼它认识它用不到的档位。
     *
     * @param approvalId 审批编号
     * @param approver 谁批的
     * @param approved true 批准（只这一次），false 拒绝
     * @param reason 拒绝的理由
     * @return 结论与给人看的补充说明
     */
    default Result decide(String approvalId, String approver, boolean approved, String reason) {
        return decide(approvalId, approver, approved ? ApprovalChoice.ALLOW_ONCE : ApprovalChoice.DENY, reason);
    }

    /**
     * 这条请求从哪段对话来、发起人是谁。
     *
     * @param sessionKey 会话键（含段），可以为 {@code null}
     * @param channel 卡片与结论的投递通道，可以为 {@code null}
     * @param chatRef 通道内的地址，可以为 {@code null}
     * @param principal 发起人，可以为 {@code null}（那时这条请求不会自动续跑）
     */
    record Origin(String sessionKey, String channel, String chatRef, Principal principal) {
    }

    /**
     * 一条待批请求。
     *
     * @param executionId 哪一轮发起的，可以为 {@code null}
     * @param owner 发起人
     * @param toolName 工具名
     * @param argsDigest 参数指纹（exec 是冻结件的摘要，其他工具是规范化参数的摘要）
     * @param argsText 给人看的原文；落库前由实现脱敏并截断
     * @param origin 来处，可以为 {@code null}
     * @param frozenPlan 冻结件（JSON），只有 exec 会写
     * @param allowAlways 这条请求接不接受"以后都放行"
     */
    record Ask(String executionId, String owner, String toolName, String argsDigest, String argsText,
               Origin origin, String frozenPlan, boolean allowAlways) {

        /**
         * 不接受"以后都放行"的那一档：没有持久放行落点的审批都走它。
         *
         * @param executionId 哪一轮发起的，可以为 {@code null}
         * @param owner 发起人
         * @param toolName 工具名
         * @param argsDigest 参数指纹
         * @param argsText 给人看的原文
         * @param origin 来处，可以为 {@code null}
         * @param frozenPlan 冻结件，可以为 {@code null}
         */
        public Ask(String executionId, String owner, String toolName, String argsDigest, String argsText,
                   Origin origin, String frozenPlan) {
            this(executionId, owner, toolName, argsDigest, argsText, origin, frozenPlan, false);
        }

        /**
         * 既没有冻结件、也不接受长期放行的那一档。
         *
         * @param executionId 哪一轮发起的，可以为 {@code null}
         * @param owner 发起人
         * @param toolName 工具名
         * @param argsDigest 参数指纹
         * @param argsText 给人看的原文
         * @param origin 来处，可以为 {@code null}
         */
        public Ask(String executionId, String owner, String toolName, String argsDigest, String argsText,
                   Origin origin) {
            this(executionId, owner, toolName, argsDigest, argsText, origin, null, false);
        }
    }

    /**
     * 一条被兑现的批准。
     *
     * @param id 审批编号（写进审计，回答"这次执行是哪次批准的"）
     * @param expiresAt 到期时刻
     * @param frozenPlan 批准那一刻冻结的执行计划（JSON），可以为 {@code null}
     */
    record Grant(String id, Instant expiresAt, String frozenPlan) {

        /**
         * 不带冻结件的那一版：普通工具的审批没有"批准的是哪一条命令"这件事。
         *
         * @param id 审批编号
         * @param expiresAt 到期时刻
         */
        public Grant(String id, Instant expiresAt) {
            this(id, expiresAt, null);
        }
    }

    /**
     * 一次决定的结果。
     *
     * @param verdict 结论的取值见 {@link ApprovalVerdict}
     * @param detail 给人看的一句话。空表示"没什么可补充的"
     */
    record Result(ApprovalVerdict verdict, String detail) {
    }

    /**
     * 一条请求现在的样子（给接口与卡片渲染用）。
     *
     * @param id 审批编号
     * @param executionId 哪一轮发起的，可空
     * @param owner 发起人
     * @param toolName 工具名
     * @param argsSummary 参数摘要（已脱敏、已截断）
     * @param status 库里的状态
     * @param expired 是否已过期（按库的时钟判）
     * @param expiresAt 有效期
     * @param allowAlways 这条请求接不接受"以后都放行"
     */
    record Pending(String id, String executionId, String owner, String toolName, String argsSummary,
                   String status, boolean expired, Instant expiresAt, boolean allowAlways) {

        /**
         * 不接受"以后都放行"的那一档：除 exec 之外的审批都没有持久放行的落点。
         *
         * @param id 审批编号
         * @param executionId 哪一轮发起的，可空
         * @param owner 发起人
         * @param toolName 工具名
         * @param argsSummary 参数摘要
         * @param status 库里的状态
         * @param expired 是否已过期
         * @param expiresAt 有效期
         */
        public Pending(String id, String executionId, String owner, String toolName, String argsSummary,
                       String status, boolean expired, Instant expiresAt) {
            this(id, executionId, owner, toolName, argsSummary, status, expired, expiresAt, false);
        }
    }
}
