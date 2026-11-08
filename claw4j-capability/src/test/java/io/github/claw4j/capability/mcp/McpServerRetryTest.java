package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.McpToolTest.InProcessStdioMcpServer;
import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个 MCP server 连不上之后会怎样（票 03，照 OpenClaw 的 MCP 客户端）。
 *
 * <p>这一层要守的是三件事，分开验：
 * <ul>
 *   <li><b>应用照常起</b>：连不上只记 DOWN，不抛。对端重启 40 秒换来的不该是一次全站不可用。</li>
 *   <li><b>恢复之后看得见</b>：清单是活视图，重试成功之后工具就在下一次解析里。</li>
 *   <li><b>掉线不丢上次的清单</b>：从清单里消失会让模型说"我没有这个能力"，
 *       而真实情况是"它刚断了一下"。</li>
 * </ul>
 *
 * <p>定时器换成能记录、能手动驱动的替身：退避序列（30 → 60 → 120）只有把"排了多久"记下来才验得出，
 * 真等 30 秒的用例没人会跑第二遍。对端仍然是进程内那个真 server（{@link McpToolTest} 里那个），
 * 断连用一个只坏一次 {@code sendMessage} 的传输造出来——两头都不是猜的。
 */
@ExtendWith(OutputCaptureExtension.class)
class McpServerRetryTest {

    @Test
    @DisplayName("连不上只让那一家不可见：应用照常起，其它 server 的工具照常在")
    void unreachableServerDoesNotStopTheApplication(CapturedOutput output) {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new McpToolTest.CalcTools());
             McpToolProvider provider = new McpToolProvider(
                     List.of(new McpConnection(broken()), new McpConnection(endpoint("calc", calc))),
                     new RecordingScheduler())) {
            assertThat(provider.toolNames())
                    .as("一个 server 的重启不该把别人的工具也带走")
                    .containsExactlyInAnyOrder("add", "echo", "explode", "tick");
            assertThat(provider.status())
                    .extracting(McpServerSnapshot::name, McpServerSnapshot::status)
                    .containsExactlyInAnyOrder(
                            tuple("broken", McpServerStatus.DOWN),
                            tuple("calc", McpServerStatus.UP));
            assertThat(output)
                    .as("启动清单要一眼看得出谁在等重试")
                    .contains("MCP server 状态：")
                    .contains("broken → DOWN(30 秒后重试)")
                    .contains("calc → UP(工具 4 个)");
            assertThat(output).contains("30 秒后重试第 1 次");
        }
    }

    @Test
    @DisplayName("退避 30 秒起、每次翻倍、封顶 10 分钟")
    void backoffDoublesAndCaps() {
        RecordingScheduler scheduler = new RecordingScheduler();
        try (McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(broken())), scheduler)) {
            assertThat(scheduler.delaysInMillis()).containsExactly(30_000L);

            // 每次重试都仍然失败，于是每次都会排下一次：30 → 60 → 120 → 240 → 480 → 600（封顶）
            for (int round = 0; round < 6; round++) {
                assertThat(scheduler.runNext()).as("应该还有下一次重试").isTrue();
            }

            assertThat(scheduler.delaysInMillis())
                    .as("上限必须真的封住：不封的话第 11 次就是 8.5 小时，那已经不像“还在重连”了")
                    .containsExactly(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L);
            assertThat(provider.status()).singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.attempts()).isEqualTo(7));
        }
    }

    @Test
    @DisplayName("恢复之后下一次解析就看得见它：清单是活视图，不是启动那天的快照")
    void recoveredServerShowsItsToolsOnTheNextLook(CapturedOutput output) {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new McpToolTest.CalcTools())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicInteger opens = new AtomicInteger();
            McpServerEndpoint flaky = McpServerEndpoint.of("calc", ToolPermission.read(), () -> {
                if (opens.getAndIncrement() == 0) {
                    throw new IllegalStateException("对端正在重启");
                }
                return calc.openTransport();
            });

            try (McpToolProvider provider = new McpToolProvider(
                    List.of(new McpConnection(flaky)), scheduler)) {
                assertThat(provider.toolNames()).as("第一次没连上：这一轮它不在清单里").isEmpty();

                assertThat(scheduler.runNext()).isTrue();

                assertThat(provider.toolNames())
                        .as("恢复之后工具必须回来——连接自己好了但清单还是快照的话，"
                                + "模型那边只会说“我没有这个能力”")
                        .containsExactlyInAnyOrder("add", "echo", "explode", "tick");
                assertThat(provider.status()).singleElement()
                        .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(McpServerStatus.UP));
                assertThat(output).contains("第 1 次重试后恢复，工具 4 个");
            }
        }
    }

    @Test
    @DisplayName("运行中掉线：状态记 DOWN，但上次的清单留着——消失会让模型以为没有这个能力")
    void lostServerKeepsItsLastKnownTools(CapturedOutput output) {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new McpToolTest.CalcTools())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            SessionDroppingTransport first = new SessionDroppingTransport(calc.openTransport());
            AtomicInteger opens = new AtomicInteger();
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc", ToolPermission.read(), () -> {
                if (opens.getAndIncrement() == 0) {
                    return first;
                }
                throw new IllegalStateException("server 不在了");
            });

            McpConnection connection = new McpConnection(endpoint, Duration.ofSeconds(5));
            try (McpToolProvider provider = new McpToolProvider(List.of(connection), scheduler)) {
                assertThat(provider.toolNames()).hasSize(4);

                // 会话丢了、重开也开不起来：这就是"server 真的不在了"，不是"这一次调用手气不好"
                first.failNext(new McpTransportSessionNotFoundException("gone"));
                assertThatThrownBy(() -> connection.callTool(
                        CallToolRequest.builder("add").arguments(Map.of("a", 1, "b", 2)).build()))
                        .as("调用失败本身按普通工具失败处理，由工具回调变成一次 tool_failed")
                        .isInstanceOf(RuntimeException.class);

                assertThat(provider.toolNames())
                        .as("掉线不等于没有这个能力：整份撤掉会让模型换一种说法回答，用户以为工具没了")
                        .hasSize(4);
                assertThat(provider.status()).singleElement()
                        .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo(McpServerStatus.DOWN));
                assertThat(output).contains("保留上次成功列出的 4 个工具");
                assertThat(scheduler.delaysInMillis()).as("掉线之后要开始退避重连").containsExactly(30_000L);
            }
        }
    }

    @Test
    @DisplayName("恢复时才发现配置错（点名了没导出的工具）：停手报 ERROR，不再空转重试")
    void misconfigurationFoundAtRecoveryStopsRetrying(CapturedOutput output) {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new McpToolTest.CalcTools())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicInteger opens = new AtomicInteger();
            McpServerEndpoint endpoint = McpServerEndpoint.of("calc", ToolPermission.read(),
                    Map.of("send_mail", ToolPermission.of("send_email")), () -> {
                        if (opens.getAndIncrement() == 0) {
                            throw new IllegalStateException("对端正在重启");
                        }
                        return calc.openTransport();
                    });

            try (McpToolProvider provider = new McpToolProvider(
                    List.of(new McpConnection(endpoint)), scheduler)) {
                assertThat(scheduler.runNext()).isTrue();

                assertThat(provider.status()).singleElement().satisfies(snapshot -> {
                    assertThat(snapshot.status())
                            .as("这时候应用已经在跑了，“起不来”这个失败方式已经不可用，只能停下来报错")
                            .isEqualTo(McpServerStatus.MISCONFIGURED);
                    assertThat(snapshot.lastError()).contains("send_mail");
                });
                assertThat(provider.toolNames()).isEmpty();
                assertThat(scheduler.delaysInMillis())
                        .as("配置错误重试一万次也不会变：再排任务就是空转")
                        .containsExactly(30_000L);
                assertThat(output).contains("发现配置错误，停止重试").contains("send_mail");
            }
        }
    }

    @Test
    @DisplayName("close() 之后没有残留：定时器关掉，排着的任务不再执行，可重复调用")
    void closeStopsTheRetryLoop() {
        RecordingScheduler scheduler = new RecordingScheduler();
        McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(broken())), scheduler);

        provider.close();

        assertThat(scheduler.isShutdown())
                .as("关不掉的线程会让 JVM 停不下来，那比“某个 server 不可用”更坏")
                .isTrue();
        assertThat(scheduler.delaysInMillis()).containsExactly(30_000L);
        scheduler.runNext();
        assertThat(scheduler.delaysInMillis())
                .as("关掉之后排着的任务不该再排出新的：那几个任务串起来就能把 JVM 钉住")
                .containsExactly(30_000L);
        provider.close();
        assertThat(provider.toolNames()).isEmpty();
    }

    /** 一个连不上的端点：传输都建不起来（连命令都跑不起来的那一类）。 */
    private static McpServerEndpoint broken() {
        return McpServerEndpoint.of("broken", ToolPermission.read(), () -> {
            throw new IllegalStateException("连接被拒绝");
        });
    }

    private static McpServerEndpoint endpoint(String name, InProcessStdioMcpServer server) {
        return McpServerEndpoint.of(name, ToolPermission.read(), server::openTransport);
    }

    /**
     * 只记录、不真等：{@code schedule} 把延迟与任务都留下，由用例决定什么时候跑。
     *
     * <p>不真的 {@code schedule} 是关键——真排一个 30 秒的任务，这条用例就得等 30 秒；
     * 而 {@code isShutdown} 仍然是真的，{@link #closeStopsTheRetryLoop} 那条验的就是它。
     */
    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {

        private final List<Long> delays = new CopyOnWriteArrayList<>();

        private final ConcurrentLinkedDeque<Runnable> pending = new ConcurrentLinkedDeque<>();

        private RecordingScheduler() {
            super(1, runnable -> {
                Thread thread = new Thread(runnable, "claw4j-mcp-retry-test");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delays.add(unit.toMillis(delay));
            pending.add(command);
            return null;
        }

        List<Long> delaysInMillis() {
            return delays;
        }

        /** 跑下一个排着的重试。返回 false 表示没有排着的（或被 close 清了）。 */
        boolean runNext() {
            Runnable task = pending.poll();
            if (task == null) {
                return false;
            }
            task.run();
            return true;
        }
    }

    /**
     * 只坏一次 {@code sendMessage} 的传输：造出"会话已经不在了"这个现场。
     *
     * <p>故障在边界之外（网络/对端），不是被测对象自己的判断——用真管道就造不出这个现场。
     */
    private static final class SessionDroppingTransport implements McpClientTransport {

        private final McpClientTransport delegate;

        private volatile RuntimeException nextFailure;

        private SessionDroppingTransport(McpClientTransport delegate) {
            this.delegate = delegate;
        }

        void failNext(RuntimeException failure) {
            this.nextFailure = failure;
        }

        @Override
        public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
            return delegate.connect(handler);
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
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
