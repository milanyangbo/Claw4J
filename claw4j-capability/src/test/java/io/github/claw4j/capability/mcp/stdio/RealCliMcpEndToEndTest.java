package io.github.claw4j.capability.mcp.stdio;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.capability.mcp.McpConnection;
import io.github.claw4j.capability.mcp.McpToolProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真的跨一次 OS 进程：Java 起一个 Python 子进程，用 stdio 把 MCP 说通。
 *
 * <p><b>这一批默认不跑</b>（{@code real-cli} tag，见父 pom 的 surefire 配置），
 * 理由写在票面上：它依赖本机装了 python3，而 {@code gh} / {@code himalaya} 那两条
 * 还要依赖本机装了对应 CLI 并认证过。CI 里没有这些，硬跑的结果是"换个机器就红"，
 * 那比没有这条测试更糟——它会让"红了"这件事变得没有信息量。
 *
 * <p>本机怎么跑（{@code -Preal-cli} 才是开关，别试 {@code -DexcludedGroups=}：
 * surefire 把空值当成"没设置"，命令行顶不掉 pom 里那条排除）：
 * <pre>
 *   ./mvnw -Preal-cli -pl claw4j-capability test -Dtest=RealCliMcpEndToEndTest
 * </pre>
 *
 * <p>它比 {@code McpToolEndToEndTest}（进程内）多证明的一件事是<b>进程边界真的存在</b>：
 * 那边两条管道在同一个 JVM 里，这里每一次调用都要过一次真的 fork、真的 stdin/stdout。
 * 进程内那条测的是"连接生命周期的逻辑"，这一条测的是"这条逻辑在真实边界上还成立"。
 */
@Tag("real-cli")
class RealCliMcpEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    @DisplayName("demo server：连上、列工具、调一次拿到原话")
    void demoServer连得上也调得动() {
        try (McpConnection connection = connection("demo_mcp_server.py")) {
            assertThat(toolNames(connection))
                    .contains("demo_echo", "demo_now");

            String echoed = text(connection.callTool(new CallToolRequest("demo_echo",
                    Map.of("text", "claw4j 通过 stdio 说话"))));
            assertThat(echoed).contains("claw4j 通过 stdio 说话");
        }
    }

    /**
     * 这一条盯的是"结果确实来自刚起的那个子进程"。
     *
     * <p>只断言"调得通"是不够的：一个把结果写死的实现、一个根本没起进程的实现，
     * 都能给出一次"调通了"。{@code demo_now} 带的是子进程自己的 pid 与时间戳，
     * 两次调用不一样，才能说明那里真的有一个进程在跑。
     */
    @Test
    @DisplayName("两次调用结果不同：那里真的有一个进程，不是一段写死的回答")
    void 两次调用来自同一个活着的进程() {
        try (McpConnection connection = connection("demo_mcp_server.py")) {
            String first = text(connection.callTool(new CallToolRequest("demo_now", Map.of())));
            String second = text(connection.callTool(new CallToolRequest("demo_now", Map.of())));

            assertThat(first).contains("子进程").contains("报时");
            assertThat(second).isNotEqualTo(first);
        }
    }

    /**
     * {@code gh} 那一条：工具清单里要有它导出的三个工具。
     *
     * <p>不断言"能列出 issue"——那要看本机 {@code gh} 认证过没有。
     * 这里要证明的是"这个 server 被接进来了、它导出了哪些工具是可见的"，
     * 认证那件事属于它自己的运行前提，缺了会给一句怎么装怎么认证的话。
     */
    @Test
    @DisplayName("gh server：接进来之后那三个工具在清单里")
    void ghServer导出只读工具() {
        try (McpConnection connection = connection("gh_mcp_server.py")) {
            assertThat(toolNames(connection))
                    .contains("github_issue_list", "github_pr_checks", "github_api");
        }
    }

    /**
     * {@code himalaya} 那一条：它导出<b>一个会发邮件的工具</b>。
     *
     * <p>这一条存在的理由是权限：接它进来的端点不能声明成只读权限。
     * 断言盯的是"清单里有 mail_send 这件事"——端点该声明什么权限，
     * 取决于它接进来的 server 能干什么，而这是看得见的。
     */
    @Test
    @DisplayName("himalaya server：清单里有会发邮件的那个工具")
    void himalayaServer导出有副作用的工具() {
        try (McpConnection connection = connection("himalaya_mcp_server.py")) {
            assertThat(toolNames(connection)).contains("mail_send");
        }
    }

    /** 端点声明只读权限时，它导出的每个工具都登记成同一个权限。 */
    @Test
    @DisplayName("权限跟着端点走：整个 server 共享一条边界")
    void 权限由端点声明() {
        ProcessStdioMcpEndpoint endpoint = ProcessStdioMcpEndpoint.of("claw4j-demo", "python3",
                List.of(ExamplesDirectory.mcp().resolve("demo_mcp_server.py").toString()),
                ExamplesDirectory.pathOnly());
        try (McpConnection connection = new McpConnection(endpoint.endpoint(ToolPermission.read()),
                TIMEOUT)) {
            McpToolProvider provider = new McpToolProvider(List.of(connection));

            assertThat(provider.permissions())
                    .containsEntry("demo_echo", ToolPermission.read())
                    .containsEntry("demo_now", ToolPermission.read());
        }
    }

    private static McpConnection connection(String script) {
        ProcessStdioMcpEndpoint endpoint = ProcessStdioMcpEndpoint.of(script, "python3",
                List.of(ExamplesDirectory.mcp().resolve(script).toString()),
                ExamplesDirectory.pathOnly());
        return new McpConnection(endpoint.endpoint(ToolPermission.read()), TIMEOUT);
    }

    private static List<String> toolNames(McpConnection connection) {
        return connection.listTools().stream().map(Tool::name).toList();
    }

    private static String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(content -> content instanceof io.modelcontextprotocol.spec.McpSchema.TextContent)
                .map(content -> ((io.modelcontextprotocol.spec.McpSchema.TextContent) content).text())
                .reduce("", String::concat);
    }
}
