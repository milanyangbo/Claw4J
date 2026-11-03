package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.McpServerProperties.Permission;
import io.github.claw4j.capability.mcp.McpServerProperties.Server;
import io.github.claw4j.capability.mcp.McpServerProperties.Transport;
import io.github.claw4j.capability.mcp.http.HttpMcpEndpoint;
import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import io.github.claw4j.domain.principal.Approval;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.secret.SecretValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p><b>凭据是句柄，这里只验形状与"在不在"</b>（P08）：{@code env} 与 {@code headers} 的值
 * 必须是 {@code secret:<名字>}（或显式常量 {@code plain:<文本>}），配了明文在装配期就失败并点名
 * 到 {@code servers.<名字>.env.<键>}。真值不在这里取——stdin 那一支在起子进程时取，
 * http 那一支在每次请求写 header 时取。
 */
final class McpEndpoints {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoints.class);

    private McpEndpoints() {
    }

    /** 按配置顺序造出全部端点。{@code enabled: false} 的直接跳过，不算错。 */
    static List<McpServerEndpoint> from(McpServerProperties properties, SecretResolver resolver) {
        List<McpServerEndpoint> endpoints = new ArrayList<>();
        properties.getServers().forEach((name, server) -> {
            if (server.enabled()) {
                endpoints.add(of(name, server, resolver));
            }
        });
        return List.copyOf(endpoints);
    }

    /** 一个 server 的配置 → 一个端点。传输不同，构造出来的东西不同，但类型是同一个。 */
    static McpServerEndpoint of(String name, Server server, SecretResolver resolver) {
        requireName(name);
        ToolPermission permission = permission(server.permission());
        Map<String, ToolPermission> tools = toolPermissions(name, server.tools());
        return switch (server.transport()) {
            case STDIO -> stdio(name, server, resolver).endpoint(permission, tools);
            case HTTP -> http(name, server, false, resolver).endpoint(permission, tools);
            case SSE -> http(name, server, true, resolver).endpoint(permission, tools);
        };
    }

    /**
     * stdio 那一路。返回具体类型而不是 {@link McpServerEndpoint}，是为了让"命令与参数到底
     * 拼成了什么"可断言——这一层最容易出的错就是 YAML 里少一个破折号，而报错在进程另一边。
     */
    static ProcessStdioMcpEndpoint stdio(String name, Server server, SecretResolver resolver) {
        requireName(name);
        if (server.command() == null || server.command().isBlank()) {
            throw new IllegalArgumentException("MCP server " + name + " 是 transport: stdio，但没有 command："
                    + "不知道要起哪个程序。连别人的服务请用 transport: http 并写 url。");
        }
        Map<String, SecretValue> env = handleValues(name, "env", server.env(), resolver);
        return ProcessStdioMcpEndpoint.of(name, server.command(), server.args(), server.inheritEnv(), env, resolver);
    }

    /** http / sse 那一路。同样返回具体类型，好让"连的是哪个地址"可断言。 */
    static HttpMcpEndpoint http(String name, Server server, boolean sse, SecretResolver resolver) {
        requireName(name);
        if (server.url() == null || server.url().isBlank()) {
            throw new IllegalArgumentException("MCP server " + name + " 是 transport: " + (sse ? "sse" : "http")
                    + "，但没有 url：不知道要连哪儿。要起本地进程请用 transport: stdio 并写 command。");
        }
        Map<String, SecretValue> headers = handleValues(name, "headers", server.headers(), resolver);
        return HttpMcpEndpoint.of(name, server.url(), server.endpoint(), headers, sse, resolver);
    }

    /**
     * 一组配置值 → 一组句柄值：形状在这里校验，真值不在。
     *
     * <p>启动期顺手探一次"在不在"（{@link SecretValue#requireResolvable}）：一个配了句柄却
     * 没给真值的 server，应该在启动时就失败，而不是等到模型第一次调它的工具。
     * 探的时候只问在不在，不保留值——真值仍然每次出口现取。
     *
     * @param field {@code env} 或 {@code headers}，用来把报错点名到具体那一行
     */
    static Map<String, SecretValue> handleValues(String serverName, String field, Map<String, String> declared,
                                            SecretResolver resolver) {
        Map<String, SecretValue> values = new LinkedHashMap<>();
        declared.forEach((key, raw) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("MCP server " + serverName + " 的 " + field
                        + " 里有一个空的键：多半是 YAML 的缩进或冒号写错了。");
            }
            String where = "claw4j.capability.mcp.servers." + serverName + "." + field + "." + key;
            SecretValue value = SecretValue.parse(raw, where, true);
            value.requireResolvable(resolver);
            values.put(key, value);
        });
        if (!values.isEmpty()) {
            // 只打名字：日志会被转发、会被贴进工单，而这一行恰好知道"哪些句柄在这个 server 上"。
            log.info("MCP server {} 的 {} 用了句柄 {}", serverName, field,
                    values.values().stream().map(SecretValue::handles).flatMap(List::stream).distinct().toList());
        }
        return values;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server 的名字是空的：它是日志与报错里唯一的标识，"
                    + "配了 server 却不给名字，出问题时只能说“某一个连不上”。");
        }
    }

    /**
     * 配置里那一段 → 一个权限。
     *
     * <p><b>审批这一档不从 yml 读</b>（P01）：配出来的第三方工具一律 {@link Approval#NONE}。
     * 加一个 {@code approval: required} 的键很便宜，但"哪个外部动作必须有批"是个部署决定，
     * 而它的落点更该是有名单、有卡片的审批策略（P09），不是"谁记得在 yml 里补一行"。
     * 这一处只把第三个分量显式写出来，好让"接进来的 MCP 工具默认不需要审批"是一句可读的代码。
     */
    private static ToolPermission permission(Permission declared) {
        return new ToolPermission(declared.action(), Set.copyOf(declared.tables()), Approval.NONE);
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
