package io.github.claw4j.agent.goal;

import java.util.Optional;
import java.util.UUID;

/**
 * 会话目标的落点（ADR-0027 决定三，形状照 OpenClaw 的 session entry）。
 *
 * <p><b>为什么开接口，而 {@code ScheduleStore} 没开</b>：这个存储有两处要用——
 * 三个模型工具与 {@code /goal} 命令族——而且<b>注入路径每回合都要读它</b>。
 * 注入那条路挂在 {@code ChatClientAgentPort} 上，那个类的用例全是假模型、没有库；
 * 没有内存实现的话，每一条"提示词里有没有那一行"的断言都得拉起一个真容器。
 * 定时任务没有这个约束（它只在工具里用，用例本来就走真库）。
 *
 * <p><b>定位键是会话节点的键（不含段）</b>：目标在 entry 上，{@code /new} 时被清掉。
 * 传含段的 sessionId 会找不到行——那不是"新段没有目标"的机制，而是查错了键。
 *
 * <p><b>写方法都带 {@code goalId}</b>：它的 {@code SessionsGoalUpdateParams} 里每一个
 * 变更都要求 {@code goalId}，文档原话是"Controls target the displayed Goal ID, so a stale
 * button cannot change a replacement Goal"。不带 id 的话，清掉再建之后，旧界面上那个
 * "完成"按钮点下去改的是新目标——而两次点击看起来一模一样。
 *
 * <p><b>所有写方法在"没写成"时返回空，不抛异常</b>：目标写不进去不该让一整轮回答变成 500。
 * 调用方拿到空之后再 {@link #of} 读一次，用它区分"没有目标"、"目标已经换了"、
 * "已经达成了"与"库连不上"——四种情况对用户说的话完全不同。
 */
public interface SessionGoalStore {

    /** 这个会话的目标。没有时返回空。 */
    Optional<SessionGoal> of(String sessionKey);

    /**
     * 新建。**已有目标时不做任何事并返回空**。
     *
     * <p>不静默覆盖：OpenClaw 的原话是"在清除当前目标之前，启动第二个目标会失败，
     * 并显示 {@code Goal error: goal already exists}"。覆盖的话用户会以为新建了，
     * 其实改的是旧的那个——而旧目标的 token 用量与状态一起没了。
     *
     * @param tokenBudget null 表示不限额；有值时必须是正数
     * @return 写下来的那一行；空表示已有目标、或没写进去
     */
    Optional<SessionGoal> start(String sessionKey, String objective, Long tokenBudget);

    /**
     * 改写目标内容。**状态与用量不变**。
     *
     * <p><b>达成之后不能改</b>：原文的排错表 {@code Goal error: goal is already complete}
     * ——"目标必须先被显式 resume 才能编辑、暂停或阻塞"。
     *
     * <p>OpenClaw 的原话："重新表述当前目标；状态和 Token 统计保持不变"。
     * 这条最容易被实现成"先 clear 再 start"，而那样 token 用量会归零——
     * 一个"改个措辞"的动作把额度还回去了。
     *
     * @return 改后的那一行；空表示没有目标、目标已达成、id 对不上、或没写进去
     */
    Optional<SessionGoal> edit(String sessionKey, UUID goalId, String objective);

    /**
     * 改状态，并把 {@code note} 写成"最新状态说明"。
     *
     * <p>五个时刻列在这里分开写：{@code paused} 记 {@code pausedAt}、{@code blocked} 记
     * {@code blockedAt}、{@code complete} 记 {@code completedAt}（首次那个不覆盖）、
     * {@code usage_limited} 记 {@code usageLimitedAt}。合用一个 {@code updatedAt} 的话，
     * "重复 complete 保持首次完成时间"这条规则（{@code goals-transitions.ts:129}）就没了落点。
     *
     * @param note 可以为 null（等于把上一条说明清掉）
     * @return 改后的那一行；空表示没有目标、目标是终态（{@link GoalStatus#COMPLETE}）、
     *         id 对不上、或没写进去
     */
    Optional<SessionGoal> setStatus(String sessionKey, UUID goalId, GoalStatus status, String note);

    /**
     * 恢复一个目标：状态变 {@code active}，**额度用完的那一个顺手开新窗口**。
     *
     * <p>OpenClaw 原文两处合起来才是完整的规则：
     * "Token accounting continues unless the budget was reached, in which case resume starts a
     * fresh budget window." 所以新窗口只发生在 {@code budget_limited} / {@code usage_limited} 上——
     * 无条件清零等于"暂停一下就能白拿一份额度"。
     *
     * <p><b>完成的也能恢复</b>：命令表里 resume 的作用对象是"已暂停、已阻塞、受限<b>或已完成</b>"。
     * 恢复同一个目标（内容、创建时间不动）；{@code completedAt} 在从 complete 回到 active 时清掉
     * ——完成属于"上一次达成"，再次达成要记新的时刻。
     *
     * @param note 可以为 null（等于把上一条说明清掉）
     * @return 改后的那一行；空表示没有目标、目标本来就是 {@code active}、id 对不上、或没写进去
     */
    Optional<SessionGoal> resume(String sessionKey, UUID goalId, String note);

    /**
     * 记这一轮花掉的 token，并**按快照差重算用量**；撞上预算就同时进 {@code budget_limited}。
     *
     * <p>这是它 {@code accountSessionGoalUsage}（{@code goals-transitions.ts:26-65}）的对应物：
     * 用量不是"累加"，而是 {@code max(旧值, 会话累计 - tokenStart)}——漏记一轮下一轮能补回来。
     * {@code tokenStartFresh=false} 时这一轮把 {@code tokenStart} 采纳成当前累计，
     * 旧用量保持（建目标那一刻还没有新鲜快照，之前的花费不算给它）。
     *
     * <p><b>记账与判超在同一条语句里</b>：分两步的话，两条并发消息会各自读到"还没超"，
     * 各自加一笔，结果是超了却没进受限状态——一个不会自己恢复的错。
     *
     * @param deltaTokens 这一轮模型报上来的用量；<b>null 表示拿不到</b>。它与 0 是两件事：
     *                    0 是"这一轮没花钱"，null 是"这一轮没有数"，后者不能拿来重算
     * @return 记完之后的这一行；空表示没有目标、或没写进去。调用方不需要它也能继续回答
     */
    Optional<SessionGoal> account(String sessionKey, Long deltaTokens);

    /** 删掉这一行。{@code /goal clear} 用。@return 真的删掉了一行 */
    boolean clear(String sessionKey, UUID goalId);
}
