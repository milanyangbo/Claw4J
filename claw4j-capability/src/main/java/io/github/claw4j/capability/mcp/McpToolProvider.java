package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP 这一层的工具来源：把每个端点导出的工具收成 {@link ToolCallback}，
 * 并且在连不上的时候继续活着（票 03）。
 *
 * <p>它守的分界线是配置错误与连不上，判据是「重试一万次会不会变」：
 *
 * <ul>
 *   <li>会变（连不上、握手超时、连接断了、连上了但工具还没注册完）：后台退避重试。
 *       对端重启 40 秒不该换来一次全站不可用——在 K8s 上是 CrashLoopBackOff，
 *       连 HTTP 通道一起下线。</li>
 *   <li>不会变（点名了 server 没导出的工具、两个 server 导出同名工具）：启动失败。
 *       yml 写错了，重试只会把「配漏了」变成「过一会儿就好了」。</li>
 * </ul>
 *
 * <p>这一层是活视图：{@link #getToolCallbacks()} 与 {@link #permissionFor(String)} 读的是
 * 当下状态，不是建的时候拍下的快照。恢复发生在后台线程，模型下一轮就看得见
 * （清单每轮解析，见 {@code ToolCatalog}）。掉线的 server 保留上次成功列出的工具：
 * 从清单里消失会让模型说「我没有这个能力」，而实情是「它刚断了一下」，调用它按普通工具失败处理
 * （{@code tool_failed}）。
 *
 * <p>退避 15 秒起、每次翻倍、封顶 10 分钟，与 OpenClaw 的 MCP 启动退避同一组
 * （{@code src/agents/mcp-startup-backoff.ts:125} 的 {@code delayMs: 15_000}、
 * {@code :158} 的 {@code Math.min(delayMs * 2, 600_000)}）。
 * 关不掉的线程会让 JVM 停不下来，所以 {@link #close()} 既关连接也停调度器。
 */
public final class McpToolProvider implements ToolCallbackProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    /** 第一次重试等 15 秒，之后每次翻倍，封顶见 {@link #MAX_BACKOFF}。 */
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(15);

    /**
     * 退避上限。10 分钟与 OpenClaw 的 MCP 启动退避上限相同
     * （{@code src/agents/mcp-startup-backoff.ts:158}）。
     */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final List<Server> servers;

    private final ScheduledExecutorService scheduler;

    /** 容器关闭之后不再排重试任务。读写都在本对象的锁里。 */
    private boolean closed;

    /** 用真实定时器建一层工具来源；构造期就与每个 server 接触一次。 */
    public McpToolProvider(List<McpConnection> connections) {
        this(connections, newScheduler());
    }

    /** 测试用：把定时器换成能记录、能同步驱动的替身，退避序列才验得出来。 */
    McpToolProvider(List<McpConnection> connections, ScheduledExecutorService scheduler) {
        Objects.requireNonNull(connections, "connections");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        List<Server> built = new ArrayList<>();
        connections.forEach(connection -> built.add(new Server(connection)));
        this.servers = List.copyOf(built);
        for (Server server : servers) {
            // 运行中掉线：一次调用失败可能只是会话过期（连接自己会重开），
            // 而"重开也失败"说明 server 不在了——那时必须让上层记 DOWN 并开始退避，
            // 否则它就只是一次普通的工具失败，这个 server 再也不会被重新连上。
            server.connection.onLost(() -> unavailable(server, "连接丢了，重新建也建不起来"));
        }

        try {
            // 首次接触：连不上只记 DOWN 并排一次重试，不抛。配置错误照样抛——那是 yml 的问题，
            // 让它带着一个"永远起不来"的 server 继续跑，比启动失败更难查。
            for (Server server : servers) {
                contact(server, 0, true);
            }
        }
        catch (McpConfigException misconfigured) {
            // 已经开出去的连接与已经起来的定时器都要收掉：启动失败的进程不该留下半条连接。
            close();
            throw new IllegalStateException(misconfigured.getMessage(), misconfigured);
        }
        logManifest();
    }

    /**
     * 当下这一份工具清单。
     *
     * <p>只读状态，不发起连接、不握手：它每一轮请求都会被调用（{@code ToolCatalog} 按轮解析），
     * 在这里连一次等于把对端的延迟灌进对话。
     */
    @Override
    public synchronized ToolCallback[] getToolCallbacks() {
        List<ToolCallback> collected = new ArrayList<>();
        for (Server server : servers) {
            collected.addAll(server.callbacks);
        }
        return collected.toArray(new ToolCallback[0]);
    }

    /** 收上来的工具名，供日志与断言使用。 */
    public List<String> toolNames() {
        return java.util.Arrays.stream(getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /** 这些工具各自需要什么权限。由自动配置登记进容器，鉴权 Advisor 按名字认领。 */
    public synchronized Map<String, ToolPermission> permissions() {
        Map<String, ToolPermission> declared = new LinkedHashMap<>();
        for (Server server : servers) {
            declared.putAll(server.permissions);
        }
        return Map.copyOf(declared);
    }

    /**
     * 某个工具要什么权限。活视图：恢复之后才出现的工具也答得上来。
     *
     * <p>快照版本的代价很具体：{@code ToolPermissionRegistry} 是装配期拍的，恢复后出现的工具
     * 在鉴权那一层是"未登记"，判定方向是拒绝——能力回来了却调不动，现象是模型拿到一个
     * {@code denied}，而日志里没有一处说得出为什么。
     */
    public synchronized Optional<ToolPermission> permissionFor(String toolName) {
        for (Server server : servers) {
            ToolPermission permission = server.permissions.get(toolName);
            if (permission != null) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }

    /** 这个名字是不是这一层导出的工具。档位登记表靠它回答"该不该出现在这个部署里"。 */
    public synchronized boolean knowsTool(String toolName) {
        return permissionFor(toolName).isPresent();
    }

    /** 每个 server 的当下状况。启动日志那一行与断言读的是同一份。 */
    public synchronized List<McpServerSnapshot> status() {
        Instant now = Instant.now();
        List<McpServerSnapshot> snapshots = new ArrayList<>();
        for (Server server : servers) {
            snapshots.add(new McpServerSnapshot(server.connection.name(), server.status,
                    server.tools.stream().map(Tool::name).toList(),
                    server.attempts, server.nextRetryAt, server.lastError));
        }
        return List.copyOf(snapshots);
    }

    /**
     * 关掉所有连接并停掉重试。声明 {@code AutoCloseable} 是为了让 Spring 在容器关闭时调用它：
     * 每条连接背后有一个 client 与两条传输线程，"重连过一次"的进程会一直攒着旧连接；
     * 而活着的定时器会让 JVM 停不下来。
     */
    @Override
    public synchronized void close() {
        closed = true;
        scheduler.shutdownNow();
        for (Server server : servers) {
            server.connection.close();
        }
    }

    /**
     * 与一个 server 接触一次：成功就装上工具，失败就记 DOWN 并排下一次重试。
     *
     * @param retry 已经排过几次重试；0 表示首次接触（启动期）
     * @param startup 启动期与运行期只差一处：配置错误在启动期抛出（启动失败），在运行期
     *     停下来报 ERROR——那时应用已经在跑，起不来这个失败方式已经不可用了
     */
    private void contact(Server server, int retry, boolean startup) {
        List<Tool> listed;
        try {
            listed = server.connection.listTools();
        }
        catch (RuntimeException failure) {
            unavailable(server, reasonOf(failure));
            return;
        }
        if (listed.isEmpty()) {
            unavailable(server, "连上了但没导出任何工具（工具还没注册完、命令写错或版本对不上）");
            return;
        }
        try {
            install(server, listed);
        }
        catch (McpConfigException misconfigured) {
            if (startup) {
                throw misconfigured;
            }
            synchronized (this) {
                server.status = McpServerStatus.MISCONFIGURED;
                server.lastError = misconfigured.getMessage();
                server.nextRetryAt = null;
            }
            log.error("MCP server {} 在恢复时发现配置错误，停止重试：{}",
                    server.connection.name(), misconfigured.getMessage());
            return;
        }
        if (retry > 0) {
            log.info("MCP server {} 第 {} 次重试后恢复，工具 {} 个", server.connection.name(), retry, listed.size());
        }
    }

    /**
     * 装上这一份工具清单。两个校验在这里，它们都算配置错误：
     * 点名了 server 没导出的工具、与别的 server 导出同名工具。
     */
    private void install(Server server, List<Tool> tools) {
        Set<String> exported = new LinkedHashSet<>(tools.stream().map(Tool::name).toList());
        // 点名了不导出的工具名＝配置写错了。静默沿用它背后的兜底权限，症状是
        // "我明明给那个写动作加严了"，查起来要翻到 yml 里数冒号。
        Set<String> unknown = new LinkedHashSet<>(server.connection.declaredToolNames());
        unknown.removeAll(exported);
        if (!unknown.isEmpty()) {
            throw new McpConfigException("MCP server " + server.connection.name()
                    + " 的 tools 里点名了它没有导出的工具：" + String.join("、", unknown.stream().sorted().toList())
                    + "。它实际导出的是：" + String.join("、", exported.stream().sorted().toList())
                    + "。按工具声明权限时名字必须是 server 里的原名，写错一个就等于那条加严没生效。");
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, ToolPermission> declared = new LinkedHashMap<>();
        synchronized (this) {
            for (Tool tool : tools) {
                Server owner = ownerOf(tool.name(), server);
                if (owner != null) {
                    throw new McpConfigException("MCP 工具重名：" + tool.name()
                            + "（来自 " + server.connection.name() + " 与 " + owner.connection.name()
                            + "）。名字是模型与鉴权认领工具的唯一依据，悄悄留一个等于让这次调到谁取决于注册顺序，"
                            + "所以按配置错误失败。");
                }
                callbacks.add(new McpToolCallback(server.connection, tool));
                declared.put(tool.name(), server.connection.permissionFor(tool.name()));
            }
            server.tools = List.copyOf(tools);
            server.callbacks = List.copyOf(callbacks);
            server.permissions = Map.copyOf(declared);
            server.status = McpServerStatus.UP;
            server.lastError = null;
            server.nextRetryAt = null;
            // 恢复之后从头开始退避：下一次再断，仍然等 15 秒而不是直接等到 10 分钟。
            server.attempts = 0;
        }
    }

    /** 这个名字已经由哪个 server 提供（{@code self} 除外）。 */
    private Server ownerOf(String toolName, Server self) {
        for (Server other : servers) {
            if (other != self && other.permissions.containsKey(toolName)) {
                return other;
            }
        }
        return null;
    }

    /**
     * 记 DOWN 并排下一次重试。
     *
     * <p>清单不清空：掉线之前成功列过的工具留在原处（见类注释）。连接要关掉：一条已经死掉的
     * client 不会自己变好，不关的话下一次重试仍在摇同一棵死树，退避会一直空转到封顶。
     */
    private void unavailable(Server server, String reason) {
        Duration delay;
        int next;
        int keptTools;
        synchronized (this) {
            server.status = McpServerStatus.DOWN;
            server.lastError = reason;
            keptTools = server.tools.size();
            if (closed) {
                return;
            }
            next = server.attempts + 1;
            delay = backoff(next);
            server.attempts = next;
            server.nextRetryAt = Instant.now().plus(delay);
            server.connection.close();
            try {
                scheduler.schedule(() -> retry(server, next), delay.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (RejectedExecutionException shuttingDown) {
                // 正在关容器：不再排任务，也不算失败
                server.nextRetryAt = null;
            }
        }
        log.warn("MCP server {} 暂时不可用（{}）：{} 秒后重试第 {} 次。{}",
                server.connection.name(), reason, delay.toSeconds(), next,
                keptTools == 0
                        ? "它还没有成功列过工具，这一轮的清单里没有它，其它工具照常。"
                        : "保留上次成功列出的 " + keptTools + " 个工具，调用它们会按普通工具失败处理。");
    }

    private void retry(Server server, int retry) {
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        contact(server, retry, false);
    }

    /**
     * 第 {@code attempt} 次重试之前等多久：15 秒起，每次翻倍，封顶 10 分钟。
     * 上限必须真的封住——不封的话第 11 次就是 8.5 小时，那已经不像"还在重连"了。
     */
    static Duration backoff(int attempt) {
        Duration delay = INITIAL_BACKOFF;
        for (int step = 1; step < attempt; step++) {
            delay = delay.multipliedBy(2);
            if (delay.compareTo(MAX_BACKOFF) >= 0) {
                return MAX_BACKOFF;
            }
        }
        return delay;
    }

    /**
     * 启动日志里那一份清单：哪个 server 是 UP、哪个在等重试。
     *
     * <p>不写这一行的话，"配了三个 server、其中一个 DOWN"在日志里只剩一句 WARN，
     * 而它属于哪一轮、另外两个怎么样，要人去数。启动清单是一眼能看完的那一份。
     */
    private void logManifest() {
        Instant now = Instant.now();
        List<String> line = new ArrayList<>();
        for (McpServerSnapshot snapshot : status()) {
            line.add(snapshot.describe(now));
        }
        log.info("MCP server 状态：{}", line.isEmpty() ? "（一个都没配）" : String.join("，", line));
    }

    private static String reasonOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "claw4j-mcp-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 一个 server 的当下状态。字段读写都在 {@link McpToolProvider} 的锁里。 */
    private static final class Server {

        private final McpConnection connection;

        private McpServerStatus status = McpServerStatus.DOWN;

        private List<Tool> tools = List.of();

        private List<ToolCallback> callbacks = List.of();

        private Map<String, ToolPermission> permissions = Map.of();

        private int attempts;

        private Instant nextRetryAt;

        private String lastError;

        private Server(McpConnection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }
    }

    /** 配置错误：重试一万次也不会变。启动期抛、运行期报 ERROR 并停手。 */
    private static final class McpConfigException extends RuntimeException {

        private McpConfigException(String message) {
            super(message);
        }
    }
}
