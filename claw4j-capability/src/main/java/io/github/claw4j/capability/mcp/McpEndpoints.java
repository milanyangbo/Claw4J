package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.McpServerProperties.Permission;
import io.github.claw4j.capability.mcp.McpServerProperties.Server;
import io.github.claw4j.capability.mcp.McpServerProperties.Transport;
import io.github.claw4j.capability.mcp.http.HttpMcpEndpoint;
import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import io.github.claw4j.domain.principal.ToolPermission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把"配置里那一小段 YAML"翻成一个 {@link McpServerEndpoint}。
 *
 * <p>放在这里而不是塞进 {@link McpServerProperties}：那一类是绑定用的，回答"配置长什么样"；
 * 这一类是装配用的，回答"配错了会怎样"。两件事混在一个类里，测配置绑定就不得不连一次 server。
 *
 * <p><b>每一条检查都在启动时失败，且报错带 server 名</b>。理由与 {@code McpToolProvider}
 * 的 fail-fast 同源：配错了而不报，最后看到的现象是"模型说它没这个能力"，
 * 离真正的原因（yml 少了一行）隔着一整条链路。带名字是为了"配了五个 server、错的是哪个"不用猜。
 */
final class McpEndpoints {

    private McpEndpoints() {
    }

    /** 按配置顺序造出全部端点。{@code enabled: false} 的直接跳过，不算错。 */
    static List<McpServerEndpoint> from(McpServerProperties properties) {
        List<McpServerEndpoint> endpoints = new ArrayList<>();
        properties.getServers().forEach((name, server) -> {
            if (server.enabled()) {
                endpoints.add(of(name, server));
            }
        });
        return List.copyOf(endpoints);
    }

    /** 一个 server 的配置 → 一个端点。传输不同，构造出来的东西不同，但类型是同一个。 */
    static McpServerEndpoint of(String name, Server server) {
        requireName(name);
        ToolPermission permission = permission(server.permission());
        Map<String, ToolPermission> tools = toolPermissions(name, server.tools());
        return switch (server.transport()) {
            case STDIO -> stdio(name, server).endpoint(permission, tools);
            case HTTP -> http(name, server, false).endpoint(permission, tools);
            case SSE -> http(name, server, true).endpoint(permission, tools);
        };
    }

    /**
     * stdio 那一路。返回具体类型而不是 {@link McpServerEndpoint}，是为了让"命令与参数到底
     * 拼成了什么"可断言——这一层最容易出的错就是 YAML 里少一个破折号，而报错在进程另一边。
     */
    static ProcessStdioMcpEndpoint stdio(String name, Server server) {
        requireName(name);
        if (server.command() == null || server.command().isBlank()) {
            throw new IllegalArgumentException("MCP server " + name + " 是 transport: stdio，但没有 command："
                    + "不知道要起哪个程序。连别人的服务请用 transport: http 并写 url。");
        }
        return ProcessStdioMcpEndpoint.of(name, server.command(), server.args(), environment(System.getenv(), server));
    }

    /** http / sse 那一路。同样返回具体类型，好让"连的是哪个地址"可断言。 */
    static HttpMcpEndpoint http(String name, Server server, boolean sse) {
        requireName(name);
        if (server.url() == null || server.url().isBlank()) {
            throw new IllegalArgumentException("MCP server " + name + " 是 transport: " + (sse ? "sse" : "http")
                    + "，但没有 url：不知道要连哪儿。要起本地进程请用 transport: stdio 并写 command。");
        }
        return HttpMcpEndpoint.of(name, server.url(), server.endpoint(), server.headers(), sse);
    }

    /**
     * 子进程拿到的环境变量：先按名字从 {@code parent} 透传，再用显式给出的覆盖。
     *
     * <p>顺序是有意的。透传解决的是"连启动器都找不到"（{@code npx} / {@code uvx} 要靠
     * {@code PATH}，包缓存要靠 {@code HOME}）；显式给出的那些用来<b>收紧</b>或补一个
     * 父进程没有的键（比如只给子进程一份专用 token）。反过来先显式再透传，会让
     * "我明明写了"的那一份被父进程的同名变量顶掉。
     *
     * <p>{@code parent} 是参数而不是直接读 {@code System.getenv()}：这一条要能测，
     * 而"从本进程真读一遍"是测不了"父进程有 PATH 但没有 HOME"这种形状的。
     */
    static Map<String, String> environment(Map<String, String> parent, Server declared) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : declared.inheritEnv()) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("MCP server 的 inherit-env 里有空项：" + declared.inheritEnv()
                        + "。空的名字透传不了任何东西，多半是 YAML 里多了一个破折号。");
            }
            String value = parent.get(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        env.putAll(declared.env());
        return env;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server 的名字是空的：它是日志与报错里唯一的标识，"
                    + "配了 server 却不给名字，出问题时只能说“某一个连不上”。");
        }
    }

    private static ToolPermission permission(Permission declared) {
        return new ToolPermission(declared.action(), Set.copyOf(declared.tables()));
    }

    /**
     * 按工具覆盖的那张表。空表是合法且默认的（＝整条 server 一个权限）。
     *
     * <p>这里只拦"键是空的"这一种形状错误——它一定是 YAML 里多写了一个冒号或者缩进错了。
     * "点名了 server 并不导出的工具"要等拿到工具清单才能判，那一步在 {@link McpToolProvider}。
     */
    private static Map<String, ToolPermission> toolPermissions(String serverName, Map<String, Permission> declared) {
        Map<String, ToolPermission> converted = new LinkedHashMap<>();
        declared.forEach((tool, permission) -> {
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException("MCP server " + serverName + " 的 tools 里有一个空的工具名："
                        + "多半是 YAML 的缩进或冒号写错了。按工具声明的权限，键必须是那个工具在 server 里的原名。");
            }
            converted.put(tool, permission(permission));
        });
        return converted;
    }
}
