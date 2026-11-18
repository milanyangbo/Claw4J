package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.advisor.PiiRedactor;

/**
 * 审批行上那句给人看的参数摘要：先脱敏，再截断。
 *
 * <p>顺序不能反。反过来的话，一个手机号会被截成两半，两半都不再匹配号码规则，于是掩码失效、
 * 前半截原样进库。
 *
 * <p>两个入口共用这一份（exec 的冻结件与普通工具的参数）：它们写的是同一列
 * {@code approval_request.args_summary}，上限也是同一个。各写一遍的后果是改了列长只改一处，
 * 另一处继续被库拒掉——而拒绝的方式是"审批请求没登记上"，人看到的是"没人能批"。
 */
final class ArgsSummary {

    /**
     * 摘要的长度上限，与 {@code args_summary varchar(512)} 对齐。
     *
     * <p>超过它就会被库拒掉。截断在写之前做，比让库去挡清楚。
     */
    static final int LIMIT = 480;

    private ArgsSummary() {
    }

    /**
     * 脱敏并截断。
     *
     * @param raw 原始文本，可以为 {@code null}
     * @return 摘要；{@code null} 入参返回空串
     */
    static String of(String raw) {
        String masked = PiiRedactor.mask(raw == null ? "" : raw);
        return masked.length() <= LIMIT ? masked : masked.substring(0, LIMIT - 1) + "…";
    }
}
