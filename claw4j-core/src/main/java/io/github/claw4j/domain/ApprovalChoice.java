package io.github.claw4j.domain;

/**
 * 人的决定落到哪一档（票 05）。与 {@code approval_request.decision} 那一列逐字对应：
 * {@code allow-once} / {@code allow-always} / {@code deny}。
 *
 * <p>为什么不放在 {@code domain.port} 里：那个包只放契约（接口与 record），枚举是带实现的类型。
 * {@code CoreBoundariesTest} 会红——这条边界是机器守的，不是文风偏好。
 *
 * <p>为什么不是 boolean：{@code allow-always} 落的不是"更长的有效期"，而是另一张表上的一条持久
 * 条目（{@code exec_allowlist}）。把它压进 {@code true}，卡片上那个第三选项就只能假装成"批一次"。
 */
public enum ApprovalChoice {

    /** 只放这一次。 */
    ALLOW_ONCE,

    /** 放这一次，并记住（{@code ask=always} 时卡片上不该给这一档）。 */
    ALLOW_ALWAYS,

    /** 不跑。 */
    DENY
}
