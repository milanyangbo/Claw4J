package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个 MCP server 的连接端点：它怎么被连上、连上之后要什么权限。
 *
 * <p>这是本票刻意留下的接缝。Java 侧只见 {@code 工具名 + 参数 Schema}（ADR-0003），
 * server 本体在进程外还是进程内、用 stdio 还是 HTTP，都由 {@link #openTransport()}
 * 给出来的传输决定，上面的连接生命周期代码不感知。真实部署换传输＝换这一个方法。
 *
 * <p><b>权限由端点声明，可以按工具覆盖</b>：同一个 server 导出的工具默认共享它的边界
 * （"这个 server 是只读的外部能力"还是"这个 server 能发邮件"是一件事），
 * 但第三方 server 常把"查一下"与"改一下"放在同一个进程里导出。{@link #permissionFor(String)}
 * 先看按工具声明的那张表，没点名才回落到 server 那一条——这是 ADR-0003 的第二层，
 * 粒度到工具为止，不到参数。
 * 声明成 {@link ToolPermission#read()} 而<b>不带任何表</b>，就是"没有业务表权限"：
 * 表清单为空时 {@code Principal.canUse} 只看动作，于是只读身份能调它、但拿不到任何表。
 *
 * <p><b>不声明就等于拒绝</b>：没有端点就没有工具；端点声明了权限，工具才被登记。
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

    /** 整条 server 一个权限。等价于"没有任何工具覆盖"。 */
    public static McpServerEndpoint of(String name, ToolPermission permission, Supplier<McpClientTransport> transport) {
        return of(name, permission, Map.of(), transport);
    }

    /**
     * @param toolPermissions 按工具覆盖：键是 server 导出的工具名，值是它自己的权限。
     *                        点名了 server 不导出的工具名会在 {@link McpToolProvider} 建的时候失败。
     */
    public static McpServerEndpoint of(String name, ToolPermission permission,
                                       Map<String, ToolPermission> toolPermissions,
                                       Supplier<McpClientTransport> transport) {
        return new McpServerEndpoint(name, permission, toolPermissions, transport);
    }

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
     * <p>覆盖表留空时结果与"整条 server 一个权限"完全一样——这条是"加了这一层不改变现网行为"的落点。
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
