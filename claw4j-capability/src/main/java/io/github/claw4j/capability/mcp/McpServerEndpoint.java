package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolFilter;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个 MCP server 的连接端点：它怎么被连上、哪些工具算存在。
 *
 * <p>这是留给传输的接缝。Java 侧只见工具名与参数 Schema（ADR-0003），server 在进程外还是
 * 进程内、用 stdio 还是 HTTP，都由 {@link #openTransport()} 给出的传输决定，上层的连接
 * 生命周期代码不感知。换传输就是换这一个方法。
 *
 * <p>这里<b>不声明权限</b>（ADR-0030 裁决二、票 04）：{@code action} / {@code tables} 是业务系统的
 * 授权问题，而这一层只回答"接谁、装哪些工具"。看得见什么由工具清单决定，执行前要不要人点头
 * 由审批决定，两件事都不在端点上。
 */
public final class McpServerEndpoint {

    private final String name;

    private final ToolFilter toolFilter;

    private final Supplier<McpClientTransport> transport;

    private McpServerEndpoint(String name, ToolFilter toolFilter, Supplier<McpClientTransport> transport) {
        this.name = Objects.requireNonNull(name, "name");
        this.toolFilter = Objects.requireNonNull(toolFilter, "toolFilter");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 建一个不筛的端点：server 导出什么就装什么。
     *
     * @param name 配置里的 server 名，日志与报错只用它
     * @param transport 传输工厂，每次要连接时调用一次，必须返回新的一条
     * @return 端点
     */
    public static McpServerEndpoint of(String name, Supplier<McpClientTransport> transport) {
        return of(name, ToolFilter.none(), transport);
    }

    /**
     * 建一个带存在性过滤的端点。
     *
     * <p>过滤在这里而不是在 {@link McpToolProvider} 里配置，是因为它属于"这个 server 提供什么"，
     * 与传输同一层：两个都来自同一段 yml，也都要在拿到工具清单之后才生效。
     *
     * @param name 配置里的 server 名
     * @param toolFilter 哪些工具存在；{@link ToolFilter#none()} 表示不筛
     * @param transport 传输工厂
     * @return 端点
     */
    public static McpServerEndpoint of(String name, ToolFilter toolFilter,
                                       Supplier<McpClientTransport> transport) {
        return new McpServerEndpoint(name, toolFilter, transport);
    }

    /** server 名。 */
    public String name() {
        return name;
    }

    /** 哪些工具<b>存在</b>：过滤发生在建 callback 之前，所以排掉的名字不会有任何登记。 */
    public ToolFilter toolFilter() {
        return toolFilter;
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
