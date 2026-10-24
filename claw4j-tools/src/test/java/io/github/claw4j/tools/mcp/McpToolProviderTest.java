package io.github.claw4j.tools.mcp;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.tools.mcp.stdio.InProcessStdioMcpServer;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 这一层唯一值得单独测的东西：**连接语义**。
 *
 * <p>工具本身对不对不在这里测（那是 server 自己的事），这里测的是
 * ADR-0008 点名的两条必测——工具清单为空即 fail-fast、会话丢失重新初始化——
 * 加上"三层对 Agent 呈现为一回事"这条契约在代码里的样子：
 * Java 侧拿到的只有工具名与参数 Schema，没有任何实现细节。
 *
 * <p>用的 server 是 {@link InProcessStdioMcpServer}：进程内 stdio，不依赖 Docker 与 Python，
 * 于是这两条进得了 CI。
 */
class McpToolProviderTest {

    @Test
    void 工具的名字与描述来自server而不是Java侧() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", new StatusTools())) {
            McpToolProvider provider = providerOf(server, "demo");

            assertThat(provider.toolNames()).containsExactly("runtimeStatus", "upper");
            ToolCallback upper = callbackNamed(provider, "upper");
            assertThat(upper.getToolDefinition().description()).contains("转成大写");
            // 参数 Schema 是从 server 拿回来的，Java 侧没有任何关于它的硬编码
            assertThat(upper.getToolDefinition().inputSchema()).contains("text");
        }
    }

    @Test
    void 调用MCP工具拿到的是server的返回值() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", new StatusTools())) {
            McpToolProvider provider = providerOf(server, "demo");

            assertThat(callbackNamed(provider, "upper").call("{\"text\":\"abc\"}")).isEqualTo("ABC");
            assertThat(callbackNamed(provider, "runtimeStatus").call("{}"))
                    .contains("processors=" + Runtime.getRuntime().availableProcessors());
        }
    }

    /**
     * 工具清单为空即 fail-fast（ADR-0002）。
     *
     * <p>为什么这条要单独存在：连接是<b>通的</b>——协议握手成功、server 也起来了——
     * 只是它身上一件能力都没有。这种状态不报错的话，现场是"模型说它没这个能力"，
     * 而真正的原因是命令写错或版本对不上。
     */
    @Test
    void 工具清单为空时建provider就失败() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("empty")) {
            assertThatThrownBy(() -> new McpToolProvider(List.of(new McpConnection(endpointOf(server, "empty")))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empty")
                    .hasMessageContaining("没有导出任何工具");
        }
    }

    /**
     * 会话丢失要重新初始化连接，而不是对着一条死会话重试（ADR-0002）。
     *
     * <p>断言落在两个地方，缺一个都不成立：
     * <b>调用仍然成功</b>（否则"重连"只是把异常换了个说法），
     * 且<b>连接被初始化了两次</b>（否则"成功"可能是根本没丢过会话）。
     */
    @Test
    void 会话丢失后重新初始化连接() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", new StatusTools())) {
            AtomicInteger opens = new AtomicInteger();
            McpServerEndpoint endpoint = McpServerEndpoint.of("demo", ToolPermission.read(), () -> {
                McpClientTransport transport = server.openTransport();
                // 第一条连接的会话在发起 tools/call 时丢掉，第二条是干净的
                return opens.incrementAndGet() == 1 ? new SessionLosingTransport(transport) : transport;
            });
            McpConnection connection = new McpConnection(endpoint);
            McpToolProvider provider = new McpToolProvider(List.of(connection));

            assertThat(callbackNamed(provider, "upper").call("{\"text\":\"abc\"}")).isEqualTo("ABC");
            assertThat(connection.initializations()).isEqualTo(2);
        }
    }

    /** 两个 server 导出同名工具时按配置错误失败，而不是静默留一个。 */
    @Test
    void 两个server导出同名工具时失败() {
        try (InProcessStdioMcpServer first = InProcessStdioMcpServer.of("first", new StatusTools());
             InProcessStdioMcpServer second = InProcessStdioMcpServer.of("second", new StatusTools())) {
            List<McpConnection> connections = List.of(
                    new McpConnection(endpointOf(first, "first")),
                    new McpConnection(endpointOf(second, "second")));

            assertThatThrownBy(() -> new McpToolProvider(connections))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("重名")
                    .hasMessageContaining("runtimeStatus");
        }
    }

    /**
     * MCP 层不授予任何业务表权限（ADR-0003 / ADR-0007）：登记的是端点声明的那一份，
     * 表里一张业务表都没有。留空不等于"无需权限"，而是"这次调用不牵扯任何表"——
     * 鉴权只看动作，于是只读身份能调它，但调它拿不到任何一张表。
     */
    @Test
    void MCP工具登记的权限不含任何业务表() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", new StatusTools())) {
            Map<String, ToolPermission> permissions = providerOf(server, "demo").permissions();

            assertThat(permissions).containsKeys("runtimeStatus", "upper");
            assertThat(permissions.values())
                    .as("MCP 层不授予任何业务表权限")
                    .allSatisfy(permission -> assertThat(permission.tables()).isEmpty());
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static McpToolProvider providerOf(InProcessStdioMcpServer server, String name) {
        return new McpToolProvider(List.of(new McpConnection(endpointOf(server, name))));
    }

    private static McpServerEndpoint endpointOf(InProcessStdioMcpServer server, String name) {
        return endpoint(name, ToolPermission.read(), server::openTransport);
    }

    private static McpServerEndpoint endpoint(String name, ToolPermission permission,
                                              Supplier<McpClientTransport> transport) {
        return McpServerEndpoint.of(name, permission, transport);
    }

    private static ToolCallback callbackNamed(McpToolProvider provider, String name) {
        for (ToolCallback callback : provider.getToolCallbacks()) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new AssertionError("MCP 工具清单里没有 " + name + "，实际是 " + provider.toolNames());
    }

    /**
     * 模拟"会话丢了"：发起 {@code tools/call} 时抛
     * {@link McpTransportSessionNotFoundException}。
     *
     * <p>注入点只能是传输层——这是本项目代码唯一能看见"会话不在了"的地方，
     * 也是真实故障发生的地方（对端进程没了、HTTP 会话过期）。
     */
    private static final class SessionLosingTransport implements McpClientTransport {

        private final McpClientTransport delegate;

        SessionLosingTransport(McpClientTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
            return delegate.connect(handler);
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            if (message instanceof McpSchema.JSONRPCRequest request && "tools/call".equals(request.method())) {
                throw new McpTransportSessionNotFoundException("session not found（测试注入）");
            }
            return delegate.sendMessage(message);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }
    }

    /** server 侧的工具对象：{@code @McpTool} 标注，Java 侧只见名字与参数 Schema。 */
    static final class StatusTools {

        @McpTool(name = "upper", description = "把输入文本转成大写")
        public String upper(@McpToolParam(description = "待转换的文本") String text) {
            return text == null ? "" : text.toUpperCase();
        }

        @McpTool(name = "runtimeStatus", description = "读取当前 JVM 的运行指标")
        public String runtimeStatus() {
            return "processors=" + Runtime.getRuntime().availableProcessors();
        }
    }
}
