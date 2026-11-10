package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 与一个 MCP server 之间的一条连接：开、探、丢、重开。
 *
 * <p>会话丢失（{@code McpTransportSessionNotFoundException}）的含义是手上这条会话已经不在。
 * 对它重试只是把同一个失败再做一遍，而且会让之后的每次调用都失败——一次断连变成永久不可用。
 * 处理方式是关掉旧连接、开一条新的、把这次调用重放一次；只重放一次，重放后仍然丢会话说明
 * server 真的不在了，交给上层失败。取舍见 docs/adr/0002。
 *
 * <p>重开也失败时要上报（票 03）：一次调用失败多半只是会话过期，重开一条就好；重开也失败
 * 说明 server 不在了，上层据此记 DOWN 并开始退避重试。不上报的话，这个 server 只剩
 * "每次调用都失败"一种表现，而且再也不会被重新连上。
 *
 * <p>不用 Spring AI 自动配置出来的 {@code McpSyncClient}：那些 client 由配置驱动建好之后
 * 就固定了，而重新初始化必须能再建一个。所以 client 由本类持有，传输由
 * {@link McpServerEndpoint} 提供——重新初始化只是再向端点要一条传输。
 */
public final class McpConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnection.class);

    /** 一次 MCP 请求的等待上限。超时与"会话丢失"是两种故障，不能混成同一种重试。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 握手的默认上限。见 {@link #initializationTimeout}：启动器解析包可能比一次调用慢两个数量级。 */
    private static final Duration DEFAULT_INITIALIZATION_TIMEOUT = Duration.ofSeconds(60);

    private final McpServerEndpoint endpoint;

    private final Duration requestTimeout;

    /**
     * 握手（{@code initialize}）的等待上限，与单次请求超时分开。
     *
     * <p>两者量级差得远：一次工具调用通常几百毫秒，而 {@code npx} 这类启动器要解析包、
     * 可能要访问 registry——本机实测 {@code npx -y @modelcontextprotocol/server-everything}
     * 在缓存已热时仍要 50 秒才走完握手。SDK 默认的 20 秒，会让一个正常的 server
     * 以 {@code Client failed to initialize} 的形式被判成连不上。
     */
    private final Duration initializationTimeout;

    private final AtomicInteger initializations = new AtomicInteger();

    private final Object lock = new Object();

    private McpSyncClient client;

    /**
     * 重开也失败时的回调（票 03）。为 null 表示没人关心这条连接的生死（单测、例子）。
     *
     * <p>上层（{@link McpToolProvider}）靠它把状态记成 DOWN 并在后台退避重试；没有它，
     * 一次重开失败就只是一次普通的工具失败，那个 server 再也不会被重新连上。
     */
    private volatile Runnable onLost;

    /** 用默认请求超时与默认握手超时建一条连接；真实连接在第一次用到时才建立。 */
    public McpConnection(McpServerEndpoint endpoint) {
        this(endpoint, REQUEST_TIMEOUT);
    }

    /**
     * 建一条连接，只覆盖请求超时。
     *
     * @param endpoint MCP server 端点，建连接与重开都向它要传输
     * @param requestTimeout 一次 MCP 请求的等待上限。可注入是为了让连不上这类故障在测试里
     *     也能早点红：生产用 {@link #REQUEST_TIMEOUT}，测试给 2 秒
     */
    public McpConnection(McpServerEndpoint endpoint, Duration requestTimeout) {
        this(endpoint, requestTimeout, DEFAULT_INITIALIZATION_TIMEOUT);
    }

    /**
     * 建一条连接，两个超时都显式给出。
     *
     * @param endpoint MCP server 端点
     * @param requestTimeout 一次 MCP 请求的等待上限
     * @param initializationTimeout 握手超时。要给 {@code npx} / {@code uvx} 这类启动器留余量：
     *     SDK 默认的 20 秒会把一个正常的 server 判成连不上
     */
    public McpConnection(McpServerEndpoint endpoint, Duration requestTimeout, Duration initializationTimeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.initializationTimeout = Objects.requireNonNull(initializationTimeout, "initializationTimeout");
    }

    /** 配置里的 server 名，取自端点。 */
    public String name() {
        return endpoint.name();
    }

    /** 某个工具实际要什么权限：端点按工具覆盖过就用覆盖，否则回落到 server 那一条。 */
    public ToolPermission permissionFor(String toolName) {
        return endpoint.permissionFor(toolName);
    }

    /** 端点上逐个点名过权限的工具名。用来判"点名了但 server 没导出"。 */
    public Set<String> declaredToolNames() {
        return endpoint.declaredToolPermissions().keySet();
    }

    /**
     * 这条连接被初始化过几次。
     *
     * <p>重连发生在连接内部，从外部看得见的只有这个计数，它是"会话丢失后重新初始化"
     * 唯一的观测点。
     */
    public int initializations() {
        return initializations.get();
    }

    /**
     * 这个 server 导出了哪些工具。空清单由 {@link McpToolProvider} 判成启动失败。
     *
     * <p>"没有声明 tools 能力"归一成空清单，而不是让 SDK 的
     * {@code Server does not provide tools capability} 抛出来：那是同一件事实的两种表示，
     * 两处各判一次就有两条报错路径，而 fail-fast 的判据只有一条——清单是空的。
     */
    public List<Tool> listTools() {
        McpSyncClient connected = current();
        ServerCapabilities capabilities = connected.getServerCapabilities();
        if (capabilities == null || capabilities.tools() == null) {
            return List.of();
        }
        List<Tool> tools = connected.listTools().tools();
        return tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 调一个工具，会话丢失时换一条连接重放一次。
     *
     * <p>只重放一次。重放后仍然丢会话说明 server 不在了，这时先通知上层，再把异常抛出。
     *
     * @param request 工具名与参数
     * @return server 的原样返回，包括 {@code isError} 的情形
     * @throws RuntimeException 非会话丢失的故障原样上抛；重开也失败时抛重开那次的异常
     */
    public CallToolResult callTool(CallToolRequest request) {
        try {
            return current().callTool(request);
        }
        catch (RuntimeException failure) {
            if (!isLostSession(failure)) {
                throw failure;
            }
            log.warn("MCP server {} 的会话丢失，重新初始化连接（ADR-0002）：{}", name(), failure.getMessage());
            try {
                return currentAfterReopening().callTool(request);
            }
            catch (RuntimeException hopeless) {
                // 通知必须在锁外：上层要拿自己的锁去改状态，而这里不能与它形成交叉等待。
                lost(hopeless);
                throw hopeless;
            }
        }
    }

    /**
     * 告诉上层这条连接重开也建不起来了。
     *
     * <p>回调里抛出的异常一律吞掉：它不该把"这次工具调用失败"变成另一种失败。
     */
    private void lost(RuntimeException failure) {
        Runnable listener = onLost;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        }
        catch (RuntimeException ignored) {
            log.warn("MCP server {} 失联的处理本身失败了：{}", name(), failure.getMessage());
        }
    }

    /** 上层接线用：连接重新建立也失败时叫一声。传 null 解除。 */
    void onLost(Runnable listener) {
        this.onLost = listener;
    }

    private McpSyncClient current() {
        synchronized (lock) {
            if (client == null) {
                client = open();
            }
            return client;
        }
    }

    private McpSyncClient currentAfterReopening() {
        synchronized (lock) {
            McpSyncClient dead = client;
            client = null;
            closeQuietly(dead);
            client = open();
            return client;
        }
    }

    private McpSyncClient open() {
        McpSyncClient opened;
        try {
            opened = McpClient.sync(endpoint.openTransport())
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(initializationTimeout)
                    .build();
            opened.initialize();
        }
        catch (RuntimeException failure) {
            // 消息里必须带 server 名。底层抛的是 Cannot run program / ConnectException /
            // 超时，里面一个能认出"是哪一个"的字都没有；而配置化的接法天然会有好几个 server，
            // 只报一句 IOException 等于让人在 yml 里逐个二分。
            throw new IllegalStateException("MCP server " + name() + " 连不上：" + failure.getMessage(), failure);
        }
        initializations.incrementAndGet();
        return opened;
    }

    /**
     * 是不是会话丢了。
     *
     * <p>沿 cause 链找一层：异常到达时可能已被 Reactor 或 SDK 包过
     * （{@code McpSyncClient.callTool} 在 {@code Mono.block()} 之后抛出）。
     * 只认这一种类型，别的故障原样上抛——把超时、协议错误也当成"重开一次"，
     * 会把一次真故障变成一串无意义的重连。
     */
    private static boolean isLostSession(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpTransportSessionNotFoundException) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(McpSyncClient dead) {
        if (dead == null) {
            return;
        }
        try {
            dead.close();
        }
        catch (RuntimeException ignored) {
            // 一条已经丢了会话的连接，关不关得掉都不影响后面那条新的
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeQuietly(client);
            client = null;
        }
    }
}
