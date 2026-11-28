package io.github.claw4j.agent.turn;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 一轮对话的工具调用熔断（{@code claw4j.agent.tool-call-limit} / {@code tool-call-limit-per-tool}）。
 *
 * <p>它是止损，不是额度（ADR-0027 决定五）。额度回答「这件事能花多少」，挂在目标上
 * （{@code goal.token_budget}），用完进 {@code budget_limited}，由人决定要不要开新窗口；
 * 熔断回答「这一轮绕了多少次」，到点就停，不改任何持久状态。合成一个数，运维看到「停了」
 * 分不清该加预算还是该让模型收敛。
 *
 * <p>需要它是因为没有 per-turn 超时。OpenClaw 用 {@code agents.defaults.timeoutSeconds} 给整轮一个
 * 期限；靠墙钟要等几十分钟才停，而一个只在工具里打转的循环，靠次数三跳就停。
 *
 * <p><b>默认值 8 / 20 是 2026-11-28 从 3 / 6 提上来的。</b>依据是两次实测的中止都不是「模型在打转」：
 * 一次是模型在一条消息里发 4 个 {@code Read}（读 4 个文件），第 4 个就撞了单工具 3；另一次是派单
 * 连发 2–3 次检索。**并行批次按「每次调用」计数**，所以单工具上限必须容得下一次正常批次。
 * 提上来之后离框架默认（Spring AI 2.0.2 的 40 / 150）还差得远，仍拦得住真正的原地打转。
 *
 * <p>超限必须是 {@code THROW}：{@code ToolCallingAdvisor} 会接住
 * {@code ToolCallLimitExceededException}，把当前回答直接交给客户端
 * （Spring AI {@code ToolCallingAdvisor}:184）。{@code RETURN_ERROR_RESPONSE} 把「超限」
 * 当工具结果喂回模型，模型再试一次，上限等于没设。
 *
 * <p><b>同时接上了「点名未披露工具」的兜底</b>：模型有时会直接写出一个这一轮没披露的名字
 * （系统提示里见过的、或纯粹编的）。框架默认是抛 {@code IllegalStateException}，整轮 500、
 * 用户拿到空回答。这里挂一个 {@link UndisclosedToolFallback}，把它变成一条工具结果——
 * 「这个工具不在清单里，要就自己写 query 去检索一次」——模型下一轮能自己改道，而不是整轮作废。
 * 只接占位回调、不接真工具：解析到真工具等于绕过披露与准入闸（ADR-0039）。
 *
 * <p>数字写在代码里而不是 Spring AI 的 {@code spring.ai.tools.limits.*}：那套属性到不了这条链上。
 * 客户端由 {@code ChatClient.builder(chatModel, ...)} 自建，只有显式传入的
 * {@code ToolCallingManager} 生效；自动配置的那个只在使用 Boot 注入的 {@code ChatClient.Builder}
 * bean 时才被用上。哪天改成注入 bean，这两个数会静默退回 40/150，所以有单测钉着。
 */
public final class ToolCallBudget {

    /**
     * 同一个工具在一轮里最多被调用几次，含第一次尝试与后续自纠错。
     *
     * <p>取 8：模型会并行发批次（一次 3–4 个 {@code Read}、一次 2 个检索），按调用计数时
     * 上限必须容得下一次正常批次再加两次自纠错。
     */
    public static final int MAX_CALLS_PER_TOOL = 8;

    /**
     * 一轮里所有工具调用总上限的默认值，可在 yml 里用 {@code claw4j.agent.tool-call-limit} 覆盖。
     *
     * <p>取 20：一次多步任务（检索 → 派单 → 取数 → 读几个文件）实测要 6–10 次；
     * 留一倍余量给自纠错，同时仍然是一个能停下来的数。
     */
    public static final int MAX_TOTAL_CALLS = 20;

    private ToolCallBudget() {
    }

    /**
     * 按默认的单工具上限建 manager。
     *
     * @param observationRegistry 观测注册表，透传给框架的 manager
     * @param totalCalls 整轮上限，来自 {@code claw4j.agent.tool-call-limit}；非正数按
     *     {@link #MAX_TOTAL_CALLS} 处理
     * @return 生效的 {@code ToolCallingManager}
     */
    public static ToolCallingManager toolCallingManager(ObservationRegistry observationRegistry,
                                                        int totalCalls) {
        return toolCallingManager(observationRegistry, totalCalls, MAX_CALLS_PER_TOOL);
    }

    /**
     * 带熔断的工具循环 manager，供 {@code ChatClient.builder} 显式传入。
     *
     * @param observationRegistry 观测注册表，透传给框架的 manager
     * @param totalCalls 整轮上限，来自 {@code claw4j.agent.tool-call-limit}；非正数按
     *     {@link #MAX_TOTAL_CALLS} 处理。写 0 不是「不限」——那会让一轮无限绕下去，
     *     真要放开就写一个大数
     * @param perToolCalls 单工具上限，来自 {@code claw4j.agent.tool-call-limit-per-tool}；
     *     非正数按 {@link #MAX_CALLS_PER_TOOL} 处理
     * @return 生效的 {@code ToolCallingManager}
     */
    public static ToolCallingManager toolCallingManager(ObservationRegistry observationRegistry,
                                                        int totalCalls,
                                                        int perToolCalls) {
        return ToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .maxCallsPerTool(perToolCalls <= 0 ? MAX_CALLS_PER_TOOL : perToolCalls)
                .maxTotalToolCalls(totalCalls <= 0 ? MAX_TOTAL_CALLS : totalCalls)
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                // 模型点名一个没披露的工具时别抛异常把整轮打掉：回一条工具结果，让它自己改道。
                .toolCallbackResolver(UndisclosedToolFallback.INSTANCE)
                .resolutionFallbackEnabled(true)
                .build();
    }
}
