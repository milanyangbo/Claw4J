package io.github.claw4j.agent.run;

import java.time.Instant;

/**
 * run 里的一步。
 *
 * <p><b>它只在 run 的边界上产生</b>：工具循环内部的检索与自纠错不落库（ADR-0016）。
 * 这条边界是硬的了，因为它一旦松掉，一次 run 会涨到几百行，
 * 而这张表作为"审计投影"的价值恰恰在于它<b>小到能读完</b>。
 *
 * @param seq            同一 run 内自增。这是"第几步"唯一的来源
 * @param input          这一步的入站内容（已脱敏）
 * @param toolNames      这一步调过的工具名，逗号分隔。<b>不存参数原文</b>——
 *                        第三方 MCP 的参数里可能有凭据与个人信息
 * @param resultSummary  结果摘要。同样不落完整产出，落的是"这一步得到了什么"的一句话
 */
public record Step(String runId,
                   int seq,
                   StepKind kind,
                   String input,
                   String toolNames,
                   String resultSummary,
                   StepStatus status,
                   Instant createdAt) {

    public Step {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("step 必须属于某个 run");
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("step 的序号从 1 起：" + seq);
        }
    }
}
