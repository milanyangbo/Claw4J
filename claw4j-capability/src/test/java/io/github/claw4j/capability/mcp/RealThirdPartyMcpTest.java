package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.McpServerProperties.Permission;
import io.github.claw4j.capability.mcp.McpServerProperties.Server;
import io.github.claw4j.capability.mcp.McpServerProperties.Transport;
import io.github.claw4j.secret.SecretResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接别人写的 MCP server，两种传输各一条：一条起子进程（npm 上的
 * {@code @modelcontextprotocol/server-everything}），一条连公网端点（DeepWiki）。
 * 这组打 {@code real-mcp} tag，默认不跑。
 *
 * <p>默认不跑的理由：stdio 那条要本机有 Node，第一次 {@code npx} 还要下载包；http 那条要能出网。
 * 换台机器就可能红，而那个红说明不了是谁的问题，留在回归基线里只会训练人忽略红灯。本机想跑：
 *
 * <pre>{@code
 * ./mvnw -Preal-cli -pl claw4j-capability test -Dtest=RealThirdPartyMcpTest
 * }</pre>
 *
 * <p>它与 {@code application-local.yml.example} 末尾那段注释着的配置是同一件事的两面：那份配置
 * 给人照着抄，这两条用例是"那样一段配置真的能连上"的证据。两边用同一套 {@link McpEndpoints} 转换，
 * 差别只在命令与地址写死在这里（读 yml 就变成测 Spring 的绑定，那是 {@code McpServersConfigTest} 的事）。
 *
 * <p>两条都走真实路径：stdio 那条调一次 {@code echo}（{@code ToolCallback} 到 JSON-RPC 帧、子进程、
 * 再原样回来），http 那条列工具清单并核对权限登记。
 */
@Tag("real-mcp")
class RealThirdPartyMcpTest {

    /**
     * 首次 npx 要下载包、握手本身在本机实测要 50 秒、公网端点偶尔慢——
     * 30 秒的默认值在这两条上都不够。应用里的同名默认值是 30 秒 / 60 秒
     * （{@code claw4j.capability.mcp.request-timeout} 与 {@code .initialization-timeout}）。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /** 这两条都不带凭据：一个零参数、一个公开端点，句柄解析器只需回答“没有”。 */
    private static final SecretResolver NO_SECRETS = name -> Optional.empty();

    @Test
    @DisplayName("stdio：npm 上的第三方 server，工具清单拿得到，工具真的能调")
    void thirdPartyStdioServerWorks() {
        Server server = new Server(Transport.STDIO, "npx",
                List.of("-y", "@modelcontextprotocol/server-everything"),
                Map.of(), List.of("PATH", "HOME"), null, null, Map.of(),
                new Permission("read", List.of()), Map.of(), true);

        try (McpToolProvider provider = new McpToolProvider(
                List.of(new McpConnection(McpEndpoints.of("everything", server, NO_SECRETS), TIMEOUT, TIMEOUT)))) {

            assertThat(provider.toolNames())
                    .as("everything server 的参考工具集里一定有 echo")
                    .contains("echo");

            ToolCallback echo = Arrays.stream(provider.getToolCallbacks())
                    .filter(callback -> callback.getToolDefinition().name().equals("echo"))
                    .findFirst()
                    .orElseThrow();

            assertThat(echo.call("{\"message\":\"第三方 stdio server 通了\"}"))
                    .as("这一条走的是完整那条路：ToolCallback → JSON-RPC 帧 → 子进程 → 原样回来")
                    .contains("第三方 stdio server 通了");
        }
    }

    @Test
    @DisplayName("http：公网上的第三方端点，不起进程也能拿到工具清单，且每个工具都按只读登记")
    void thirdPartyHttpEndpointWorks() {
        Server server = new Server(Transport.HTTP, null, List.of(), Map.of(), List.of("PATH"),
                "https://mcp.deepwiki.com", null, Map.of(), new Permission("read", List.of()), Map.of(), true);

        try (McpToolProvider provider = new McpToolProvider(
                List.of(new McpConnection(McpEndpoints.of("deepwiki", server, NO_SECRETS), TIMEOUT)))) {

            assertThat(provider.toolNames())
                    .as("DeepWiki 的工具都是「读某个仓库的文档」，不需要凭据就能列出来")
                    .contains("read_wiki_structure", "read_wiki_contents", "ask_wiki_question");
            assertThat(provider.permissions().values())
                    .as("整条端点声明成只读，于是它导出的每个工具都是只读")
                    .allSatisfy(permission -> assertThat(permission.action()).isEqualTo("read"));
        }
    }
}
