package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.expose.McpExposure;
import io.github.claw4j.capability.mcp.expose.McpExposureProperties;
import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolProfile;
import io.github.claw4j.domain.principal.ToolTier;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 接入：连接的生命周期、工具清单怎么进来、参数怎么变成远端那次调用。
 *
 * <p>对端用进程内的 stdio server 顶替（它同时也是演示实现），传输本身是真的管道与真的 JSON-RPC 帧。
 * 会话丢失用一个只坏一次 {@code sendMessage} 的传输替身造出来：这是边界之外的故障，
 * 不是被测对象自己的判断。
 *
 * <p>两个方向都在这个文件里：前半是接别人的 MCP server（工具怎么进来），后半是自己当 MCP server
 * （工具怎么出去，P11）。放一起是因为两个方向共用同一套管道设施，也是因为门禁规定一个模块最多
 * 10 个测试文件；更实质的理由是判据相同——都是"客户端视角看到了什么"，只是站在管道的不同一端。
 */
class McpToolTest {

    /** 管道缓冲：一帧可能带上整个工具结果，1KB 的默认值会频繁阻塞。 */
    private static final int PIPE_SIZE = 1 << 16;

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
    @DisplayName("按工具声明的权限进了登记表：点名的用覆盖，其余回落到 server 那一条")
    void perToolPermissionReachesTheRegistry() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc", ToolPermission.read(),
                    Map.of("echo", ToolPermission.of("send_email")), server::openTransport);

            try (McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(endpoint)))) {
                assertThat(provider.permissions())
                        .as("登记表就是鉴权 Advisor 查的那张表，所以这一条等价于“调用时真的按工具分权”")
                        .containsEntry("echo", ToolPermission.of("send_email"))
                        .containsEntry("add", ToolPermission.read());
            }
        }
    }

    @Test
    @DisplayName("覆盖表点名了 server 没导出的工具：建 provider 就失败，且把实际清单一起报出来")
    void unknownToolNameInOverridesFailsWhenTheProviderIsBuilt() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc", ToolPermission.read(),
                    Map.of("send_mail", ToolPermission.of("send_email")), server::openTransport);

            assertThatThrownBy(() -> new McpToolProvider(List.of(new McpConnection(endpoint))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MCP server calc")
                    .hasMessageContaining("send_mail")
                    .as("报出实际导出的清单，省得再去翻 server 的文档")
                    .hasMessageContaining("add");
        }
    }

    @Test
    @DisplayName("连接通了但一个工具都没有：不算配置错误，记 DOWN 等重试，应用照常起")
    void emptyToolListIsRecoverableRatherThanFatal() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("empty");
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(endpoint("empty", server))))) {
            assertThat(provider.getToolCallbacks())
                    .as("没导出工具就一个都不给：造一个占位的空壳工具会让模型去调它，"
                            + "把基础设施故障变成模型的措辞问题")
                    .isEmpty();
            assertThat(provider.status())
                    .singleElement()
                    .satisfies(snapshot -> {
                        assertThat(snapshot.status()).isEqualTo(McpServerStatus.DOWN);
                        assertThat(snapshot.lastError())
                                .as("判成永久错误的代价是把“对方启动慢”变成“我们永远起不来”")
                                .contains("没导出");
                    });
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

    // ---- 反方向：把自己交出去（P11）。上面是"接别人的 server"，这里是"自己当 server" ----

    @Test
    @DisplayName("交出去的就是清单里点名的那几个：客户端 listTools 拿到的名字与描述来自工具定义")
    void exposedToolsReachTheClient() throws IOException {
        ProbeTool peek = new ProbeTool("peek", "42");

        try (Connected connected = serve(catalogOf(peek), Map.of("peek", ToolPermission.read("sales_order")),
                properties("peek"))) {
            List<Tool> tools = connected.client().listTools().tools();

            assertThat(tools).extracting(Tool::name).containsExactly("peek");
            assertThat(tools.getFirst().description()).isEqualTo("探针工具 peek");
            assertThat(tools.getFirst().inputSchema())
                    .as("入参 schema 直接来自工具定义，不是这里另拼的一份")
                    .containsKey("properties");
        }
    }

    @Test
    @DisplayName("客户端调一次：参数原样到工具手里，返回值原样回客户端")
    void clientCallGoesThroughTheExposedTool() throws IOException {
        ProbeTool echo = new ProbeTool("echo", "ok");

        try (Connected connected = serve(catalogOf(echo), Map.of("echo", ToolPermission.read()),
                properties("echo"))) {
            CallToolResult result = connected.client().callTool(
                    CallToolRequest.builder("echo").arguments(Map.of("text", "hello")).build());

            assertThat(result.isError()).isFalse();
            assertThat(textOf(result)).isEqualTo("ok|{\"text\":\"hello\"}");
            assertThat(echo.calls()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("动作不是 read 的工具装配期就交不出去：外部客户端只有 read，不是靠调用时拒绝")
    void nonReadToolIsRefusedAtAssembly() {
        ProbeTool send = new ProbeTool("send", "sent");

        assertThatThrownBy(() -> McpExposure.specifications(catalogOf(send),
                List.of(registry(Map.of("send", ToolPermission.of("send_email")))), properties("send")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("动作是 send_email");
    }

    @Test
    @DisplayName("需要人工批准的工具装配期就交不出去：客户端那边没有人能点按钮，也没有 run 可挂")
    void approvalToolIsRefusedAtAssembly() {
        ProbeTool drop = new ProbeTool("drop", "dropped");

        assertThatThrownBy(() -> McpExposure.specifications(catalogOf(drop),
                List.of(registry(Map.of("drop", ToolPermission.requiringApproval("write", "sales_order")))),
                properties("drop")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("需要人工批准");
    }

    @Test
    @DisplayName("没登记权限的工具交不出去：这条路径不经过鉴权 Advisor，登记表是唯一那道门")
    void unregisteredToolIsRefusedAtAssembly() {
        ProbeTool loose = new ProbeTool("loose", "nothing");

        assertThatThrownBy(() -> McpExposure.specifications(catalogOf(loose), List.of(), properties("loose")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有登记权限")
                .as("报错里要能看出是哪个工具，而不是让人去翻配置")
                .hasMessageContaining("loose");
    }

    @Test
    @DisplayName("点了清单里没有的名字：只跳过并告警，其余照常交出去（与 P09 的 allow/deny 同语义）")
    void unknownNameIsSkipped() {
        ProbeTool peek = new ProbeTool("peek", "42");

        List<SyncToolSpecification> specifications = McpExposure.specifications(catalogOf(peek),
                List.of(registry(Map.of("peek", ToolPermission.read()))), properties("peek", "ghost"));

        assertThat(specifications).extracting(specification -> specification.tool().name())
                .containsExactly("peek");
    }

    @Test
    @DisplayName("一个都交不出去就失败：连上却拿到空清单，看起来像 server 坏了")
    void emptyExposureFailsAtAssembly() {
        ProbeTool peek = new ProbeTool("peek", "42");

        assertThatThrownBy(() -> McpExposure.specifications(catalogOf(peek),
                List.of(registry(Map.of("peek", ToolPermission.read()))), properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose 是空的");

        assertThatThrownBy(() -> McpExposure.specifications(catalogOf(peek),
                List.of(registry(Map.of("peek", ToolPermission.read()))), properties("ghost")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个都不在清单里");
    }

    @Test
    @DisplayName("外部客户端能读的表收窄之后：越权的那次调用被拒，而工具一次都没执行")
    void narrowTablesRefuseTheCallAndTheToolNeverRuns() throws IOException {
        ProbeTool peek = new ProbeTool("peek", "42");

        try (Connected connected = serve(catalogOf(peek), Map.of("peek", ToolPermission.read("sales_order")),
                new McpExposureProperties(false, "test", List.of("peek"), Set.of("region")))) {
            CallToolResult result = connected.client().callTool(CallToolRequest.builder("peek").build());

            assertThat(result.isError()).isTrue();
            assertThat(textOf(result)).contains("没有权限").contains("sales_order");
            assertThat(peek.calls())
                    .as("拒绝发生在执行之前：工具跑过一次就已经越权了")
                    .isZero();
        }
    }

    @Test
    @DisplayName("工具自己报错变成一次失败的结果，不是协议层故障")
    void toolFailureBecomesAnErrorResult() throws IOException {
        ProbeTool boom = new ProbeTool("boom", "unused", true);

        try (Connected connected = serve(catalogOf(boom), Map.of("boom", ToolPermission.read()),
                properties("boom"))) {
            CallToolResult result = connected.client().callTool(CallToolRequest.builder("boom").build());

            assertThat(result.isError()).isTrue();
            assertThat(textOf(result)).contains("执行失败").contains("探针自己炸了");
            assertThat(boom.calls()).isEqualTo(1);
        }
    }

    private static McpExposureProperties properties(String... expose) {
        return new McpExposureProperties(false, "test", List.of(expose), Set.of(Principal.WILDCARD));
    }

    private static ToolPermissionRegistry registry(Map<String, ToolPermission> permissions) {
        return ToolPermissionRegistry.of(permissions);
    }

    private static ToolCatalog catalogOf(ToolCallback... tools) {
        return new ToolCatalog("test", new ToolProfile(ToolTier.FULL, Set.of(), Set.of()), List.of(tools));
    }

    /** 起一对管道、一个真 server 与一个真 client：帧是真的 JSON-RPC，不是直接调方法。 */
    private static Connected serve(ToolCatalog catalog, Map<String, ToolPermission> permissions,
                                   McpExposureProperties properties) throws IOException {
        return connect(McpExposure.specifications(catalog, List.of(registry(permissions)), properties));
    }

    private static Connected connect(List<SyncToolSpecification> specifications) throws IOException {
        PipedInputStream clientReads = new PipedInputStream(PIPE_SIZE);
        OutputStream serverWrites = new PipedOutputStream(clientReads);
        PipedInputStream serverReads = new PipedInputStream(PIPE_SIZE);
        OutputStream clientWrites = new PipedOutputStream(serverReads);

        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(
                        McpJsonDefaults.getMapper(), serverReads, serverWrites))
                .serverInfo("exposed", "1.0.0")
                .tools(specifications)
                .build();
        McpSyncClient client = McpClient.sync(new InProcessStdioMcpServer.PipedStdioClientTransport(clientReads, clientWrites))
                .clientInfo(new Implementation("probe-client", "1.0.0"))
                .build();
        client.initialize();
        return new Connected(client, server);
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

    /**
     * 交出去的那个工具：记下被调了几次。
     *
     * <p>计数不是为好看——"越权时工具一次都没执行"这条判据只能这么验：
     * 返回一句拒绝文本、工具照跑的实现在别处看不出区别。
     */
    private static final class ProbeTool implements ToolCallback {

        private final String name;

        private final String answer;

        private final boolean failing;

        private final AtomicInteger calls = new AtomicInteger();

        private ProbeTool(String name, String answer) {
            this(name, answer, false);
        }

        private ProbeTool(String name, String answer, boolean failing) {
            this.name = name;
            this.answer = answer;
            this.failing = failing;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("探针工具 " + name)
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("探针自己炸了");
            }
            return answer + "|" + toolInput;
        }
    }

    /** 一条已经握过手的连接。先关 client 再关 server：server 的读线程正等在这条管道上。 */
    private record Connected(McpSyncClient client, McpSyncServer server) implements AutoCloseable {

        @Override
        public void close() {
            client.closeGracefully();
            server.closeGracefully();
        }
    }

    /**
     * Java 自实现的 stdio MCP server，跑在本进程里：一套管道当 stdin/stdout，工具对象由接入方给
     * （{@code @McpTool} 标注的普通对象），Java 侧只见工具协议。它只服务这一个文件。
     *
     * <p>它同时是测试替身与演示实现：不依赖 Docker、不依赖 Python，于是"连不上""连上了但没工具"
     * "会话丢失重新初始化"这几类必测能进默认构建，不会退化成"只在有 Docker 的机器上测过"。
     *
     * <p>它没有跨 OS 进程。真实部署里 server 是另一个进程（{@code examples/mcp/} 里 {@code gh} 与
     * {@code himalaya} 各包成一个），那时换掉的是 {@link McpServerEndpoint#openTransport()} 给出的
     * 传输实现，本类之上的连接生命周期代码一行不改——这条接缝是刻意留下的。
     *
     * <p>每次 {@link #openTransport()} 都新建一对管道并起一个新 server 会话，因此"重新初始化连接"
     * 拿到的是一条干净的连接，而不是去摇一棵已经死掉的树。
     */
    static final class InProcessStdioMcpServer implements AutoCloseable {

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
        static InProcessStdioMcpServer of(String name, Object... toolObjects) {
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

        /**
         * MCP stdio 传输的进程内形态：两端走与 {@code StdioClientTransport} 完全一样的
         * 帧格式（行分隔的 JSON-RPC），承载它的不是子进程的标准输入输出，而是一对管道。
         *
         * <p>需要它的原因：ADR-0008 把「MCP 连接的 fail-fast 探测与会话丢失重连」列为三条必测之一，
         * 而 SDK 的 {@code StdioClientTransport} 只接受 {@code ServerParameters}，一定会
         * {@code ProcessBuilder.start()} 起一个子进程。子进程路线要拿到一条可靠的 classpath
         * （surefire 默认给的是 manifest-only 的 booter jar），这条路本身就脆。
         * 因此连接语义（初始化、listTools、会话丢失）在进程内真测，OS 进程那条边界由部署方式承担。
         *
         * <p>实现刻意照抄 {@code StdioClientTransport} 的线程分工：读一个线程、写一个线程，
         * 入站消息走 {@code Sinks.many().unicast()}，出站消息由 {@code sendMessage} 投进 sink。
         * 不自己发明一套——这个类的价值是"帧格式与官方一致"。
         *
         * <p><b>它住在 {@link InProcessStdioMcpServer} 里面</b>：两个类合用一个文件是为了门禁的
         * 文件位（每模块 10 个），而它们本来就只服务同一件事——"进程内的一条 stdio 连接"。
         */
        public static final class PipedStdioClientTransport implements McpClientTransport {

            private static final Logger log = LoggerFactory.getLogger(PipedStdioClientTransport.class);

            private final McpJsonMapper mapper = McpJsonDefaults.getMapper();

            private final InputStream inbound;

            private final OutputStream outbound;

            private final Sinks.Many<JSONRPCMessage> inboundSink = Sinks.many().unicast().onBackpressureBuffer();

            private final Sinks.Many<JSONRPCMessage> outboundSink = Sinks.many().unicast().onBackpressureBuffer();

            private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "claw4j-mcp-stdio");
                thread.setDaemon(true);
                return thread;
            });

            private final Scheduler outboundScheduler = Schedulers.fromExecutorService(workers);

            private final AtomicBoolean closing = new AtomicBoolean();

            /**
             * @param inbound  server 写、client 读的那一端
             * @param outbound client 写、server 读的那一端
             */
            PipedStdioClientTransport(InputStream inbound, OutputStream outbound) {
                this.inbound = inbound;
                this.outbound = outbound;
            }

            @Override
            public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
                return Mono.<Void>fromRunnable(() -> {
                    // 订阅必须在读线程起来之前：unicast sink 没有订阅者时 tryEmitNext 会失败，
                    // 而那种失败不报错，只是消息被丢掉——正是"连接看起来成功了但什么也没发生"的形状。
                    inboundSink.asFlux()
                            .flatMap(message -> Mono.just(message).transform(handler))
                            .subscribe();
                    startInbound();
                    startOutbound();
                }).subscribeOn(Schedulers.boundedElastic());
            }

            private void startInbound() {
                workers.submit(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(inbound, StandardCharsets.UTF_8))) {
                        String line;
                        while (!closing.get() && (line = reader.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(mapper, line);
                            if (!inboundSink.tryEmitNext(message).isSuccess()) {
                                break;
                            }
                        }
                    }
                    catch (IOException failure) {
                        if (!closing.get()) {
                            log.error("读取 MCP server 的入站消息失败", failure);
                        }
                    }
                    finally {
                        inboundSink.tryEmitComplete();
                    }
                });
            }

            private void startOutbound() {
                outboundSink.asFlux()
                        // 写发生在调用方线程上会与读抢同一条管道，这里固定到一个写线程
                        .publishOn(outboundScheduler)
                        .subscribe(this::write, failure -> {
                            if (!closing.get()) {
                                log.error("写往 MCP server 的出站消息失败", failure);
                            }
                        });
            }

            private void write(JSONRPCMessage message) {
                if (closing.get()) {
                    return;
                }
                try {
                    String json = mapper.writeValueAsString(message)
                            // stdio 帧以换行分隔，消息体内不许出现裸换行（MCP 传输规范）
                            .replace("\r\n", "\\n")
                            .replace("\n", "\\n")
                            .replace("\r", "\\n");
                    synchronized (outbound) {
                        outbound.write(json.getBytes(StandardCharsets.UTF_8));
                        outbound.write('\n');
                        outbound.flush();
                    }
                }
                catch (IOException failure) {
                    throw new IllegalStateException("写往 MCP server 失败", failure);
                }
            }

            @Override
            public Mono<Void> sendMessage(JSONRPCMessage message) {
                outboundSink.emitNext(message, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
                return Mono.empty();
            }

            @Override
            public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
                return mapper.convertValue(data, typeRef);
            }

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.<Void>fromRunnable(() -> {
                    closing.set(true);
                    inboundSink.tryEmitComplete();
                    outboundSink.tryEmitComplete();
                }).then(Mono.<Void>fromRunnable(() -> {
                    // 两个工作线程都阻塞在 read 上，disposeGracefully 等不到它们，只能硬关
                    workers.shutdownNow();
                    outboundScheduler.dispose();
                })).subscribeOn(Schedulers.boundedElastic());
            }
        }
    }
}
