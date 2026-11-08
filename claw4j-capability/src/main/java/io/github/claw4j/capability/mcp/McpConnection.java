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
 * 与一个 MCP server 之间的连接，管的是这条连接的<b>生命周期</b>：开、探、丢、重开。
 *
 * <p>两件事是它存在的全部理由，都是 ADR-0002 / ADR-0003 点名的：
 *
 * <ol>
 *   <li><b>会话丢失要重新初始化，而不是重试死会话。</b>{@code McpTransportSessionNotFoundException}
 *       的含义是"你手上这条会话已经不在了"，对一条不在的会话重试只是把同一个失败重复一遍，
 *       而且会让后续每一次调用都失败——一次断连变成永久不可用。
 *       这里的处理是关掉旧连接、重新开一条、再把这一次调用重放一遍（只重放一次：
 *       重放之后仍然丢会话，那是 server 真的不在了，交给上层去失败）。
 *   <li><b>连接建立时工具清单为空即失败</b>（fail-fast）。这条不在这里判，
 *       在 {@link McpToolProvider} 建出来的时候判——那里才是"这一层的工具到底有哪些"被决定的地方。
 *   <li><b>重开也失败要上报</b>（票 03）：一次调用失败多半只是会话过期，重开一条就好；
 *       重开也失败说明 server 真的不在了，那时叫一声，让上层记 DOWN 并开始退避重试。
 *       不说的话，这个 server 就只剩"每次调用都失败"这一种表现，而它再也不会被重新连上。
 * </ol>
 *
 * <p>为什么不用 Spring AI 自动配置出来的 {@code McpSyncClient}：那些 client 由配置驱动建好之后
 * 就固定了，而"会话丢失重新初始化"必须能<b>再建一个</b>。所以 client 由本类持有，
 * 传输由 {@link McpServerEndpoint} 提供——重新初始化只是再问端点要一条传输。
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
     * <p>分开是因为两者量级差一个数量级：一次工具调用通常几百毫秒，
     * 而 {@code npx} 这类启动器要解析包、可能要访问 registry——本机实测
     * {@code npx -y @modelcontextprotocol/server-everything} 在缓存已热的情况下
     * 仍然要 50 秒才走完握手。SDK 的默认值是 20 秒，于是一个完全正常的 server
     * 会以 {@code Client failed to initialize} 的形式被判成"连不上"。
     */
    private final Duration initializationTimeout;

    private final AtomicInteger initializations = new AtomicInteger();

    private final Object lock = new Object();

    private McpSyncClient client;

    /**
     * 重开也失败时叫一声（票 03）。为 null 表示没人关心这条连接的生死（单测、例子）。
     *
     * <p><b>为什么需要它</b>：一次调用失败是"会话丢了"，重开一条就好；<b>重开也失败</b>说明
     * server 真的不在了。上层（{@link McpToolProvider}）要知道这件事，才能把状态记成 DOWN
     * 并在后台退避重试——不然它就只是一次普通的工具失败，那个 server 再也不会被重新连上。
     */
    private volatile Runnable onLost;

    public McpConnection(McpServerEndpoint endpoint) {
        this(endpoint, REQUEST_TIMEOUT);
    }

    /**
     * @param requestTimeout 一次 MCP 请求的等待上限。可注入是为了让"连不上"
     *                      这类故障在测试里也能早点红——生产用 {@link #REQUEST_TIMEOUT}，
     *                      测试给 2 秒，于是一条断言不必等满 30 秒才知道连不上。
     */
    public McpConnection(McpServerEndpoint endpoint, Duration requestTimeout) {
        this(endpoint, requestTimeout, DEFAULT_INITIALIZATION_TIMEOUT);
    }

    /**
     * @param initializationTimeout 握手超时。给的是 {@code npx} / {@code uvx} 这类启动器的余量：
     *                              它们要先解析包、可能需要访问 registry，本机实测 50 秒，
     *                              而 SDK 的默认值 20 秒会把一个正常的 server 判成"连不上"。
     */
    public McpConnection(McpServerEndpoint endpoint, Duration requestTimeout, Duration initializationTimeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.initializationTimeout = Objects.requireNonNull(initializationTimeout, "initializationTimeout");
    }

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
     * 这条连接被初始化了几次。**这是"会话丢失重新初始化"唯一的观测点**：
     * 重连发生在连接内部，从外部看得见的只有这个计数。
     */
    public int initializations() {
        return initializations.get();
    }

    /**
     * 这个 server 导出了哪些工具。空清单由 {@link McpToolProvider} 判成启动失败。
     *
     * <p><b>"没有声明 tools 能力"归一成空清单</b>，而不是让它以 SDK 的
     * {@code Server does not provide tools capability} 抛出来：
     * 那是同一件事实（这个 server 一件工具都没有）的两种表示，
     * 两处各判一次就等于同一种故障有两种报错路径，
     * 而 fail-fast 的判据只有一条——"清单是空的"。
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
     * 告诉上层"这条连接重新建也建不起来了"。
     *
     * <p>回调里抛异常一律吞掉：它不该把"这次工具调用失败"变成另一种失败。
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
     * 是不是"会话丢了"。
     *
     * <p>沿 cause 链找一层：这个异常可能被测到的时候已经被 Reactor 或 SDK 包过
     * （{@code McpSyncClient.callTool} 是在 {@code Mono.block()} 之后抛出来的）。
     * 只认这一种类型，别的故障原样往上抛——把"超时""协议错误"也当成"重开一次"，
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
