package io.github.claw4j.agent.turn;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * 模型点名了一个「这一轮没披露」的工具时的占位回调（2026-11-28）。
 *
 * <p>为什么需要：{@code DefaultToolCallingManager} 在请求的 callbacks 里找不到这个名字就
 * {@code throw new IllegalStateException("No ToolCallback found for tool name: …")}，
 * 整轮 500、用户拿到空回答。实测现场：问「examples 里有没有提到 refund」时模型怀疑
 * {@code Grep} 跳过点目录，直接发了一个 {@code Bash}——它并不在这轮的清单里。
 *
 * <p>为什么是占位而不是真工具：把名字解析成真工具等于绕过披露与准入闸（ADR-0039），
 * {@code Bash} 这类"对什么话题都能凑合"的工具会重新变成"随手能用"。所以这里只回一条**工具结果**：
 * 名字不在清单里、没有执行、要就自己写 query 去检索一次。模型下一轮据此改道，协议也仍然合法
 * （assistant 的 {@code tool_calls} 与 tool 的响应一一对应）。
 *
 * <p>它不是「兜住错误」而是「把错误变成可执行的信息」：模型拿到的不是异常，是一句能照做的话。
 */
final class UndisclosedToolFallback implements ToolCallbackResolver {

    /** 无状态，一个实例够用。 */
    static final UndisclosedToolFallback INSTANCE = new UndisclosedToolFallback();

    /** 占位回调的入参 schema：任何对象都收，实际不读。 */
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private UndisclosedToolFallback() {
    }

    @Override
    public ToolCallback resolve(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        return new Undisclosed(toolName);
    }

    /** 这个名字的占位：定义齐全，调用只返回一句指路的话。 */
    private record Undisclosed(String toolName) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(this.toolName)
                    .description("占位：这个工具不在本轮清单里，不会执行任何动作。")
                    .inputSchema(EMPTY_SCHEMA)
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            return "工具「" + this.toolName + "」不在这一轮的工具清单里，没有执行任何动作。\n"
                    + "如果确实需要它：把你真正要办的事写成一条 query，调用 toolSearchTool 检索一次，"
                    + "拿到之后再按名字调用它。\n"
                    + "检索回来还是没有（或者你这边没有 toolSearchTool 这个工具），就换一条路，"
                    + "或者直接说明做不到；不要重复写这个名字。";
        }
    }
}
