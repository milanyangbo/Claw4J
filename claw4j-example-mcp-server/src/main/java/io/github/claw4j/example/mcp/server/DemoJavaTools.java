package io.github.claw4j.example.mcp.server;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.time.Clock;
import java.time.Instant;

/**
 * 这个 MCP server 导出的工具。写法与 Claw4J 内部的 Java 工具一样，{@code @Tool} 与
 * {@code @McpTool} 都是「一个方法 + 一段描述 + 参数说明」，差别只在它由谁宿主：宿主是本进程时
 * {@code MethodToolCallbackProvider} 把它变成 {@code ToolCallback}；宿主是另一个进程（这里就是）
 * 时 {@code SyncMcpToolProvider} 把它变成 MCP 的 tool spec，对面通过 JSON-RPC 调用。
 *
 * <p>三个工具都不碰业务、不碰网络、不读文件：它们用来验协议与进程边界。
 * {@link #processId} 是其中最要紧的一个，它返回 server 进程的 pid，于是「这是另一个进程」
 * 可以被断言。
 */
public final class DemoJavaTools {

    private final Clock clock;

    /** 用系统时钟，导出的时间就是 server 进程此刻的时间。 */
    public DemoJavaTools() {
        this(Clock.systemUTC());
    }

    /** 测试用：把时间钉住，才能断言返回的确实是那一个时刻。 */
    DemoJavaTools(Clock clock) {
        this.clock = clock;
    }

    /**
     * 把给定的一段文本原样返回，用来验证跨进程的往返没有丢字。
     *
     * @param text 要原样带回来的文本
     * @return 同一个字符串
     */
    @McpTool(name = "echo", description = "把给定的一段文本原样返回，用来验证跨进程的往返没有丢字")
    public String echo(@McpToolParam(description = "要原样带回来的文本") String text) {
        return text;
    }

    /**
     * 返回 server 进程当前的 UTC 时间，用来验证调用结果真的来自远方。
     *
     * @return ISO-8601 格式的时刻
     */
    @McpTool(name = "serverTime", description = "返回 server 进程当前的 UTC 时间（ISO-8601），用来验证调用结果真的来自远方")
    public String serverTime() {
        return Instant.now(clock).toString();
    }

    /**
     * 返回 server 进程的 pid，调用方拿它跟自己的 pid 比就能看出这是不是一个独立进程。
     *
     * @return 当前进程的 pid
     */
    @McpTool(name = "processId", description = "返回 server 进程的 pid。调用方拿它跟自己的 pid 比，就能看出这是不是一个独立进程")
    public long processId() {
        return ProcessHandle.current().pid();
    }
}
