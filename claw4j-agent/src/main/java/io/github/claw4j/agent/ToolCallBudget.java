package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 一轮对话的工具调用预算。
 *
 * <p><b>为什么自己定数而不是用框架默认值</b>：Spring AI 2.0.2 的
 * {@code DefaultToolCallingManager} 默认给的是每工具 40 次、整轮 150 次
 * （{@code DEFAULT_MAX_CALLS_PER_TOOL} / {@code DEFAULT_MAX_TOTAL_TOOL_CALLS}）。
 * 那个默认值是为"分页取数、反复重试"这类合法循环设计的，
 * 而 Text-to-SQL 的自纠错每多一次就是一次模型往返加一次数据库往返，
 * 40 次足够把一次提问烧成几百块 token 也答不出来。3 次的口径是：
 * 正常一次查到，留两次给自纠错；第三次还错，说明模型没读懂错误原因，
 * 再给机会也只是继续烧。
 *
 * <p><b>超限的行为必须是 THROW</b>：{@code ToolCallingAdvisor} 会接住
 * {@code ToolCallLimitExceededException}，把它变成一代回答直接给客户端，
 * 不再回头问模型。另一种行为 {@code RETURN_ERROR_RESPONSE} 是把"超限"当成
 * 工具结果喂回模型，模型会再试一次——那就是又一轮调用，上限等于没设。
 *
 * <p><b>为什么写在代码里而不是 yml</b>：{@code spring.ai.tools.limits.*}
 * 到不了我们这条链上。我们是用 {@code ChatClient.builder(chatModel, ...)} 自己建客户端的，
 * 五参重载里传进去的 {@code ToolCallingManager} 才是生效的那个，
 * 而自动配置的那个 manager 只有在使用 Boot 注入的 {@code ChatClient.Builder} bean 时才会被用上。
 * 等到哪天改成注入 bean，这两个数字会静默退回 40/150——所以这处有单测钉着
 * （见对话主链路那份测试的"一直写错时在上限处截断"）。
 */
public final class ToolCallBudget {

    /** 同一个工具在一轮里最多被调用几次（含第一次尝试与后续自纠错）。 */
    public static final int MAX_CALLS_PER_TOOL = 3;

    /** 一轮里所有工具调用的总上限。 */
    public static final int MAX_TOTAL_CALLS = 6;

    private ToolCallBudget() {
    }

    public static ToolCallingManager toolCallingManager(ObservationRegistry observationRegistry) {
        return ToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .maxCallsPerTool(MAX_CALLS_PER_TOOL)
                .maxTotalToolCalls(MAX_TOTAL_CALLS)
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                .build();
    }
}
