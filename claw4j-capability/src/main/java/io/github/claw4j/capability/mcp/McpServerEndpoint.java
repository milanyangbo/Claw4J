package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个 MCP server 的连接端点：它怎么被连上、连上之后要什么权限。
 *
 * <p>这是留给传输的接缝。Java 侧只见工具名与参数 Schema（ADR-0003），server 在进程外还是
 * 进程内、用 stdio 还是 HTTP，都由 {@link #openTransport()} 给出的传输决定，上层的连接
 * 生命周期代码不感知。换传输就是换这一个方法。
 *
 * <p>权限由端点声明，可以按工具覆盖：同一个 server 导出的工具默认共享它的边界
 * （"这个 server 是只读的外部能力"还是"这个 server 能发邮件"是一件事），但第三方 server
 * 常把"查一下"与"改一下"放在同一个进程里导出。{@link #permissionFor(String)} 先看按工具
 * 声明的那张表，没点名才回落到 server 那一条——这是 ADR-0003 的第二层，粒度到工具为止，
 * 不到参数。声明成 {@link ToolPermission#read()} 而不带任何表，就是"没有业务表权限"：
 * 表清单为空时 {@code Principal.canUse} 只看动作，于是只读身份能调它、但拿不到任何表。
 *
 * <p>不声明就等于拒绝：没有端点就没有工具。
 */
public final class McpServerEndpoint {

    private final String name;

    private final ToolPermission permission;

    private final Map<String, ToolPermission> toolPermissions;

    private final Supplier<McpClientTransport> transport;

    private McpServerEndpoint(String name, ToolPermission permission,
                              Map<String, ToolPermission> toolPermissions,
                              Supplier<McpClientTransport> transport) {
        this.name = Objects.requireNonNull(name, "name");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.toolPermissions = Map.copyOf(toolPermissions);
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 整条 server 一个权限，没有任何工具覆盖。
     *
     * @param name 配置里的 server 名，日志与报错只用它
     * @param permission 这条 server 所有工具共用的权限
     * @param transport 传输工厂，每次要连接时调用一次，必须返回新的一条
     * @return 端点
     */
    public static McpServerEndpoint of(String name, ToolPermission permission, Supplier<McpClientTransport> transport) {
        return of(name, permission, Map.of(), transport);
    }

    /**
     * 建一个可以按工具覆盖权限的端点。
     *
     * @param name 配置里的 server 名
     * @param permission 没被逐个点名的工具用这一条
     * @param toolPermissions 按工具覆盖：键是 server 导出的工具名，值是它自己的权限。
     *     点名了 server 不导出的工具名，会在 {@link McpToolProvider} 建的时候失败
     * @param transport 传输工厂
     * @return 端点
     */
    public static McpServerEndpoint of(String name, ToolPermission permission,
                                       Map<String, ToolPermission> toolPermissions,
                                       Supplier<McpClientTransport> transport) {
        return new McpServerEndpoint(name, permission, toolPermissions, transport);
    }

    /** server 名。 */
    public String name() {
        return name;
    }

    /** server 那一条：所有没被逐个点名的工具都用它。 */
    public ToolPermission permission() {
        return permission;
    }

    /**
     * 某个工具实际需要什么权限：命中覆盖用覆盖，否则回落到 server 那一条。
     *
     * <p>覆盖表留空时结果与"整条 server 一个权限"完全一样，这一层因此不改变现网行为。
     */
    public ToolPermission permissionFor(String toolName) {
        return toolPermissions.getOrDefault(toolName, permission);
    }

    /**
     * 逐个点名的那些工具，给"点名了但 server 没导出"这个校验用
     * （那个校验需要知道哪些名字被点名过，而 {@link #permissionFor} 只能回答"这个名字要什么"）。
     */
    public Map<String, ToolPermission> declaredToolPermissions() {
        return toolPermissions;
    }

    /** 开一条新连接。会话丢失时靠它重新开一条，而不是去摇一棵已经死掉的树。 */
    public McpClientTransport openTransport() {
        McpClientTransport opened = transport.get();
        if (opened == null) {
            throw new IllegalStateException("MCP server " + name + " 的传输工厂返回了 null");
        }
        return opened;
    }

    @Override
    public String toString() {
        return "McpServerEndpoint[" + name + "]";
    }
}
