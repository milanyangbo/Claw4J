package io.github.claw4j.capability.exec;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Elevation;
import io.github.claw4j.domain.principal.Principal;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;
import java.util.Objects;

/**
 * 给 exec 这三个工具绑一层调用上下文：这次调用是谁在问、属于哪段对话（票 05）。
 *
 * <p>为什么包在工具回调上：{@code ToolContext} 是<b>每一次调用</b>的，而判定发生在后端的
 * {@code run} 里，那里拿不到它。装配期只有一个后端实例，做不到"每轮一个"；回调与后端在同一次
 * 同步调用里，也没有流式换线程的问题。所以上下文由这一层放进 {@link ExecCallContext}，
 * 端口再从那里读。
 *
 * <p>单参重载不绑：那条路上框架没给上下文，绑一份空的等于告诉端口"这个人没有名字"，
 * 而端口该知道的正是"没人告诉我这是谁"。
 *
 * @see ExecCallContext
 */
public final class ExecContextToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    /**
     * 包一层。
     *
     * @param delegate 被包的工具，必填
     */
    public ExecContextToolCallback(ToolCallback delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return ExecCallContext.with(contextOf(toolContext), () -> delegate.call(toolInput, toolContext));
    }

    /**
     * 从框架的工具上下文里取这几样。
     *
     * <p>身份取不到时整份上下文为 {@code null}，而不是拼一份只有会话的：审批要回答"批给谁"，
     * 没有答案时那条请求本来就不该存在。
     *
     * @param toolContext 框架给的上下文，可以为 {@code null}
     * @return 这次调用的上下文；没有身份时为 {@code null}
     */
    private static ExecCallContext contextOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Map<String, Object> values = toolContext.getContext();
        if (!(values.get(ContextKeys.PRINCIPAL) instanceof Principal principal)
                || principal.userId() == null || principal.userId().isBlank()) {
            return null;
        }
        return new ExecCallContext(principal,
                text(values, ChatMemory.CONVERSATION_ID),
                text(values, ContextKeys.CHANNEL),
                text(values, ContextKeys.CHAT_REF),
                text(values, ContextKeys.RUN_ID),
                // 提权档由对话那一层写进来（/elevated）；读不出来就是不提权，不猜。
                Elevation.parse(text(values, ContextKeys.ELEVATION)).orElse(Elevation.OFF));
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
