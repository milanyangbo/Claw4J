package io.github.claw4j.tools.mcp.stdio;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.tools.mcp.McpConnection;
import io.github.claw4j.tools.mcp.McpServerEndpoint;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨进程端点的参数与故障方向。
 *
 * <p>这一批<b>不需要真的有一个 MCP server</b>也能跑：钉的是"配错了必须早点红"。
 * 对一个跨进程的 server 来说，配置错误的代价特别高——命令写错的表现是
 * "连接超时"或"工具清单为空"，而这两种表现都可以被误读成"这个 server 没有工具"。
 *
 * <p>真正连一次外部 CLI 的那条在 {@code RealCliMcpEndToEndTest}（{@code real-cli} tag）：
 * 它依赖本机装了 python3 与 {@code gh}/{@code himalaya}，按票面要求不进 CI。
 */
class ProcessStdioMcpEndpointTest {

    @Test
    @DisplayName("命令为空立刻失败：没有要起的程序就没有 server")
    void 命令为空即失败() {
        assertThatThrownBy(() -> ProcessStdioMcpEndpoint.of("x", "  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    @DisplayName("参数里有空项立刻失败：空参数会被当成少了一个参数，而报错在进程那一侧")
    void 参数里有空项即失败() {
        assertThatThrownBy(() -> ProcessStdioMcpEndpoint.of("x", "python3", List.of("-c", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空项");
    }

    @Test
    @DisplayName("命令与参数是分开给的，不是拼成一条命令")
    void 命令与参数分开保存() {
        ProcessStdioMcpEndpoint endpoint = ProcessStdioMcpEndpoint.of("gh", "python3",
                List.of("server.py", "--read-only"));

        assertThat(endpoint.commandLine()).isEqualTo("python3 server.py --read-only");
    }

    /** 环境不给就是空的：应用的模型 key、数据库口令不会漏到子进程里。 */
    @Test
    @DisplayName("不给环境时子进程拿不到应用进程的环境")
    void 环境默认为空() {
        ProcessStdioMcpEndpoint endpoint = ProcessStdioMcpEndpoint.of("x", "python3", List.of("s.py"));

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.endpoint(ToolPermission.read())).isInstanceOf(McpServerEndpoint.class);
    }

    @Test
    @DisplayName("openTransport 每次给一条新传输，重连拿到的是干净的连接")
    void 每次开一条新传输() {
        ProcessStdioMcpEndpoint endpoint = ProcessStdioMcpEndpoint.of("x", "python3", List.of("s.py"));

        McpClientTransport first = endpoint.openTransport();
        McpClientTransport second = endpoint.openTransport();

        assertThat(first).isNotSameAs(second);
    }

    /**
     * 命令不存在：必须在连的时候失败，而不是"连上了但一个工具都没有"。
     *
     * <p>后者正是 02 号票的 fail-fast 要挡的姿势——{@code McpToolProvider} 判的是
     * "清单为空即失败"，而如果连不上也表现成清单为空，那两种故障就混成一种了。
     * 这条断言盯的是"连不上"走的是抛异常这条路。
     */
    @Test
    @DisplayName("命令不存在时连接失败，而不是拿到一个空的工具清单")
    void 命令不存在时连接失败() {
        ProcessStdioMcpEndpoint missing = ProcessStdioMcpEndpoint.of("missing",
                "claw4j-no-such-command-2026", List.of(), Map.of("PATH", "/usr/bin:/bin"));
        // 短超时是为了让这条断言不必等满生产的 30 秒：等的是"连不上"，不是"慢"
        McpConnection connection = new McpConnection(missing.endpoint(ToolPermission.read()),
                Duration.ofSeconds(2));

        assertThatThrownBy(connection::listTools).isInstanceOf(RuntimeException.class);

        connection.close();
    }
}
