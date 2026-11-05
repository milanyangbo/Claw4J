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
 * <p><b>会话靠空闲过期，不靠"任务完成"</b>：与 OpenClaw 的 idle reset 同构
 * （{@code idleMinutes} 默认 120）。行业里没有哪个 Agent 框架去判定"用户这件事做完了"——
 * 判定不了，判错的代价是把人卡住。能判的只有"多久没动静"。
 * 所以 {@link #idleMillis()} 就是"一段会话的寿命"：连续几轮共享额度，隔了两小时再来重新开始。
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
public record RunBudgetProperties(int maxToolCalls, long maxTokens, long maxSeconds, long idleMinutes) {

    public static final int DEFAULT_MAX_TOOL_CALLS = 6;

    public static final long DEFAULT_MAX_TOKENS = 100_000L;

    public static final long DEFAULT_MAX_SECONDS = 900L;

    /** 一段会话多久没动静就算结束。OpenClaw 的 {@code idleMinutes} 默认也是 120。 */
    public static final long DEFAULT_IDLE_MINUTES = 120L;

    public RunBudgetProperties {
        // 0 与"没配"落到同一个默认值上：这两者的区别不值得单独解释一层。
        maxToolCalls = maxToolCalls <= 0 ? DEFAULT_MAX_TOOL_CALLS : maxToolCalls;
        maxTokens = maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
        maxSeconds = maxSeconds <= 0 ? DEFAULT_MAX_SECONDS : maxSeconds;
        idleMinutes = idleMinutes <= 0 ? DEFAULT_IDLE_MINUTES : idleMinutes;
    }

    /** 全部走默认值。配置绑定在键缺失时传的就是 0，两条路落到同一处。 */
    public static RunBudgetProperties defaults() {
        return new RunBudgetProperties(0, 0L, 0L, 0L);
    }

    /** 耗时上限换算成毫秒：库里记的是毫秒，yml 里写秒（没有人想写 900000）。 */
    public long maxMillis() {
        return maxSeconds * 1000L;
    }

    /** 会话的空闲窗口，毫秒。聚合用量时只算这个窗口内动过的 run。 */
    public long idleMillis() {
        return idleMinutes * 60_000L;
    }
}
