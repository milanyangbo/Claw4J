package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 一轮对话的工具调用熔断（{@code claw4j.agent.tool-call-limit}）。
 *
 * <p>它是止损，不是额度（ADR-0027 决定五）。额度回答「这件事能花多少」，挂在目标上
 * （{@code goal.token_budget}），用完进 {@code budget_limited}，由人决定要不要开新窗口；
 * 熔断回答「这一轮绕了多少次」，到点就停，不改任何持久状态。合成一个数，运维看到「停了」
 * 分不清该加预算还是该让模型收敛。
 *
 * <p>需要它是因为没有 per-turn 超时。OpenClaw 用 {@code agents.defaults.timeoutSeconds} 给整轮一个
 * 期限；靠墙钟要等几十分钟才停，而一个只在工具里打转的循环，靠次数三跳就停。
 *
 * <p>单工具 3 次不可配，整轮默认 6 次可配。3 的口径：正常一次查到，留两次给自纠错，
 * 第三次还错说明模型没读懂错误原因，再给机会也只是继续烧。不用框架默认值（Spring AI 2.0.2 的
 * {@code DefaultToolCallingManager.DEFAULT_MAX_CALLS_PER_TOOL} 是 40、
 * {@code DEFAULT_MAX_TOTAL_TOOL_CALLS} 是 150）：那是为分页取数、反复重试这类合法循环留的，
 * 而 Text-to-SQL 的自纠错每多一次就是一次模型往返加一次数据库往返。
 *
 * <p>超限必须是 {@code THROW}：{@code ToolCallingAdvisor} 会接住
 * {@code ToolCallLimitExceededException}，把当前回答直接交给客户端
 * （Spring AI {@code ToolCallingAdvisor}:184）。{@code RETURN_ERROR_RESPONSE} 把「超限」
 * 当工具结果喂回模型，模型再试一次，上限等于没设。
 *
 * <p>数字写在代码里而不是 yml：{@code spring.ai.tools.limits.*} 到不了这条链上。
 * 客户端由 {@code ChatClient.builder(chatModel, ...)} 自建，只有显式传入的
 * {@code ToolCallingManager} 生效；自动配置的那个只在使用 Boot 注入的 {@code ChatClient.Builder}
 * bean 时才被用上。哪天改成注入 bean，这两个数会静默退回 40/150，所以有单测钉着。
 */
public final class ToolCallBudget {

    /** 同一个工具在一轮里最多被调用几次，含第一次尝试与后续自纠错。 */
    public static final int MAX_CALLS_PER_TOOL = 3;

    /**
     * 一轮里所有工具调用总上限的默认值，可在 yml 里用 {@code claw4j.agent.tool-call-limit} 覆盖。
     *
     * <p>取 6：正常一件事两三次工具就够，留几次给自纠错；第六次还在绕，说明模型没读懂错误原因。
     */
    public static final int MAX_TOTAL_CALLS = 6;

    private ToolCallBudget() {
    }

    /**
     * 带熔断的工具循环 manager，供 {@code ChatClient.builder} 显式传入。
     *
     * @param observationRegistry 观测注册表，透传给框架的 manager
     * @param totalCalls 整轮上限，来自 {@code claw4j.agent.tool-call-limit}；非正数按
     *     {@link #MAX_TOTAL_CALLS} 处理。写 0 不是「不限」——那会让一轮无限绕下去，
     *     真要放开就写一个大数
     * @return 生效的 {@code ToolCallingManager}
     */
    public static ToolCallingManager toolCallingManager(ObservationRegistry observationRegistry,
                                                        int totalCalls) {
        return ToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .maxCallsPerTool(MAX_CALLS_PER_TOOL)
                .maxTotalToolCalls(totalCalls <= 0 ? MAX_TOTAL_CALLS : totalCalls)
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                .build();
    }
}
