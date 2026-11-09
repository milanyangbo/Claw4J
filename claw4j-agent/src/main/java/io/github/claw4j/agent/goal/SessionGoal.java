package io.github.claw4j.agent.goal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个会话上的目标（ADR-0027 决定三，字段照 OpenClaw 的 {@code SessionGoalSchema}）。
 *
 * <p><b>它是会话状态，不是任务</b>：OpenClaw 的原话是"A goal is one durable objective
 * attached to the current OpenClaw session"，并且明确"A goal is not a task queue"。
 * 所以这里没有"第几步"、没有"进度百分比"、没有优先级——那些东西要么是
 * 某一轮的事，要么根本不该存在。
 *
 * <p><b>字段逐条对齐 {@code packages/gateway-protocol/src/schema/sessions-goal.ts:7-32}</b>，
 * 顺序也一样。这不是抄着玩：{@code id} 是变更请求的目标身份（旧按钮不能改到替换后的目标），
 * {@code tokenStart} / {@code tokenStartFresh} 决定预算从哪一刻起算，
 * 五个 {@code *At} 记的是"这件事什么时候变成这个状态"——它们各自都能单独回答一个
 * 从 {@code updatedAt} 里猜不出来的问题。
 *
 * <p><b>{@code objective} 保留原文</b>：换行、缩进、连续空格都不动，只有首尾空白被裁掉。
 * 一句话的目标与七行的目标都该原样进去，因为"把换行折成空格"会让一份清单变成一段
 * 读不懂的散文，而用户写的时候是照着清单写的。
 *
 * @param sessionKey      会话节点的键（**不含段**）。{@code /new} 之后目标被清掉，
 *                        不是"换了一个 key 所以读不到"
 * @param schemaVersion   对象格式版本，恒为 1；加字段时旧行按它解释
 * @param id              目标身份（UUID）。所有改状态的语句都带它，防"旧按钮改到新目标"
 * @param objective       目标原文（首尾已裁白）
 * @param status          六态之一
 * @param createdAt       这一版目标创建的时刻
 * @param updatedAt       最后一次改动
 * @param tokenStart      预算窗口的起点：创建目标时会话的累计用量
 * @param tokenStartFresh 起点是不是一个真实的快照。false 表示创建时那轮拿不到用量，
 *                        下一次真实用量到来时才采纳为新起点（此前花费不算给它）
 * @param tokensUsed      从起点算起的用量（存下来的"最后一次已知值"，见 {@code account}）
 * @param tokenBudget     不限额时是 null
 * @param continuationTurns 自动续跑已经推进过的轮数。我们还没做续跑，恒 0；
 *                        列留着是因为它属于目标状态，不是某一轮的事
 * @param note            最新一条状态说明，例如"waiting for CI"；没有时是 null
 * @param pausedAt        最后一次进入 paused 的时刻
 * @param blockedAt       最后一次进入 blocked 的时刻
 * @param completedAt     首次达成的时刻。重复 complete 不覆盖它，resume 之后再次达成才记新的
 * @param usageLimitedAt  最后一次进入 usage_limited 的时刻（这一档我们还进不去，恒 null）
 * @param budgetLimitedAt 最后一次撞上预算的时刻
 */
public record SessionGoal(String sessionKey,
                          int schemaVersion,
                          UUID id,
                          String objective,
                          GoalStatus status,
                          Instant createdAt,
                          Instant updatedAt,
                          long tokenStart,
                          boolean tokenStartFresh,
                          long tokensUsed,
                          Long tokenBudget,
                          int continuationTurns,
                          String note,
                          Instant pausedAt,
                          Instant blockedAt,
                          Instant completedAt,
                          Instant usageLimitedAt,
                          Instant budgetLimitedAt) {

    /** 当前的对象格式版本。与它一样恒为 1。 */
    public static final int SCHEMA_VERSION = 1;

    public SessionGoal {
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(id, "id");
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
        if (tokenStart < 0 || tokensUsed < 0) {
            throw new IllegalArgumentException("用量不能是负数：" + tokenStart + "/" + tokensUsed);
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("目标格式版本必须是正数：" + schemaVersion);
        }
    }

    /** 这个目标现在该不该进提示词。只有 {@link GoalStatus#ACTIVE} 该进。 */
    public boolean injected() {
        return status == GoalStatus.ACTIVE;
    }
}
