package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import io.github.claw4j.domain.principal.ToolFilter;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.modelcontextprotocol.client.McpClient;
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
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import io.github.claw4j.capability.untrusted.UntrustedDescriptionCallback;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * <p>只有"接别人的 server"这一个方向：反方向（自己当 MCP server、把工具交出去）已由 ADR-0030 整块下线
 * （票 08）。它原来占这个文件后半，判据也是"客户端视角看到了什么"——只是站在管道的另一端；
 * 这一端现在不存在了，于是那个进程内 server 只剩一个用途：给客户端这一侧当真对端。
 *
 * <p>存在性过滤（票 07）那五条 2026-11-18 并回这里：它们原来单独一份
 * （{@code McpToolFilterTest}），拆出去的理由是"这个类已经 20 个方法"，而那时门禁上限是 20。
 * 上限抬到 26 之后，模块的测试<b>文件数</b>成了新的约束（那时要给 sandbox 腾一个位置），
 * 于是按当初拆出去的理由并回来。（那个位置后来空了：P17a 的容器沙箱 2026-11-18 回滚，SandboxTest 也删了。）
 */
@ExtendWith(OutputCaptureExtension.class)
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
                                // 原描述还在前面，末尾追加一句"输出是外部的"：MCP 的结果不包装（票 01），
                                // 不可信这件事只能写在工具契约里。
                                .startsWith("把两个整数相加")
                                .contains(UntrustedDescriptionCallback.NOTICE));
                assertThat(provider.toolNames())
                        .containsExactlyInAnyOrder("add", "echo", "explode", "tick");
            }
        }
    }

    @Test
    @DisplayName("画像登记是活视图：server 迟到重连之后出现的工具，也答得出它属于哪一档")
    void tierRegistryAnswersForToolsThatAppearedLater() {
        Set<String> present = new java.util.LinkedHashSet<>(Set.of("demoNow"));
        ToolTierRegistry tiers = LiveMcpTiers.of(present::contains, () -> present);

        assertThat(tiers.profilesFor("runtimeSnapshot"))
                .as("那一刻它还没连上，问不到是正常的")
                .isEmpty();

        // 十五秒后 java-http 重连成功，它的工具这时候才出现
        present.add("runtimeSnapshot");
        present.add("cronNext");

        assertThat(tiers.profilesFor("runtimeSnapshot"))
                .as("拍快照的话这里会是空的，而清单组装处查不到画像就直接抛异常——"
                        + "一个外部 server 的迟到会把每一轮对话打挂，启动日志里却一切正常")
                .contains(Set.of(ToolTier.CODING, ToolTier.MESSAGING));
        assertThat(tiers.externalToolNames())
                .as("group:plugins 靠它一次点名，所以它也得跟着长")
                .containsExactlyInAnyOrder("demoNow", "runtimeSnapshot", "cronNext");
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
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc",
                    () -> opens.getAndIncrement() == 0 ? first : server.openTransport());

            try (McpConnection connection = new McpConnection(endpoint, Duration.ofSeconds(10))) {
                Tool add = connection.listTools().stream()
                        .filter(tool -> "add".equals(tool.name()))
                        .findFirst()
                        .orElseThrow();
                // 回调在这里就建好：它不该在构造时抓住当时那条 client，否则下面这次重开之后
                // 手上就是一个死掉的会话（票 02 的判据）。
                McpToolCallback callback = new McpToolCallback(connection, add);

                first.failNext(new McpTransportSessionNotFoundException("gone"));

                assertThat(callback.call("{\"a\":2,\"b\":3}")).isEqualTo("5");
                // 第二次仍然走同一个回调：仍然成功，且没有再多开一条连接（回调没有缓存 client）
                assertThat(callback.call("{\"a\":2,\"b\":3}")).isEqualTo("5");
                assertThat(connection.initializations()).isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("与「会话丢了」无关的传输故障原样上抛，不触发重连")
    void unrelatedTransportFailureIsNotRetried() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            FlakyTransport transport = new FlakyTransport(server.openTransport());
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc", () -> transport);

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
        return McpServerEndpoint.of(name, server::openTransport);
    }

    /*
     * 存在性过滤（票 07）：以前单独一个 McpToolFilterTest，理由是"那个类已经 20 个方法（门禁上限）"。
     * 上限 2026-11-18 抬到 26，而模块的测试文件数到顶（14），所以并回来。
     *
     * 它要验的是一条链上三处一起少掉：callbacks、名字登记（toolNames）、画像（knowsTool）、
     * 以及启动日志里那份清单。只少一处的实现会让模型"看得见却调不动"或者反过来，
     * 而两种现象都不指向配置。
     */

    @Test
    @DisplayName("没配 tool-filter 时清单与改前逐个相同：这条最容易漏，漏了就是所有 MCP 工具静默消失")
    void noFilterKeepsEveryTool() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(endpoint(calc, ToolFilter.none()))))) {
            assertThat(provider.toolNames()).containsExactlyInAnyOrder("add", "echo", "explode", "tick");
        }
    }

    @Test
    @DisplayName("include 两个：第三个的 callback 与名字登记都不存在，画像也查不到它")
    void includeFiltersCallbacksAndPermissions() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(
                     endpoint(calc, new ToolFilter(List.of("add", "echo"), List.of())))))) {
            assertThat(provider.toolNames())
                    .as("清单里少一个就是少一个：没有 callback 的名字不该还留着一个能问到的登记")
                    .containsExactlyInAnyOrder("add", "echo");
            assertThat(provider.knowsTool("tick"))
                    .as("画像层问的就是这个：答 true 的话，allow 里写 tick 会被当成一个存在的工具")
                    .isFalse();
            assertThat(provider.status()).singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.tools())
                            .as("启动日志那份清单读的是同一份，不是另抄的一份")
                            .containsExactlyInAnyOrder("add", "echo"));
        }
    }

    @Test
    @DisplayName("exclude 一个：等价于把其余写进 include，结果一致")
    void excludeRemovesTool() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(
                     endpoint(calc, new ToolFilter(List.of(), List.of("explode"))))))) {
            assertThat(provider.toolNames()).containsExactlyInAnyOrder("add", "echo", "tick");
            assertThat(provider.toolNames()).doesNotContain("explode");
        }
    }

    @Test
    @DisplayName("通配：runtime* 这类写法按整串命中，只留下名字对得上的那个")
    void globFiltersByName() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(
                     endpoint(calc, new ToolFilter(List.of("e*"), List.of())))))) {
            assertThat(provider.toolNames())
                    .as("echo 与 explode 都以 e 开头：两个都留，tick 与 add 都走")
                    .containsExactlyInAnyOrder("echo", "explode");
        }
    }

    @Test
    @DisplayName("恢复之后重新筛一遍：拿到的不是第一次列出来的那份快照")
    void filterReappliedWhenTheServerComesBack(CapturedOutput output) {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            McpServerRetryTest.RecordingScheduler scheduler = new McpServerRetryTest.RecordingScheduler();
            AtomicInteger opens = new AtomicInteger();
            ToolFilter filter = new ToolFilter(List.of("add", "echo"), List.of());
            McpServerEndpoint flaky = McpServerEndpoint.of("calc", filter, () -> {
                if (opens.getAndIncrement() == 0) {
                    throw new IllegalStateException("对端正在重启");
                }
                return calc.openTransport();
            });

            try (McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(flaky)), scheduler)) {
                assertThat(provider.toolNames()).as("第一次没连上：这一轮它不在清单里").isEmpty();

                assertThat(scheduler.runNext()).isTrue();

                assertThat(provider.toolNames())
                        .as("恢复时走的是同一条装上清单的路：过滤在 install 里，所以重新筛，不是沿用快照")
                        .containsExactlyInAnyOrder("add", "echo");
                assertThat(output).contains("第 1 次重试后恢复，工具 4 个");
            }
        }
    }

    private static McpServerEndpoint endpoint(InProcessStdioMcpServer server, ToolFilter filter) {
        return McpServerEndpoint.of("calc", filter, server::openTransport);
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
