package io.github.claw4j.capability.mcp.stdio;

import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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

    /**
     * MCP stdio 传输的**进程内**形态：两端走与 {@code StdioClientTransport} 完全一样的
     * 帧格式（行分隔的 JSON-RPC），只是承载它的不是子进程的标准输入输出，而是一对管道。
     *
     * <p>为什么要有它：ADR-0008 把"MCP 连接的 fail-fast 探测与会话丢失重连"列为三条必测之一，
     * 而 SDK 的 {@code StdioClientTransport} 只接受 {@code ServerParameters}——它一定会
     * {@code ProcessBuilder.start()} 起一个子进程。子进程路线要拿到一条可靠的 classpath
     * （surefire 默认给的是 manifest-only 的 booter jar），在 CI 上是脆的。
     * 于是连接语义（初始化、listTools、会话丢失）在进程内真测，OS 进程那条边界由部署方式承担。
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
        public PipedStdioClientTransport(InputStream inbound, OutputStream outbound) {
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
