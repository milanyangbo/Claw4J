package io.github.claw4j.tools.mcp;

import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MCP 这一层的工具来源：把每个端点导出的工具收成 {@link ToolCallback}。
 *
 * <p><b>建这个对象的时候就连接并取回工具清单，清单为空立刻失败</b>（ADR-0002 的 fail-fast）。
 * 为什么必须在建的时候而不是第一次调用的时候：配置错误（server 起来了但一个工具都没导出、
 * 命令写错了、版本对不上）在启动日志里是一行看得懂的失败，
 * 而等到用户问到了才暴露，现场是"模型说它没这个能力"——离真正的原因隔了十万八千里。
 * 它是单例 bean，于是"建的时候"＝"应用启动的时候"。
 *
 * <p><b>重名也在这里失败</b>。两个 server 导出同名工具是配置错误，而名字是模型与鉴权
 * 认领工具的唯一依据：悄悄留一个，等于让"这次调到的到底是哪个"取决于注册顺序。
 * 让它红，比让它静默选一个强（与 01 号票同一个取向：宁可红，不要静默少一个）。
 *
 * <p>权限登记跟着工具一起产出：{@link #permissions()} 给出"工具名 → 需要什么权限"，
 * 由自动配置交成一个 {@code ToolPermissionRegistry}。登记的是端点声明的那一份，
 * 本层不认识任何业务表。
 */
public final class McpToolProvider implements ToolCallbackProvider, AutoCloseable {

    private final List<McpConnection> connections;

    private final List<ToolCallback> callbacks;

    private final Map<String, ToolPermission> permissions;

    public McpToolProvider(List<McpConnection> connections) {
        Objects.requireNonNull(connections, "connections");
        this.connections = List.copyOf(connections);
        List<ToolCallback> collected = new ArrayList<>();
        Map<String, ToolPermission> declared = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();

        for (McpConnection connection : connections) {
            List<Tool> tools = connection.listTools();
            if (tools.isEmpty()) {
                throw new IllegalStateException("MCP server " + connection.name()
                        + " 没有导出任何工具。连接是通的（协议握手成功了），但它身上没有可用能力——"
                        + "通常是命令写错、版本对不上或工具没被注册。按 ADR-0002 的要求在这里就失败，"
                        + "不等到用户问到了才暴露。");
            }
            for (Tool tool : tools) {
                if (!seen.add(tool.name())) {
                    throw new IllegalStateException("MCP 工具重名：" + tool.name()
                            + "（来自 " + connection.name() + "）。名字是模型与鉴权认领工具的唯一依据，"
                            + "悄悄留一个等于让这次调到谁取决于注册顺序，所以按配置错误失败。");
                }
                collected.add(new McpToolCallback(connection, tool));
                declared.put(tool.name(), connection.requiredPermission());
            }
        }

        this.callbacks = List.copyOf(collected);
        this.permissions = Map.copyOf(declared);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.toArray(new ToolCallback[0]);
    }

    /** 这些工具各自需要什么权限。由自动配置登记进容器，鉴权 Advisor 按名字认领。 */
    public Map<String, ToolPermission> permissions() {
        return permissions;
    }

    /** 收集上来的工具名，供日志与断言使用。 */
    public List<String> toolNames() {
        return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
    }

    /**
     * 关掉所有连接。是 {@code AutoCloseable} 是为了让 Spring 在容器关闭时替我们调用它：
     * 每条连接背后有一个 client 与两条传输线程，不给它们一个出口的话，
     * "重连过一次"的进程会一直攒着旧连接。
     */
    @Override
    public void close() {
        for (McpConnection connection : connections) {
            connection.close();
        }
    }
}
