package io.github.claw4j.capability.sandbox;

/**
 * 一段会话在沙箱这件事上的身份：是不是"主对话"。
 *
 * <p>只有两个取值，因为 {@code mode=non-main} 要回答的只有一个问题：这一段算不算主对话。
 * 分得更细（子 Agent、定时任务、通道代答各一个）会把一个二值判断变成一张表，而 {@code non-main}
 * 的语义本来就是"主对话之外的一切"。
 *
 * <p>取值由调用方给：脚本层与 exec 层都拿不到"这一轮是谁在问"，那一层在 Agent 侧。
 * 所以这个枚举现在只有 {@link #MAIN} / {@link #OTHER} 两个名字与一条判定函数，
 * 真正的按会话区分要等票 06（它要接第 9 层）。
 */
public enum SandboxSession {

    /** 人直接问、直接看回答的那一段。 */
    MAIN,

    /** 其余：子 Agent、定时任务、通道代答。 */
    OTHER
}
