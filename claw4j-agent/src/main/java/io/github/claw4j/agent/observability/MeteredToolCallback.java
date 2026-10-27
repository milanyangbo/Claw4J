package io.github.claw4j.agent.observability;

import io.github.claw4j.agent.ToolCallLog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * 给一个工具加计量：被调了几次、成没成；顺带记下"这一轮调用过它"。
 *
 * <p><b>为什么在 {@code ToolCallback} 上，而不是在 {@code ToolCallingManager} 上</b>：
 * 一次 {@code executeToolCalls} 可能执行零个、一个或多个工具调用（模型一次要三个是常事），
 * 计在 manager 上数的是"轮次"，不是"工具调用"。
 * 而"哪个工具被问得多"恰恰是按工具算才有用——它决定下一个该优化谁、哪个该收权限。
 *
 * <p><b>它做两件事，因为这两个事实同源</b>：计数的前提是"这次调用真的发生了"，
 * 而 {@link ToolCallLog} 要记的也正是同一件事。分成两个包装类会多一层对象，
 * 还会让"只包了计量那层"的路径悄悄漏记——这个仓库已经因为"某条路径没被包上"
 * 出过一次问题（工具清单只收 provider 不收单个 callback）。
 *
 * <p><b>没有 registry 时照样包</b>：记录"这一轮调过哪些工具"是语义缓存判断副作用的依据，
 * 与"有没有装 actuator"无关。装不装只影响计数报不报得出去。
 *
 * <p><b>这里看不到 {@code denied}</b>：鉴权拒绝发生在 Advisor 那一层，工具根本没被执行，
 * 所以走不到这个 {@code call} 里。那个计数只能在鉴权 Advisor 上报（见
 * {@code AuthorizationAdvisor}）。两处各报一半，是因为各自是唯一知道真相的地方。
 *
 * <p>两个 {@code call} 重载都覆写：带 {@code ToolContext} 的那个在 Spring AI 里是 default 方法，
 * 但它未必转调单参那个（不同实现不一样）。只包一个的话，走另一个重载的调用就漏计了。
 */
public class MeteredToolCallback implements ToolCallback {

    public static final String TOOL_CALLS = "claw4j.tool.calls";

    private final ToolCallback delegate;
    private final String toolName;
    private final MeterRegistry registry;

    /** @param registry 为 null 表示调用方没装 actuator：照常记录调用，只是没有指标可报 */
    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = registry;
        this.toolName = delegate.getToolDefinition().name();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        ToolCallLog.record(toolName);
        try {
            String result = delegate.call(toolInput);
            count("ok");
            return result;
        }
        catch (RuntimeException failure) {
            count("error");
            throw failure;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        ToolCallLog.record(toolName);
        try {
            String result = delegate.call(toolInput, toolContext);
            count("ok");
            return result;
        }
        catch (RuntimeException failure) {
            count("error");
            throw failure;
        }
    }

    private void count(String outcome) {
        if (registry == null) {
            return;
        }
        Counter.builder(TOOL_CALLS)
                .description("工具调用次数")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
