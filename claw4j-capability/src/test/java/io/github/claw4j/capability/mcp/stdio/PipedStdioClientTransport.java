package io.github.claw4j.capability.mcp.stdio;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * MCP stdio 传输的**进程内**形态：两端走的是与 {@code StdioClientTransport} 完全一样的
 * 帧格式（行分隔的 JSON-RPC），只是承载它的不是子进程的标准输入输出，而是一对管道。
 *
 * <p>为什么要有它：ADR-0008 把"MCP 连接的 fail-fast 探测与会话丢失重连"列为三条必测之一，
 * 而 SDK 的 {@code StdioClientTransport} 只接受 {@code ServerParameters}——它一定会
 * {@code ProcessBuilder.start()} 起一个子进程。子进程路线要拿到一条可靠的 classpath
 * （surefire 默认给的是 manifest-only 的 booter jar），在 CI 上是脆的；
 * 而"必测项只在有 Docker 的机器上测过"正是 spec 明令要避免的形状。
 * 于是连接语义（初始化、listTools、会话丢失）在进程内真测，
 * <b>OS 进程那条边界由 {@link InProcessStdioMcpServer} 之外的部署方式承担</b>（见 07）。
 *
 * <p>实现刻意照抄 {@code StdioClientTransport} 的线程分工：读一个线程、写一个线程，
 * 入站消息走 {@code Sinks.many().unicast()}，出站消息由 {@code sendMessage} 投进 sink。
 * 不自己发明一套——这个类的价值是"帧格式与官方一致"，发明就等于自己造协议。
 */
public final class PipedStdioClientTransport implements McpClientTransport {

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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inbound, StandardCharsets.UTF_8))) {
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
