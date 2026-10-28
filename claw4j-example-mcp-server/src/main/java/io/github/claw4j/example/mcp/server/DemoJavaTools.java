package io.github.claw4j.example.mcp.server;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.time.Clock;
import java.time.Instant;

/**
 * 这个 MCP server 导出的工具。写法和 Claw4J 内部的 Java 工具**一模一样**（{@code @Tool} 与
 * {@code @McpTool} 都是"一个方法 + 一段描述 + 参数说明"），差别只在它由谁宿主：
 *
 * <ul>
 *   <li>宿主是本进程 → {@code MethodToolCallbackProvider} 把它变成 {@code ToolCallback}；</li>
 *   <li>宿主是另一个进程（就是这里）→ {@code SyncMcpToolProvider} 把它变成 MCP 的 tool spec，
 *       对面通过 JSON-RPC 调用。</li>
 * </ul>
 *
 * <p>这三个工具刻意都不碰业务、不碰网络、不读文件：它们是拿来验协议与进程边界的，
 * 不是拿来演示"能力有多强"。{@link #processId} 是其中最要紧的一个——
 * 它返回 server 进程的 pid，于是"这是另一个进程"这句话可以被断言，而不是靠嘴说。
 */
public final class DemoJavaTools {

    private final Clock clock;

    public DemoJavaTools() {
        this(Clock.systemUTC());
    }

    /** 测试用：把时间钉住，才能断言"返回的确实是那一个时刻"。 */
    DemoJavaTools(Clock clock) {
        this.clock = clock;
    }

    @McpTool(name = "echo", description = "把给定的一段文本原样返回，用来验证跨进程的往返没有丢字")
    public String echo(@McpToolParam(description = "要原样带回来的文本") String text) {
        return text;
    }

    @McpTool(name = "serverTime", description = "返回 server 进程当前的 UTC 时间（ISO-8601），用来验证调用结果真的来自远方")
    public String serverTime() {
        return Instant.now(clock).toString();
    }

    @McpTool(name = "processId", description = "返回 server 进程的 pid。调用方拿它跟自己的 pid 比，就能看出这是不是一个独立进程")
    public long processId() {
        return ProcessHandle.current().pid();
    }
}
