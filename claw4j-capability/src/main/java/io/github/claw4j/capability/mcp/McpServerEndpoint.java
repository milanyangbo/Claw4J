package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolPermission;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个 MCP server 的连接端点：它怎么被连上、连上之后要什么权限。
 *
 * <p>这是本票刻意留下的接缝。Java 侧只见 {@code 工具名 + 参数 Schema}（ADR-0003），
 * server 本体在进程外还是进程内、用 stdio 还是 HTTP，都由 {@link #openTransport()}
 * 给出来的传输决定，上面的连接生命周期代码不感知。真实部署换传输＝换这一个方法。
 *
 * <p><b>权限由端点声明，不由工具声明</b>：同一个 server 导出的工具共享它的边界
 * （"这个 server 是只读的外部能力"还是"这个 server 能发邮件"是一件事），
 * 让每个工具各声明一次等于把同一条边界抄了很多份。
 * 声明成 {@link ToolPermission#read()} 而<b>不带任何表</b>，就是"没有业务表权限"：
 * 表清单为空时 {@code Principal.canUse} 只看动作，于是只读身份能调它、但拿不到任何表。
 *
 * <p><b>不声明就等于拒绝</b>：没有端点就没有工具；端点声明了权限，工具才被登记。
 */
public final class McpServerEndpoint {

    private final String name;

    private final ToolPermission permission;

    private final Supplier<McpClientTransport> transport;

    private McpServerEndpoint(String name, ToolPermission permission, Supplier<McpClientTransport> transport) {
        this.name = Objects.requireNonNull(name, "name");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public static McpServerEndpoint of(String name, ToolPermission permission, Supplier<McpClientTransport> transport) {
        return new McpServerEndpoint(name, permission, transport);
    }

    public String name() {
        return name;
    }

    public ToolPermission permission() {
        return permission;
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
