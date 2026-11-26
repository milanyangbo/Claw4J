package io.github.claw4j.agent.toolaccess;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * 换了描述的工具回调：只改"模型与索引看到的那段字"，执行一点没变（票 11）。
 *
 * <p>为什么是装饰器而不是在源头改描述：工具描述由库的 {@code @Tool(description=…)} 定死，
 * 改它要么改库、要么每个工具包一层。而这一层同时喂两处——模型的清单与工具的索引——所以只要
 * 包在收集出口，两边就是同一份字。
 *
 * <p>执行与元数据全部原样转发：{@code call} 的两个重载都要转，只转一个的话，带
 * {@code ToolContext} 的那条路会走接口的默认实现再回到 {@link #call(String)}，中间丢掉上下文
 * （审批、观测都挂在上面）。
 */
final class TranslatedToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    private final ToolDefinition definition;

    /**
     * @param delegate 原回调
     * @param chinese  译出来的那句中文
     */
    TranslatedToolCallback(ToolCallback delegate, String chinese) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        ToolDefinition original = delegate.getToolDefinition();
        this.definition = ToolDefinition.builder()
                .name(original.name())
                .description(chinese + "\n" + original.description())
                .inputSchema(original.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return this.delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return this.delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return this.delegate.call(toolInput, toolContext);
    }
}
