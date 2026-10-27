package io.github.claw4j.agent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 这一轮对话里已经执行过哪些工具。
 *
 * <p><b>为什么需要它</b>：语义缓存要判断"这一轮有没有副作用"，依据是**实际调用过的工具集合**，
 * 不是问题文本。而工具执行发生在 {@code ToolCallingManager} 里，那里既不是 Advisor 上下文、
 * 也不是返回值的一部分——工具循环结束时，最终那个 {@code ChatResponse} 已经不带工具调用了。
 * 除了在这里记一笔，没有别的地方知道"刚才调过 {@code draft_script}"。
 *
 * <p><b>为什么是 ThreadLocal 而不是塞进上下文</b>：工具回调能拿到的只有 {@code ToolContext}，
 * 而 Advisor 能拿到的是 {@code ChatClientRequest.context()}，这两份 Map 不互通
 * （{@code ChatClientAgentPort} 现在两边各放一份 Principal，正是因为这个）。
 * 同步链上两者跑在同一个线程，用 ThreadLocal 是最短的桥。
 *
 * <p><b>代价要记账</b>：流式链上工具执行可能换线程，那时读到的会是空集合。
 * 所以缓存**只在同步路径写**（见 {@code CacheAdvisor}），空集合在那边不会被误信为"没有副作用"。
 *
 * <p>写入点是 {@code MeteredToolCallback}——那是所有工具的唯一入口，父 Agent 与子 Agent
 * 都从那里过。放在别处就必然有某条路径漏记。
 */
public final class ToolCallLog {

    private static final ThreadLocal<Set<String>> CALLS = ThreadLocal.withInitial(LinkedHashSet::new);

    private ToolCallLog() {
    }

    /** 记一次工具调用。重名的只留一条：判断副作用只关心"调用过哪些"，不关心次数。 */
    public static void record(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            CALLS.get().add(toolName);
        }
    }

    /** 这一轮调用过的工具名（副本）。没有调用过就是空集合，不是 null。 */
    public static Set<String> current() {
        return Set.copyOf(CALLS.get());
    }

    /**
     * 开始新的一轮，或者这一轮已经处理完。
     *
     * <p>不调用它的后果是**线程池复用导致跨请求串味**：第 N 个请求看到的"调用过 draft_script"
     * 其实是第 N-1 个请求留下的。表现是"有些本该缓存的问答没缓存"，比这更糟的是反过来——
     * 所以缓存写入前必须问一次当前集合，而不是读一个"可能是上一轮的"。
     */
    public static void clear() {
        CALLS.remove();
    }
}
