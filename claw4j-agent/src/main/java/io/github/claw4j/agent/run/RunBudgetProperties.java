package io.github.claw4j.agent.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 一次 run 的三项上限（A04）。键在 {@code claw4j.run.budget} 下。
 *
 * <p><b>工具调用取 6，与一轮的上限同数</b>：run 不是"再给几次机会"的借口——
 * 它只是让同一个上限跨轮生效。一件要调 20 次工具的事不是"该把上限调大"，
 * 是"该把它拆成几件事"，或者该用委派把中间过程隔离掉（ADR-0012）。
 * 而"每轮重置"的旧行为等于没有上限：三轮各调 6 次，一件小事能烧掉 18 次调用。
 *
 * <p><b>token 取 10 万</b>：一次带工具定义的提问大约几千 token，
 * 10 万大致是"二十轮正常对话"的量，够一件真事做完，不够一个绕圈的循环一直绕。
 *
 * <p><b>耗时取 900 秒（累计的各轮实际耗时，不含等待）</b>：一件交互着做的事
 * 不该跑过一刻钟；超过之后继续跑的产物多半也没人等了。
 *
 * <p><b>三个数都可以在 yml 里改，但改它们改变的是行为而不是容量</b>：
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
