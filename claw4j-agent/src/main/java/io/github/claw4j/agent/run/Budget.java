package io.github.claw4j.agent.run;

/**
 * 一个 run 已经用掉的量：工具调用次数、token、耗时三项（A04）。
 *
 * <p><b>为什么是三项而不是一项</b>：只数工具调用，"带上整张上下文的一次调用"与
 * "查一个数的一次调用"算成一样贵；只数 token，一个不调模型、只在工具里打转的循环
 * 就完全不花钱；只看耗时，一个便宜但绕圈的循环会一直绕下去。
 * 三项里任何一项先撞线都算预算用尽——它们量的是同一件事的三个面：
 * 调用次数管"别绕圈子"，token 管"别烧钱"，耗时管"别卡住"。
 *
 * <p><b>耗时是"各轮实际跑的时间之和"，不是"开 run 到现在"</b>：
 * 一件等审批等了两天的事不该因为等待就把预算耗光，而等待期间没有任何一轮在跑。
 * 于是这一项天然不含"停在那里等人"那一段，不需要为它专门加一条例外。
 *
 * @param toolCalls 已经执行的工具调用次数
 * @param tokens    模型报上来的 token 总数。拿不到就记 0，不拿估算值冒充
 * @param millis    各轮耗时的累计毫秒数
 */
public record Budget(int toolCalls, long tokens, long millis) {

    /** 什么都没用。 */
    public static final Budget NONE = new Budget(0, 0L, 0L);

    public Budget {
        toolCalls = Math.max(toolCalls, 0);
        tokens = Math.max(tokens, 0L);
        millis = Math.max(millis, 0L);
    }

    /** 累加一笔。没有"减一笔"：已经烧掉的量退不回来。 */
    public Budget plus(int toolCalls, long tokens, long millis) {
        return new Budget(this.toolCalls + toolCalls, this.tokens + tokens, this.millis + millis);
    }
}
