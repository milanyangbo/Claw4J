package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.execution.ToolExecutionException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 接入：连接的生命周期、工具清单怎么进来、参数怎么变成远端那次调用。
 *
 * <p>对端用进程内的 stdio server 顶替（它同时也是演示实现），传输本身是真的管道与真的
 * JSON-RPC 帧。会话丢失用一个只坏一次 {@code sendMessage} 的传输替身造出来——
 * 这是边界之外的故障，不是被测对象自己的判断。
 */
class McpToolTest {

    @Test
    @DisplayName("端点导出的每个工具都进清单，名字与描述来自远端")
    void providerExposesEveryToolFromTheServer() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            McpServerEndpoint endpoint = endpoint("calc", server);
            try (McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(endpoint)))) {
                assertThat(provider.toolNames()).containsExactlyInAnyOrder("add", "echo", "explode", "tick");

                assertThat(provider.getToolCallbacks())
                        .filteredOn(callback -> callback.getToolDefinition().name().equals("add"))
                        .singleElement()
                        .satisfies(callback -> assertThat(callback.getToolDefinition().description())
                                .isEqualTo("把两个整数相加"));
                assertThat(provider.permissions())
                        .containsOnlyKeys("add", "echo", "explode", "tick")
                        .allSatisfy((name, permission) -> assertThat(permission).isEqualTo(ToolPermission.read()));
            }
        }
    }

    @Test
    @DisplayName("连接通了但一个工具都没有：建 provider 就失败，不留到用户问起")
    void emptyToolListFailsWhenTheProviderIsBuilt() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("empty")) {
            List<McpConnection> connections = List.of(new McpConnection(endpoint("empty", server)));

            assertThatThrownBy(() -> new McpToolProvider(connections))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有导出任何工具");
        }
    }

    @Test
    @DisplayName("两个 server 导出同名工具按配置错误失败，不按注册顺序悄悄留一个")
    void duplicateToolNamesAcrossServersFail() {
        try (InProcessStdioMcpServer first = InProcessStdioMcpServer.of("first", new DupTools());
             InProcessStdioMcpServer second = InProcessStdioMcpServer.of("second", new DupTools())) {
            List<McpConnection> connections = List.of(
                    new McpConnection(endpoint("first", first)),
                    new McpConnection(endpoint("second", second)));

            assertThatThrownBy(() -> new McpToolProvider(connections))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MCP 工具重名：dup");
        }
    }

    @Test
    @DisplayName("会话丢失后重开一条连接并重放这次调用，不是对着死会话重试")
    void lostSessionIsReopenedAndTheCallIsReplayed() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            FlakyTransport first = new FlakyTransport(server.openTransport());
            AtomicInteger opens = new AtomicInteger();
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc", ToolPermission.read(),
                    () -> opens.getAndIncrement() == 0 ? first : server.openTransport());

            try (McpConnection connection = new McpConnection(endpoint, Duration.ofSeconds(10))) {
                assertThat(connection.listTools()).extracting(Tool::name).contains("add");

                first.failNext(new McpTransportSessionNotFoundException("gone"));
                CallToolResult result = connection.callTool(
                        CallToolRequest.builder("add").arguments(Map.of("a", 2, "b", 3)).build());

                assertThat(textOf(result)).isEqualTo("5");
                assertThat(connection.initializations()).isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("与「会话丢了」无关的传输故障原样上抛，不触发重连")
    void unrelatedTransportFailureIsNotRetried() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            FlakyTransport transport = new FlakyTransport(server.openTransport());
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc", ToolPermission.read(), () -> transport);

            try (McpConnection connection = new McpConnection(endpoint, Duration.ofSeconds(10))) {
                connection.listTools();
                transport.failNext(new IllegalStateException("管道断了"));

                assertThatThrownBy(() -> connection.callTool(
                        CallToolRequest.builder("add").arguments(Map.of("a", 1, "b", 1)).build()))
                        .isInstanceOf(RuntimeException.class);
                assertThat(connection.initializations()).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("模型给的参数原样成为远端那次调用的 arguments")
    void argumentsReachTheRemoteToolUnchanged() throws Exception {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = providerOf("calc", server)) {
            String answer = callbackNamed(provider, "echo")
                    .call("{\"text\":\"hello world; echo pwned\"}");

            assertThat(answer).isEqualTo("ECHO:hello world; echo pwned");
        }
    }

    @Test
    @DisplayName("远端工具报错变成一次失败的工具结果，不是一段看似成功的文本")
    void remoteToolErrorBecomesAToolExecutionException() throws Exception {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = providerOf("calc", server)) {
            assertThatThrownBy(() -> callbackNamed(provider, "explode").call("{}"))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessageContaining("MCP 工具 explode 返回了错误");
        }
    }

    @Test
    @DisplayName("没有参数的调用收到空输入时按空参数处理，不报解析错")
    void blankInputBecomesEmptyArguments() throws Exception {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = providerOf("calc", server)) {
            assertThat(callbackNamed(provider, "tick").call("")).isEqualTo("tick");
        }
    }

    @Test
    @DisplayName("命令与空参数在起进程之前就被拒绝：空参数的错误会落在进程另一边")
    void stdioEndpointRefusesBlankCommandAndBlankArguments() {
        assertThatThrownBy(() -> ProcessStdioMcpEndpoint.of("blank", "  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command 是空的");

        assertThatThrownBy(() -> ProcessStdioMcpEndpoint.of("blank-arg", "/usr/bin/env", List.of("a", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数里有空项");
    }

    private static McpServerEndpoint endpoint(String name, InProcessStdioMcpServer server) {
        return McpServerEndpoint.of(name, ToolPermission.read(), server::openTransport);
    }

    private static McpToolProvider providerOf(String name, InProcessStdioMcpServer server) {
        return new McpToolProvider(List.of(new McpConnection(endpoint(name, server))));
    }

    private static org.springframework.ai.tool.ToolCallback callbackNamed(McpToolProvider provider, String name) {
        return java.util.Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String textOf(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /** 远端那一边的工具；参数与返回值都会被序列化成 JSON-RPC 帧。 */
    public static final class CalcTools {

        @McpTool(name = "add", description = "把两个整数相加")
        public String add(@McpToolParam(description = "第一个加数") int a,
                          @McpToolParam(description = "第二个加数") int b) {
            return String.valueOf(a + b);
        }

        @McpTool(name = "echo", description = "原样回显一段文本")
        public String echo(@McpToolParam(description = "要回显的文本") String text) {
            return "ECHO:" + text;
        }

        @McpTool(name = "tick", description = "不需要参数")
        public String tick() {
            return "tick";
        }

        @McpTool(name = "explode", description = "总是失败")
        public String explode() {
            throw new IllegalStateException("远端工具自己炸了");
        }
    }

    public static final class DupTools {

        @McpTool(name = "dup", description = "重名的工具")
        public String dup(@McpToolParam(description = "任意文本") String text) {
            return text;
        }
    }

    /**
     * 只坏一次 {@code sendMessage} 的传输替身：故障在边界之外（网络/会话），
     * 用来造出"会话已经不在了"这个现场。
     */
    private static final class FlakyTransport implements McpClientTransport {

        private final McpClientTransport delegate;

        private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

        private FlakyTransport(McpClientTransport delegate) {
            this.delegate = delegate;
        }

        void failNext(RuntimeException failure) {
            nextFailure.set(failure);
        }

        @Override
        public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
            return delegate.connect(handler);
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            RuntimeException failure = nextFailure.getAndSet(null);
            return failure == null ? delegate.sendMessage(message) : Mono.error(failure);
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }
    }
}
