package io.github.claw4j.domain.port;

import io.github.claw4j.domain.ApprovalChoice;
import io.github.claw4j.domain.ApprovalVerdict;

import java.time.Instant;
import java.util.Optional;

/**
 * 审批的"人那一侧"：有人点了批准或拒绝，世界该有什么变化（P02）。
 *
 * <p>与 {@link ApprovalLedger} 的分工是这一对接口的全部要点：账本回答"此刻有没有一条能用的
 * 批准"，是执行前的一次询问；本接口收下一次人的决定，是决定之后的连锁反应——落结论、在那段
 * 对话里接着说、把理由回灌给模型。合成一个接口的话，鉴权那条热路径上会挂着一整套状态驱动的
 * 代码。
 *
 * <p>实现在 Agent 模块（它要读审批表、要落结论、要叫醒那段对话）；入口是通道的事（P03 的
 * HTTP 接口与飞书卡片），所以放在 domain.port 而不是某个通道的 spi。
 */
public interface ApprovalDecider {

    /**
     * 一次决定的结果。
     *
     * @param verdict 结论的取值见 {@link io.github.claw4j.domain.ApprovalVerdict}
     * @param detail  给人看的一句话。空表示"没什么可补充的"
     */
    record Result(ApprovalVerdict verdict, String detail) {
    }

    /**
     * 一条审批请求现在的样子（给接口与卡片渲染用）。
     *
     * <p>没有 argsDigest：指纹是给机器比对的，展示它等于让人去比两串十六进制。人要看的是
     * {@code argsSummary}——"这个工具这一次打算干什么"。
     *
     * @param expired 由实现按库的时钟判，不在调用方算（同 {@link ApprovalLedger#redeem} 的理由）。
     *                卡片据此就地显示"已过期"，而不是显示一个点下去必然失败的按钮
     * @param allowAlways 这个请求接不接受"以后都放行"（票 05）。为 {@code false} 时卡片上不该
     *                    出现那个按钮：{@code ask=always} 的含义是每条都要人看，落一条持久放行
     *                    等于把它悄悄改成 {@code on-miss}。服务端按同一个判断拒收——卡片只是
     *                    "别让人点一个注定被拒的按钮"
     */
    record Pending(String id, String runId, String owner, String toolName, String argsSummary,
                   String status, boolean expired, Instant expiresAt, boolean allowAlways) {

        /**
         * 不接受"以后都放行"的那一档：除 exec 之外的审批都没有持久放行的落点。
         *
         * @param id 审批编号
         * @param runId 哪一轮发起的，可空
         * @param owner 发起人
         * @param toolName 工具名
         * @param argsSummary 参数摘要
         * @param status 库里的状态
         * @param expired 是否已过期
         * @param expiresAt 有效期
         */
        public Pending(String id, String runId, String owner, String toolName, String argsSummary,
                       String status, boolean expired, Instant expiresAt) {
            this(id, runId, owner, toolName, argsSummary, status, expired, expiresAt, false);
        }
    }

    /**
     * 收下一个决定。
     *
     * <p>先定结论，再叫醒 run：反过来的话，run 会在一段还没落地的结论上跑起来，而它下一轮的
     * 鉴权要读的正是这条结论，读不到就又是一次"没有批准"。
     *
     * <p>三档而不是"批 / 拒"两档（票 05）：{@link ApprovalChoice#ALLOW_ALWAYS} 是"以后这个可执行文件都
     * 放行"，它落的不是更长的有效期，而是另一张表上的一条持久条目。把它压进 boolean，卡片上
     * 那个第三选项就只能假装是"批一次"。
     *
     * @param approvalId 审批编号
     * @param approver 谁批的。P03 起在这里校验"这个人有没有资格批"（名单、不能批自己的），
     *                 不合格时不落结论、不改任何一行，那条请求仍然挂着等别人。检查写在这一处
     *                 而不是各入口，是因为入口有两个（HTTP 与卡片），写在入口上的规矩早晚
     *                 有一处漏掉
     * @param choice 结论档位
     * @param reason 拒绝的理由。它会被回灌给模型（"别再请求同一件事，换个做法"），所以批准时
     *               可以不给
     * @return 结论与给人看的补充说明
     */
    Result decide(String approvalId, String approver, ApprovalChoice choice, String reason);

    /**
     * 收下一个"批一次 / 拒"的决定。
     *
     * <p>留这个方法是因为绝大多数入口只有两个按钮（接口与脚本），它们不需要表达
     * {@link ApprovalChoice#ALLOW_ALWAYS}。写成默认方法而不是 require 每个入口都传三档：入口越简单，
     * 越不该逼它认识它用不到的档位。
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
     * 读一条请求的当前样子。
     *
     * @param approvalId 审批编号
     * @return 这条请求；空表示没有这个编号
     */
    Optional<Pending> find(String approvalId);
}
