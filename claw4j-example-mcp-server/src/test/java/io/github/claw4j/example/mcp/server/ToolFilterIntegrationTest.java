package io.github.claw4j.example.mcp.server;

import io.github.claw4j.capability.mcp.McpConnection;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.capability.mcp.McpToolProvider;
import io.github.claw4j.domain.principal.ToolFilter;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tool-filter} 对着一个真的 MCP server 的四组配置（票 07）。
 *
 * <p>这个 server 导出三个工具：{@code runtimeSnapshot}、{@code cronNext}、{@code simulateSlowCall}。
 * 四组配置验的是同一件事的四种写法：被排掉的工具在 {@code toolNames()} 与权限登记里<b>都不存在</b>。
 *
 * <p>为什么非要真进程：进程内的替身里，server 与客户端都是我们自己的代码，筛选器写错了它也照样
 * 通过——"清单里少了那个工具"要对着别人写的端点才算数。这个 server 是独立程序、走真 HTTP，
 * 与部署里接第三方 server 是同一条路。
 *
 * <p>一个进程、四次连接：配置是客户端这一侧的，不必为每组配置各起一次 server；
 * 反过来每组的清单要对着<b>同一个</b>服务端，四组之间才有可比性。
 */
class ToolFilterIntegrationTest {

    @Test
    @DisplayName("不配 / include 两个 / exclude 一个 / 通配：四组的清单与权限登记一起变")
    void fourConfigurationsFilterTheSameServer() throws Exception {
        int port = JavaMcpServerProcessTest.freePort();
        Path log = Files.createTempFile("claw4j-mcp-tool-filter", ".log");
        Process server = new ProcessBuilder(JavaMcpServerProcessTest.javaBinary(),
                "-cp", JavaMcpServerProcessTest.serverClasspath(),
                McpServerApplication.class.getName(), "--server.port=" + port)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        try {
            JavaMcpServerProcessTest.awaitListening(port, server, log);

            assertThat(toolsWith(port, ToolFilter.none()))
                    .as("没配过滤时三个都在：配错一个字就整层消失，这条是最容易漏的基线")
                    .containsExactlyInAnyOrder("runtimeSnapshot", "cronNext", "simulateSlowCall");

            assertThat(toolsWith(port, new ToolFilter(List.of("runtimeSnapshot", "cronNext"), List.of())))
                    .as("include 是白名单：没点名的第三个不存在，不是'这一轮不给看'")
                    .containsExactlyInAnyOrder("runtimeSnapshot", "cronNext");

            assertThat(toolsWith(port, new ToolFilter(List.of(), List.of("simulateSlowCall"))))
                    .as("换一种写法，结果与上一组一致")
                    .containsExactlyInAnyOrder("runtimeSnapshot", "cronNext");

            assertThat(toolsWith(port, new ToolFilter(List.of("runtime*"), List.of())))
                    .as("* 按整串命名匹配：只命中 runtimeSnapshot，cronNext 不被带进来")
                    .containsExactly("runtimeSnapshot");
        }
        finally {
            server.destroy();
            assertThat(server.waitFor(20, TimeUnit.SECONDS))
                    .as("关掉之后子进程必须退出：孤儿进程在 CI 上表现为「那台机器越跑越慢」")
                    .isTrue();
        }
    }

    /** 用一份过滤配置接上去，返回它看到的工具名。 */
    private static List<String> toolsWith(int port, ToolFilter filter) {
        McpServerEndpoint endpoint = McpServerEndpoint.of("java-http", filter, () -> transport(port));
        try (McpToolProvider provider = new McpToolProvider(
                List.of(new McpConnection(endpoint, Duration.ofSeconds(30), Duration.ofSeconds(30))))) {
            return provider.toolNames();
        }
    }

    /**
     * 一条新的 Streamable HTTP 传输。
     *
     * <p>用 SDK 的 builder 而不是 {@code McpServerEndpoint} 的 http 工厂：那个工厂吃的是配置里的
     * 地址与句柄，而这条用例的端口是临时挑的，显式给一条更直白。
     */
    private static McpClientTransport transport(int port) {
        return HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .jsonMapper(McpJsonDefaults.getMapper())
                .build();
    }
}
