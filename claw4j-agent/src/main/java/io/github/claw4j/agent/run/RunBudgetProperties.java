package io.github.claw4j.agent.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 一段会话的三项上限（A04，ADR-0025 改挂会话）。键在 {@code claw4j.run.budget} 下。
 *
 * <p><b>挂会话，不挂 run</b>：run 的寿命是一轮，答完就 {@code DONE}，下一轮是新 run。
 * 按 run 记的数在每轮开头都是 0/6，"跨轮累计"从来没有发生过——
 * 三轮各调 6 次一共 18 次，全部放行。按 {@code session_id} 相同的行求和之后，
 * 同一个 6 才真的是"这段对话一共能烧多少"。
 *
 * <p><b>会话什么时候结束，不在这里定义</b>：空闲阈值曾经是本类的一个字段
 * （{@code claw4j.run.budget.idle-minutes}），那是"会话结束"的第二个定义——
 * 段（ADR-0026）把它收走了，现在唯一的口径是 {@code claw4j.session.reset.idle-minutes}。
 * 额度因为"换段"而清零，不再自己判一次空闲：两处都判的话，下一次改 idle 的人
 * 只会改一处，另一处看起来还在生效。
 *
 * <p><b>工具调用取 6</b>：一件要调 20 次工具的事不是"该把上限调大"，
 * 是"该把它拆成几件事"，或者该用委派把中间过程隔离掉（ADR-0012）。
 *
 * <p><b>token 取 10 万</b>：一次带工具定义的提问大约几千 token，
 * 10 万大致是"二十轮正常对话"的量，够一件真事做完，不够一个绕圈的循环一直绕。
 *
 * <p><b>耗时取 900 秒（累计的各轮实际耗时，不含等待）</b>：一件交互着做的事
 * 不该跑过一刻钟；超过之后继续跑的产物多半也没人等了。
 *
 * <p><b>四个数都可以在 yml 里改，但改它们改变的是行为而不是容量</b>：
 * 调大上限等于宣布"这件事可以烧更多"，而它与"模型该自己收敛"是两件事——
 * 真正该修的是让模型早点收尾的提示词与工具描述，不是这个数。
 */
@ConfigurationProperties("claw4j.run.budget")
public record RunBudgetProperties(int maxToolCalls, long maxTokens, long maxSeconds) {

    public static final int DEFAULT_MAX_TOOL_CALLS = 6;

    public static final long DEFAULT_MAX_TOKENS = 100_000L;

    public static final long DEFAULT_MAX_SECONDS = 900L;

    public RunBudgetProperties {
        // 0 与"没配"落到同一个默认值上：这两者的区别不值得单独解释一层。
        maxToolCalls = maxToolCalls <= 0 ? DEFAULT_MAX_TOOL_CALLS : maxToolCalls;
        maxTokens = maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
        maxSeconds = maxSeconds <= 0 ? DEFAULT_MAX_SECONDS : maxSeconds;
    }

    /** 全部走默认值。配置绑定在键缺失时传的就是 0，两条路落到同一处。 */
    public static RunBudgetProperties defaults() {
        return new RunBudgetProperties(0, 0L, 0L);
    }

    /** 耗时上限换算成毫秒：库里记的是毫秒，yml 里写秒（没有人想写 900000）。 */
    public long maxMillis() {
        return maxSeconds * 1000L;
    }
}
