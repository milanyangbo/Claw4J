package io.github.claw4j.tools.mcp.stdio;

import io.github.claw4j.tools.mcp.McpServerEndpoint;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Java 自实现的 stdio MCP server，**跑在本进程里**：一套管道当 stdin/stdout，
 * 工具对象由接入方给（{@code @McpTool} 标注的普通对象），Java 侧只见工具协议。
 *
 * <p>它同时是测试替身与演示实现：不依赖 Docker、不依赖 Python，
 * 于是"连接建立时工具清单为空即 fail-fast"与"会话丢失重新初始化"这两条
 * ADR-0008 的必测能进 CI——否则它们会退化成"只在有 Docker 的机器上测过"。
 *
 * <p><b>什么不是它保证的</b>：它没有跨 OS 进程。真实部署里 server 是另一个进程
 * （07 号票要把 {@code gh} 与 {@code himalaya} 包成 MCP server），
 * 那时换掉的是 {@link McpServerEndpoint#openTransport()} 给出来的传输实现，
 * 本类之上的连接生命周期代码一行都不用改——这条接缝是本票刻意留下的。
 *
 * <p>每次 {@link #openTransport()} 都新建一对管道并起一个新 server 会话，
 * 因此"重新初始化连接"拿到的是一条干净的连接，而不是去摇一棵已经死掉的树。
 */
public final class InProcessStdioMcpServer implements AutoCloseable {

    /** 管道缓冲给大一点：一帧 JSON 可能带上整个工具结果，1KB 的默认值会频繁阻塞。 */
    private static final int PIPE_SIZE = 1 << 16;

    private final String name;

    private final List<Object> toolObjects;

    private final List<McpSyncServer> servers = new ArrayList<>();

    private InProcessStdioMcpServer(String name, List<Object> toolObjects) {
        this.name = name;
        this.toolObjects = List.copyOf(toolObjects);
    }

    /** @param toolObjects {@code @McpTool} 标注的工具对象；传空即"这个 server 不导出任何工具" */
    public static InProcessStdioMcpServer of(String name, Object... toolObjects) {
        Objects.requireNonNull(name, "name");
        return new InProcessStdioMcpServer(name, List.of(toolObjects));
    }

    public String name() {
        return name;
    }

    /**
     * 开一条新连接：一对管道 + 一个新的 server 会话。
     *
     * @return 给 MCP client 用的传输实现
     */
    public McpClientTransport openTransport() {
        try {
            PipedInputStream clientReads = new PipedInputStream(PIPE_SIZE);
            OutputStream serverWrites = new PipedOutputStream(clientReads);
            PipedInputStream serverReads = new PipedInputStream(PIPE_SIZE);
            OutputStream clientWrites = new PipedOutputStream(serverReads);

            McpSyncServer server = start(serverReads, serverWrites);
            synchronized (servers) {
                servers.add(server);
            }
            return new PipedStdioClientTransport(clientReads, clientWrites);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("起进程内 MCP server 失败：" + name, failure);
        }
    }

    private McpSyncServer start(InputStream inbound, OutputStream outbound) {
        return McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper(), inbound, outbound))
                .serverInfo(name, "1.0.0")
                .tools(new SyncMcpToolProvider(toolObjects).getToolSpecifications())
                .build();
    }

    @Override
    public void close() {
        List<McpSyncServer> snapshot;
        synchronized (servers) {
            snapshot = new ArrayList<>(servers);
            servers.clear();
        }
        for (McpSyncServer server : snapshot) {
            try {
                server.close();
            }
            catch (RuntimeException ignored) {
                // 关不掉就算了：这是测试与演示用的 server，进程结束时会一起没
            }
        }
    }
}
