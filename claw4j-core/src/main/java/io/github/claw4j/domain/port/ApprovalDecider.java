package io.github.claw4j.domain.port;

import io.github.claw4j.domain.ApprovalVerdict;

import java.time.Instant;
import java.util.Optional;

/**
 * 审批的"人那一侧"：有人点了批准或拒绝，世界该有什么变化（P02）。
 *
 * <p>与 {@link ApprovalLedger} 的分工是这一对接口的全部要点：账本回答
 * "此刻有没有一条能用的批准"，是<b>执行前的一次询问</b>；本接口收下一次人的决定，
 * 是<b>决定之后的连锁反应</b>——落结论、在那段对话里接着说、把理由回灌给模型。
 * 合成一个接口的话，鉴权那条热路径上会挂着一整套状态驱动的代码。
 *
 * <p>实现在 Agent 模块（它要读审批表、要落结论、要叫醒那段对话）；入口是通道的事
 * （P03 的 HTTP 接口与飞书卡片），所以放在 domain.port 而不是某个通道的 spi。
 */
public interface ApprovalDecider {

    /**
     * @param verdict 结论的取值见 {@link io.github.claw4j.domain.ApprovalVerdict}
     * @param detail  给人看的一句话。空表示"没什么可补充的"
     */
    record Result(ApprovalVerdict verdict, String detail) {
    }

    /**
     * 一条审批请求现在的样子（给接口与卡片渲染用）。
     *
     * <p><b>没有 argsDigest</b>：指纹是给机器比对的，展示它等于让人去比两串十六进制。
     * 人要看的是 {@code argsSummary}——"这个工具这一次打算干什么"。
     *
     * @param expired 由实现按库的时钟判，不在调用方算（同 {@link ApprovalLedger#redeem} 的理由）。
     *                卡片据此就地显示"已过期"，而不是显示一个点下去必然失败的按钮
     */
    record Pending(String id, String runId, String owner, String toolName, String argsSummary,
                   String status, boolean expired, Instant expiresAt) {
    }

    /**
     * 收下一个决定。
     *
     * <p><b>先定结论，再叫醒 run</b>：反过来的话，run 会在一段还没落地的结论上跑起来，
     * 而它下一轮的鉴权要读的正是这条结论——读不到就又是一次"没有批准"。
     *
     * @param approver 谁批的。P03 起在这里校验"这个人有没有资格批"（名单、不能批自己的），
     *                 不合格时<b>不落结论、不改任何一行</b>，那条请求仍然挂着等别人——
     *                 检查写在这一处而不是各入口，是因为入口有两个（HTTP 与卡片），
     *                 写在入口上的规矩早晚有一处漏掉
     * @param approved true 批准，false 拒绝
     * @param reason   拒绝的理由。它会被回灌给模型（"别再请求同一件事，换个做法"），
     *                 所以批准时可以不给
     */
    Result decide(String approvalId, String approver, boolean approved, String reason);

    /** 读一条请求的当前样子。空表示没有这个编号。 */
    Optional<Pending> find(String approvalId);
}
