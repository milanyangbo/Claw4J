package io.github.claw4j.capability.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.Map;
import java.util.Objects;

/**
 * 把一个 MCP 工具适配成 Spring AI 的 {@link ToolCallback}：
 * 三层能力对 Agent 呈现为同一件事，靠的就是这一层适配（ADR-0003）。
 *
 * <p>名字与参数 Schema 是唯一契约：定义直接由 {@code listTools()} 拿回来的 {@link Tool} 生成，
 * Java 侧不认识这个工具是怎么实现的。
 *
 * <p>调用不持有 client，每次向 {@link McpConnection} 要：会话丢失之后连接会被换掉，
 * 持有旧 client 的回调从此永远失败。
 *
 * <p>出错时抛 {@link ToolExecutionException}：Spring AI 的
 * {@code DefaultToolExecutionExceptionProcessor} 只认这一种异常，抛别的会一路冒泡
 * 把整通对话炸掉。而"这个外部能力现在不可用"应当变成一次失败的工具结果，
 * 让模型有机会换一条路。
 */
public final class McpToolCallback implements ToolCallback {

    /**
     * 参数解析用 MCP 自己那一套 JSON 栈（{@code McpJsonMapper}），不再引一份 Jackson：
     * 参数从 MCP 链路进来，用它的栈解析，两边对同一段文本的看法才一致。Jackson 2 与 3 共存时，
     * 最容易出的就是同一段 JSON 被两套类型系统各读一遍。
     */
    private static final TypeRef<Map<String, Object>> ARGUMENTS = new TypeRef<>() {
    };

    private final McpConnection connection;

    private final ToolDefinition definition;

    /**
     * 把一个 MCP 工具包成回调。工具定义从 {@code listTools()} 的结果直接生成。
     *
     * @param connection 这条工具所属的连接，调用时按名字向它要 client
     * @param tool server 导出的工具，名字与 inputSchema 原样透给模型
     */
    public McpToolCallback(McpConnection connection, Tool tool) {
        this.connection = Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tool, "tool");
        this.definition = McpToolUtils.createToolDefinition(tool.name(), tool);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> arguments = argumentsOf(toolInput);
        CallToolResult result = connection.callTool(CallToolRequest.builder(definition.name())
                .arguments(arguments)
                .build());
        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolExecutionException(definition,
                    new IllegalStateException("MCP 工具 " + definition.name() + " 返回了错误：" + render(result)));
        }
        return render(result);
    }

    /** 流式路径上模型给的参数可能是空的（分块送达），空参数按空对象处理而不是报错。 */
    private Map<String, Object> argumentsOf(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return McpJsonDefaults.getMapper().readValue(toolInput, ARGUMENTS);
        }
        catch (Exception failure) {
            throw new ToolExecutionException(definition, failure);
        }
    }

    /** 只取文本块：MCP 的返回内容可以是图片等，本项目目前没有需要它们的工具。 */
    private static String render(CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (Content content : result.content()) {
            if (content instanceof TextContent textContent && textContent.text() != null) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.text());
            }
        }
        return text.toString();
    }
}
