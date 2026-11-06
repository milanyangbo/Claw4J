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
 * <p>把它跟 {@code examples/mcp/*.py} 放在一起看就明白了：**接一个 MCP server 与语言无关**。
 * 三个 Python server 包的是 {@code gh} / {@code himalaya}，这个包的是几个 Java 方法；
 * 对宿主来说它们完全一样——都是"起一个进程，说 JSON-RPC，交出工具清单"。
 *
 * <p>用法（宿主侧照抄这条命令即可）：
 * <pre>{@code
 * java -jar claw4j-example-mcp-server/target/claw4j-example-mcp-server-0.1.0-SNAPSHOT.jar
 * }</pre>
 *
 * <p><b>三条与 stdio 有关的硬约束</b>，写在这里因为它们不报错、只表现为"连不上"：
 * <ol>
 *   <li><b>标准输出是协议通道</b>。这里一个字都不能打印——日志一律走 stderr，
 *       否则一帧 JSON 中间插进一行日志，客户端解析失败，看起来像 server 崩了。</li>
 *   <li><b>别在 {@code build()} 之后调 {@code System.exit}，也别关掉 stdin 之后继续干活</b>。
 *       这一点是实测出来的、和直觉相反：{@code main} 返回之后进程<b>不会</b>退出，
 *       因为传输用的线程不是守护线程。客户端关掉 stdin，那些线程自己收尾，进程随之结束——
 *       "什么时候停"因此由客户端说了算，正是要的形状。这里刻意不写显式等待：
 *       多一个 {@code await()} 就等于多一条测试看不见的、决定进程寿命的依赖。
 *       （这条由 {@code JavaMcpServerProcessTest} 盯着：关掉客户端后子进程必须真的没了。）</li>
 *   <li><b>工具清单为空等于失败</b>（ADR-0002 那条语义的另一半）：连接是通的、协议握手成功，
 *       只是它一件能力都没有。那种状态不该靠调用方去发现。</li>
 * </ol>
 */
public final class McpServerApplication {

    private McpServerApplication() {
    }

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
