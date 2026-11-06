package io.github.claw4j.agent.goal;

import java.util.Optional;

/**
 * 会话目标的落点（ADR-0027 决定三）。
 *
 * <p><b>为什么开接口，而 {@code ScheduleStore} 没开</b>：这个存储有两处要用——
 * 三个模型工具与 {@code /goal} 命令族——而且<b>注入路径每回合都要读它</b>。
 * 注入那条路挂在 {@code ChatClientAgentPort} 上，那个类的用例全是假模型、没有库；
 * 没有内存实现的话，每一条"提示词里有没有那一行"的断言都得拉起一个真容器。
 * 定时任务没有这个约束（它只在工具里用，用例本来就走真库）。
 *
 * <p><b>所有写方法在"没写成"时返回空，不抛异常</b>：目标写不进去不该让一整轮回答变成 500。
 * 调用方拿到空之后再 {@link #of} 读一次，用它区分"已经有一个目标了"、"目标不存在"、
 * "已经达成了"与"库连不上"——四种情况对用户说的话完全不同，而在这里塞一个枚举
 * 只会让存储层开始解释业务。
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
     * 改写目标内容。**状态与用量不变**，{@code revision} 加一。
     *
     * <p>OpenClaw 的原话："重新表述当前目标；状态和 Token 统计保持不变"。
     * 这条最容易被实现成"先 clear 再 start"，而那样 token 用量会归零——
     * 一个"改个措辞"的动作把额度还回去了。
     *
     * @return 改后的那一行；空表示没有目标、或没写进去
     */
    Optional<SessionGoal> edit(String sessionKey, String objective);

    /**
     * 改状态，并把 {@code note} 写成"最新状态说明"。
     *
     * @param note 可以为 null（等于把上一条说明清掉）
     * @return 改后的那一行；空表示没有目标、目标是终态（{@link GoalStatus#COMPLETE}）、
     *         或没写进去
     */
    Optional<SessionGoal> setStatus(String sessionKey, GoalStatus status, String note);

    /**
     * 恢复一个目标：状态变 {@code active}，**额度用完的那一个顺手开新窗口**。
     *
     * <p>OpenClaw 原文两处合起来才是完整的规则：
     * "Token accounting continues unless the budget was reached, in which case resume starts a
     * fresh budget window." 所以清零只发生在 {@code budget_limited} 上——
     * 无条件清零等于"暂停一下就能白拿一份额度"。
     *
     * <p>判定放在 SQL 里（{@code case when status = 'budget_limited'}）而不是先读再写：
     * 先读再写会留下"读的时候还在额度里、写的时候已经超了"的窗口，
     * 那个窗口里恢复出来的目标带着一个已经超支的用量，下一轮立刻又停下。
     *
     * @param note 可以为 null（等于把上一条说明清掉）
     * @return 改后的那一行；空表示没有目标、目标是终态（{@link GoalStatus#COMPLETE}）、
     *         或没写进去
     */
    Optional<SessionGoal> resume(String sessionKey, String note);

    /**
     * 记这一轮花掉的 token；**撞上预算就同时把状态改成 {@code budget_limited}**。
     *
     * <p>记账与判超在同一条语句里（OpenClaw：用量到达预算时目标进入 {@code budget_limited}）。
     * 分两步的话，两条并发消息会各自读到"还没超"，各自加一笔，
     * 结果是超了却没进受限状态——一个不会自己恢复的错。
     *
     * <p>已经 {@code complete} 的目标只记数不变状态：那是终态，
     * 把它改成"额度用完"等于把一件做完的事重新打开。
     *
     * @param tokens 这一轮模型报上来的 token。拿不到传 0——记 0 至少看得出这一项没数
     * @return 记完之后的这一行；空表示没有目标、或没写进去。调用方不需要它也能继续回答
     */
    Optional<SessionGoal> addTokens(String sessionKey, long tokens);

    /** 删掉这一行。{@code /goal clear} 用。@return 真的删掉了一行 */
    boolean clear(String sessionKey);
}
