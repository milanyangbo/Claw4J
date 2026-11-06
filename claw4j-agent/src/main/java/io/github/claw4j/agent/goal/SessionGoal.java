package io.github.claw4j.agent.goal;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个会话上的目标（ADR-0027 决定三）。
 *
 * <p><b>它是会话状态，不是任务</b>：OpenClaw 的原话是"A goal is one durable objective
 * attached to the current OpenClaw session"，并且明确"A goal is not a task queue"。
 * 所以这里没有"第几步"、没有"进度百分比"、没有优先级——那些东西要么是
 * {@code agent_step} 的事，要么根本不该存在。
 *
 * <p><b>{@code objective} 保留原文</b>：换行、缩进、连续空格都不动，只有首尾空白被裁掉。
 * 一句话的目标与七行的目标都该原样进去，因为"把换行折成空格"会让一份清单变成一段
 * 读不懂的散文，而用户写的时候是照着清单写的。
 *
 * <p><b>{@code tokensUsed} 本票只读不写</b>：账在票 04 记（预算并进 goal）。
 * 现在它总是 0，但摘要里必须显示出来——一个字段在界面上"还没有"与"不存在"是两件事。
 *
 * @param sessionKey 会话键（含段）。它与 {@code SessionKeys#sessionIdOf} 同形状，
 *                   所以 {@code /new} 换段之后这一行自然读不到，不需要谁去删
 * @param status     六态之一
 * @param objective  目标原文（首尾已裁白）
 * @param note       最新一条状态说明，例如"waiting for CI"；没有时是 null
 * @param tokenBudget 不限额时是 null
 * @param tokensUsed  已用量
 * @param revision    改写次数，从 1 开始
 */
public record SessionGoal(String sessionKey,
                          GoalStatus status,
                          String objective,
                          String note,
                          Long tokenBudget,
                          long tokensUsed,
                          int revision,
                          Instant createdAt,
                          Instant updatedAt) {

    public SessionGoal {
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(objective, "objective");
        if (objective.isBlank()) {
            // 空白目标不是"空目标"，是"存了一个空格"。它会让注入那一行变成
            // "活跃目标： — 推进目标…"，看起来一切正常，实际上没有目标。
            throw new IllegalArgumentException("目标内容不能为空");
        }
        if (tokenBudget != null && tokenBudget <= 0) {
            throw new IllegalArgumentException("token 预算必须是正数：" + tokenBudget);
        }
    }

    /** 这个目标现在该不该进提示词。只有 {@link GoalStatus#ACTIVE} 该进。 */
    public boolean injected() {
        return status == GoalStatus.ACTIVE;
    }
}
