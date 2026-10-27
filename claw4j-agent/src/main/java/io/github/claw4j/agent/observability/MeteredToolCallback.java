package io.github.claw4j.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * 给一个工具加计量：被调了几次、成没成。
 *
 * <p><b>为什么在 {@code ToolCallback} 上，而不是在 {@code ToolCallingManager} 上</b>：
 * 一次 {@code executeToolCalls} 可能执行零个、一个或多个工具调用（模型一次要三个是常事），
 * 计在 manager 上数的是"轮次"，不是"工具调用"。
 * 而"哪个工具被问得多"恰恰是按工具算才有用——它决定下一个该优化谁、哪个该收权限。
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

    public MeteredToolCallback(ToolCallback delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
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
        Counter.builder(TOOL_CALLS)
                .description("工具调用次数")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
