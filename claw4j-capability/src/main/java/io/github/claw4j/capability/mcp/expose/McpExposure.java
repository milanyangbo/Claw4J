package io.github.claw4j.capability.mcp.expose;

import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把工具清单翻译成 MCP 的工具规格（P11）。纯函数：给清单、权限登记表与配置，出一组规格。
 *
 * <p>校验全在这里，不在装配方法里：这几条都是配置错误，越早失败越好。装配方法只负责连线与
 * 日志；判断集中在这里，一个能对着清单说"这个工具不能交出去"的函数，不需要起 server 才能测。
 *
 * <p>三道门，位置不同：
 * <ul>
 *   <li>装配期：交出去的名字必须在清单里（不在只 WARN 并跳过，与 P09 的 allow / deny 同语义）、
 *       必须有权限登记、不能需要审批、动作必须是 {@code read}。这四条错了就是配置错了，
 *       没有"运行起来再看"的余地。</li>
 *   <li>调用期：{@link ToolPermission#grantedTo} 用外部客户端那个身份再判一次。这一道与 IM 侧
 *       鉴权 Advisor 同构——登记表说"这个工具碰哪些表"，身份说"能读哪些表"，两张一对，
 *       对不上就拒，且工具一次都不执行。</li>
 *   <li>不在这条路上：审批。外部客户端没有人能点按钮，也没有 run 可以挂起，所以需要审批的
 *       工具在装配期就被拒之门外，而不是"调用时返回等你批"。</li>
 * </ul>
 *
 * <p><b>规格本身由 Spring AI 生成</b>（票 01）：{@code McpSchema.Tool} 与
 * {@code CallToolResult} 的构造交给 {@link McpToolUtils#toSyncToolSpecification}，
 * 这一层只加一件它没有的东西——调用前按身份判一次权（{@link Guarded}）。
 * 自己再拼一遍 Tool 与结果，等于把"参数 JSON 与 Schema 怎么对上"这件事抄第二遍。
 *
 * <p>拒绝是一条结果，不是异常：{@link Guarded} 抛出的 {@link IllegalStateException} 由
 * {@code McpToolUtils} 的处理器转成 {@code isError=true} 的结果。抛到协议层会让客户端看到
 * 一次故障（可能触发重连），而"这个身份读不了这张表"是一件正常的事，只是它的结果。
 * 工具本身报错同样如此，与 IM 侧"远端工具报错变成一次失败的工具结果"一致。
 */
public final class McpExposure {

    private static final Logger log = LoggerFactory.getLogger(McpExposure.class);

    private McpExposure() {
    }

    /**
     * 按配置把清单里点名的工具变成 MCP 工具规格。
     *
     * @param catalog 模型那一侧同一份清单（P09 已筛过、已包过计量）
     * @param registries 各模块声明的权限登记表；同一个工具可能被多处登记，先查到的算
     * @param properties 暴露配置
     * @return 可以直接交给 MCP server 的工具规格，顺序与配置里的名字一致
     * @throws IllegalStateException 配置里的名字一个都交不出去，或某个名字不能交
     *     （见类注释的三道门）
     */
    public static List<SyncToolSpecification> specifications(ToolCatalog catalog,
                                                             List<ToolPermissionRegistry> registries,
                                                             McpExposureProperties properties) {
        if (properties.expose().isEmpty()) {
            throw new IllegalStateException("claw4j.mcp.server.enabled=true，但 claw4j.mcp.server.expose 是空的："
                    + "客户端连得上、握手也成功，只是拿到一份空工具清单——那种状态看起来像 server 坏了。"
                    + "要交出去哪几个工具，在这里写名字。清单里可选的是：" + catalog.names());
        }

        List<SyncToolSpecification> specifications = new ArrayList<>();
        List<String> notInCatalog = new ArrayList<>();
        for (String name : properties.expose()) {
            Optional<ToolCallback> callback = catalog.byName(name);
            if (callback.isEmpty()) {
                // 与 P09 的 allow / deny 同一条语义：写了清单里没有的名字不失败
                //（裁剪部署时它本来就该落空），但要列出来——拼错也长这样，而拼错的那条永远不生效。
                notInCatalog.add(name);
                continue;
            }
            ToolPermission permission = permissionFor(name, registries).orElseThrow(
                    () -> new IllegalStateException("要暴露的工具 " + name + " 没有登记权限："
                            + "外部客户端这条路径不经过鉴权 Advisor，登记表是它唯一的那道门。"
                            + "先给它登记一个 ToolPermission.read(...)，再交出去。"));
            if (permission.needsApproval()) {
                throw new IllegalStateException("要暴露的工具 " + name + " 需要人工批准，"
                        + "而外部客户端没有人能点按钮、也没有 run 可以挂起：暴露它等于给出一条绕过审批的路径。"
                        + "要交出去，先确认它不需要审批。");
            }
            if (!ToolPermission.READ.equals(permission.action())) {
                throw new IllegalStateException("要暴露的工具 " + name + " 的动作是 " + permission.action()
                        + "，而外部客户端的身份只有 read。写动作不该由一个零上下文的客户端发起——"
                        + "真要交出去，那是另一个决定。");
            }
            specifications.add(McpToolUtils.toSyncToolSpecification(
                    new Guarded(callback.get(), permission, properties)));
        }

        if (!notInCatalog.isEmpty()) {
            log.warn("claw4j.mcp.server.expose 里提到了工具清单里没有的名字 {}："
                    + "要么这个部署没装提供它的模块（正常），要么名字拼错了（不正常）——"
                    + "拼错的那一条永远不生效。清单里可选的是：{}", notInCatalog, catalog.names());
        }
        if (specifications.isEmpty()) {
            throw new IllegalStateException("claw4j.mcp.server.expose 点名的工具一个都不在清单里（"
                    + notInCatalog + "）：客户端会连上并拿到空清单。清单里可选的是：" + catalog.names());
        }
        return List.copyOf(specifications);
    }

    private static Optional<ToolPermission> permissionFor(String toolName,
                                                          List<ToolPermissionRegistry> registries) {
        for (ToolPermissionRegistry registry : registries) {
            Optional<ToolPermission> permission = registry.permissionFor(toolName);
            if (permission.isPresent()) {
                return permission;
            }
        }
        return Optional.empty();
    }

    /**
     * 一个工具 + 一道按身份的闸。名字与 Schema 来自被包的工具，调用前先判权，判不过就不执行。
     *
     * <p>抛异常而不是返回结果：{@code McpToolUtils} 的处理器只认异常（它把
     * {@code getMessage()} 放进一条 {@code isError=true} 的结果里）。这一层的形状因此由
     * Spring AI 决定，我们只决定说哪句话。
     *
     * <p>每次调用都从 {@link ToolContext} 取当时那个客户端自报的名字，不缓存：同一份工具规格
     * 会被不同客户端调用。
     */
    private record Guarded(ToolCallback tool, ToolPermission permission, McpExposureProperties properties)
            implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return tool.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String toolName = tool.getToolDefinition().name();
            Principal principal = properties.principal(clientNameOf(toolContext));

            if (!permission.grantedTo(principal)) {
                log.info("MCP 客户端 {} 调 {} 被拒：工具需要 {} 动作读表 {}，而这个身份能读的表是 {}",
                        principal.userId(), toolName, permission.action(), permission.tables(),
                        properties.tables());
                // 由 McpToolUtils 的处理器转成一条 isError 结果，不是协议层故障
                throw new IllegalStateException("没有权限：工具 " + toolName + " 需要读取表 " + permission.tables()
                        + "，而外部客户端能读的表是 " + properties.tables() + "。");
            }

            long started = System.nanoTime();
            try {
                // 单参调用：外部调用没有 run、没有 IM 身份，工具那一侧不该看见一个装了 MCP exchange
                // 的上下文——计量与审计的包装会去里面读 principal / runId，读到一个它不认识的东西，
                // 结果是"有人调过，但查不出是谁"。context 只用在本层判权。
                String answer = tool.call(toolInput);
                // 这不是审计：审计是 run 级、落库可查的（ADR-0023），而外部调用没有 run。
                // 留这一行只为出问题时能回答"谁在什么时候调了什么"。
                log.info("MCP 调用 {} -> {} 成功（{} ms）", toolName, principal.userId(), millisSince(started));
                return answer;
            }
            catch (RuntimeException failure) {
                log.warn("MCP 调用 {} -> {} 失败（{} ms）：{}", toolName, principal.userId(), millisSince(started),
                        failure.toString());
                // 这句前缀必须由这里给：McpToolUtils 只把 getMessage() 原样当失败文本
                throw new IllegalStateException("工具 " + toolName + " 执行失败：" + failure.getMessage(), failure);
            }
        }

        private static String clientNameOf(ToolContext context) {
            return McpToolUtils.getMcpExchange(context)
                    .map(exchange -> exchange.getClientInfo())
                    .map(client -> client == null ? null : client.name())
                    .orElse(null);
        }

        private static long millisSince(long startedNanos) {
            return (System.nanoTime() - startedNanos) / 1_000_000;
        }
    }
}
