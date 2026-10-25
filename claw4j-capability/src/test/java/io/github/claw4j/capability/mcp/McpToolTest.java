package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个完整功能：MCP 接入。
 *
 * <p>这一层管的是"外部能力怎么被接进来"：连接与重连、清单为空时启动失败、
 * 工具清单进 provider、参数翻译成远端调用。用进程内的 stdio server 当对端，
 * 所以连接语义（初始化、listTools、会话丢失）是真跑出来的，不是替身演出来的。
 */
class McpToolTest {

    @Test
    @DisplayName("命令写错在构造时就失败：不要等到连不上时才暴露，那时错在另一侧进程")
    void rejectsIncompleteServerCommandAtConfigurationTime() {
        assertThatThrownBy(() -> ProcessStdioMcpEndpoint.of("gh", "  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command 是空的");
        assertThatThrownBy(() -> ProcessStdioMcpEndpoint.of("gh", "gh", List.of("mcp", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数里有空项");
    }

    @Test
    @DisplayName("连接是懒建的：建对象不动手，第一次列工具才握手，第二次复用同一条")
    void opensConnectionLazilyAndReusesIt() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new EchoTools());
             McpConnection connection = new McpConnection(endpoint("calc", server))) {

            assertThat(connection.initializations()).isZero();

            connection.listTools();
            connection.listTools();

            assertThat(connection.initializations()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("server 一个工具都没导出时在启动阶段就失败，不等用户问到才发现")
    void failsImmediatelyWhenServerExportsNoTools() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("empty")) {
            assertThatThrownBy(() -> new McpToolProvider(List.of(new McpConnection(endpoint("empty", server)))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有导出任何工具");
        }
    }

    @Test
    @DisplayName("清单里的工具带着端点声明的权限一起登记，不认识的表一张都没有")
    void exposesToolsAndRegistersPermissionFromTheEndpoint() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new EchoTools());
             McpToolProvider provider = new McpToolProvider(
                     List.of(new McpConnection(endpoint("calc", server))))) {

            assertThat(provider.toolNames()).containsExactlyInAnyOrder("echo", "explode");
            assertThat(provider.permissions().get("echo").action()).isEqualTo(ToolPermission.READ);
            assertThat(provider.permissions().get("echo").tables()).isEmpty();
        }
    }

    @Test
    @DisplayName("模型给的 JSON 参数原样送到远端：元字符不会在中间被解释一遍")
    void translatesJsonArgumentsIntoTheRemoteCall() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new EchoTools());
             McpToolProvider provider = new McpToolProvider(
                     List.of(new McpConnection(endpoint("calc", server))))) {

            String output = callback(provider, "echo").call("{\"text\":\"a; rm -rf / $(id)\"}");

            assertThat(output).isEqualTo("a; rm -rf / $(id)");
        }
    }

    @Test
    @DisplayName("远端返回错误时抛出而不是回一个空串：模型得知道这条路这次走不通")
    void surfacesRemoteFailureInsteadOfAnEmptyResult() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new EchoTools());
             McpToolProvider provider = new McpToolProvider(
                     List.of(new McpConnection(endpoint("calc", server))))) {

            assertThatThrownBy(() -> callback(provider, "explode").call("{}"))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessageContaining("server 拒绝了这次调用");
        }
    }

    @Test
    @DisplayName("会话丢失时换一条新连接并重放一次，不是对着死会话反复重试")
    void reinitializesAfterSessionLossAndReplaysTheCallOnce() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new EchoTools())) {
            // 失败标志跨重连共享：重连拿到的是一条新传输，但"只失败一次"这件事属于故障本身。
            AtomicBoolean failedOnce = new AtomicBoolean();
            McpServerEndpoint flaky = McpServerEndpoint.of("calc", ToolPermission.read(),
                    () -> new FailFirstToolCallTransport(server.openTransport(), failedOnce));

            try (McpConnection connection = new McpConnection(flaky);
                 McpToolProvider provider = new McpToolProvider(List.of(connection))) {

                String output = callback(provider, "echo").call("{\"text\":\"还在\"}");

                assertThat(output).isEqualTo("还在");
                assertThat(connection.initializations()).isEqualTo(2);
            }
        }
    }

    private static McpServerEndpoint endpoint(String name, InProcessStdioMcpServer server) {
        return McpServerEndpoint.of(name, ToolPermission.read(), server::openTransport);
    }

    private static ToolCallback callback(McpToolProvider provider, String name) {
        return Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> name.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具清单里没有 " + name));
    }

    /** 对端工具：一个原样返回，一个总是失败。 */
    public static class EchoTools {

        @McpTool(name = "echo", description = "原样返回传入的文本")
        public String echo(@McpToolParam(description = "要返回的文本") String text) {
            return text;
        }

        @McpTool(name = "explode", description = "总是失败，用来验证失败不被吞掉")
        public String explode() {
            throw new IllegalStateException("server 拒绝了这次调用");
        }
    }

    /**
     * 第一次 tools/call 就报"会话不在"的传输：用来在进程内制造一次真的会话丢失。
     * 只失败一次，重连拿到的是底层 server 给的新传输。
     */
    private static final class FailFirstToolCallTransport implements McpClientTransport {

        private final McpClientTransport delegate;

        private final AtomicBoolean failed;

        private FailFirstToolCallTransport(McpClientTransport delegate, AtomicBoolean failed) {
            this.delegate = delegate;
            this.failed = failed;
        }

        @Override
        public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
            return delegate.connect(handler);
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            if (message instanceof McpSchema.JSONRPCRequest request
                    && McpSchema.METHOD_TOOLS_CALL.equals(request.method())
                    && failed.compareAndSet(false, true)) {
                return Mono.error(new McpTransportSessionNotFoundException("session-1"));
            }
            return delegate.sendMessage(message);
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> handler) {
            delegate.setExceptionHandler(handler);
        }
    }
}
