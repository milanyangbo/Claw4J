package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * MCP 这一层的装配：容器里有几个 {@link McpServerEndpoint}，就有几个 MCP 工具来源。
 *
 * <p>加一个 MCP server 不改这里的代码：往容器里多放一个端点 bean 即可（ADR-0003）。
 * 一个端点都没有时这两个 bean 都不注册，于是"没配 MCP"表现为没有 MCP 工具，而不是启动失败。
 *
 * <p>两个来源各出一个 provider，所以这里不卡 {@code @ConditionalOnMissingBean}。
 * 那条条件的语义是"容器里已经有了就让位"，于是后注册的那一个会静默不出现——它的工具
 * 从此在清单里消失。多份来源是设计上允许的：收集方按列表收。
 */
@AutoConfiguration
public class McpToolAutoConfiguration {

    /**
     * 每个端点一条连接。连接在构造期内试一次：连不上不再让应用起不来（票 03）——
     * 记 DOWN、后台按 30 秒起翻倍退避重试，工具在该 server 恢复之后的下一次解析里出现；
     * 配置错误（点名了没导出的工具、重名）照样启动失败，见 {@link McpToolProvider} 的类注释。
     */
    @Bean
    @ConditionalOnBean(McpServerEndpoint.class)
    public McpToolProvider mcpToolProvider(ObjectProvider<McpServerEndpoint> endpoints) {
        List<McpServerEndpoint> all = endpoints.orderedStream().toList();
        return new McpToolProvider(all.stream().map(McpConnection::new).toList());
    }

    /**
     * 画像：{@code {CODING, MESSAGING}}，与业务模块同一档（ADR-0030 裁决一）。
     *
     * <p>MCP 工具是**外部来源**：它可能查数据、可能调远端接口，但跑在别的进程里，不碰本机。
     * 所以它在 {@code coding} 与 {@code messaging} 都有、{@code minimal} 没有——这与 OpenClaw 把
     * {@code bundle-mcp} 同时放进那两个画像一致。旧的 {@code FULL} 是错的：那等于"只有把整个
     * 部署放开才看得见 MCP"，一个只想查数的客服型部署因此连只读 MCP 工具都拿不到。
     *
     * <p>同样是活视图：画像由清单组装处（{@code ToolProfile#select}）读，用来判"这个工具
     * 该不该出现"。拍快照的话，恢复之后出现的工具会因为查不到画像让整份清单收集失败——
     * 一个外部 server 的重启会把整轮对话打掉。
     *
     * <p>实现 {@link ToolTierRegistry#externalToolNames()} 而不只是登记画像：`group:plugins`
     * 要靠它把"MCP 工具"一次点名，否则 {@code deny: [group:plugins]} 关不掉它们。
     */
    @Bean
    @ConditionalOnBean(McpServerEndpoint.class)
    public ToolTierRegistry mcpToolTierRegistry(McpToolProvider provider) {
        return LiveMcpTiers.of(provider);
    }
}
