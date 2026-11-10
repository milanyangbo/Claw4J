package io.github.claw4j.example.mcp.server;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import java.util.List;
/**
 * 一个独立运行的 Java MCP server：标准输入输出上说的是 MCP 协议，工具来自 {@link DemoJavaTools}。
 *
 * <p>与 {@code examples/mcp/*.py} 并排看就明白，接一个 MCP server 与语言无关。三个 Python server
 * 包的是 {@code gh} 与 {@code himalaya}，这个包的是几个 Java 方法；对宿主来说它们一样，
 * 都是起一个进程、说 JSON-RPC、交出工具清单。
 *
 * <p>用法，宿主侧照抄这条命令即可：
 * <pre>{@code
 * java -jar claw4j-example-mcp-server/target/claw4j-example-mcp-server-0.1.0-SNAPSHOT.jar
 * }</pre>
 *
 * <p>三条与 stdio 有关的硬约束，写在这里因为它们不报错、只表现为连不上：标准输出是协议通道，
 * 这里一个字都不能打印，日志一律走 stderr，否则一帧 JSON 中间插进一行日志，客户端解析失败；
 * 别在 {@code build()} 之后调 {@code System.exit}，也别关掉 stdin 之后继续干活，{@code main}
 * 返回之后进程不会退出，因为传输线程不是守护线程，客户端关掉 stdin 后那些线程收尾、进程随之结束，
 * 什么时候停因此由客户端说了算（{@code JavaMcpServerProcessTest} 盯着这一条）；工具清单为空
 * 等于失败，连接是通的、握手成功，只是它一件能力都没有，那种状态不该靠调用方去发现。
 */
public final class McpServerApplication {

    private McpServerApplication() {
    }

    /**
     * 在标准输入输出上起 MCP server，等客户端断开后自行收尾。
     *
     * @param args 未使用；宿主按上面的命令原样启动即可
     * @throws InterruptedException 传输线程被中断
     */
    public static void main(String[] args) throws InterruptedException {
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in, System.out);
        var toolSpecifications = new SyncMcpToolProvider(List.of(new DemoJavaTools())).getToolSpecifications();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("claw4j-java-demo", "1.0.0")
                .tools(toolSpecifications)
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully, "mcp-server-shutdown"));

        // 到这里就可以返回了：传输的非守护线程让进程继续活着，客户端关掉 stdin 之后它们收尾。
        // 不要在这里加"等一等再退出"：main 返回 JVM 并不会退出，那种等待是一行测不到的假设。
    }
}
