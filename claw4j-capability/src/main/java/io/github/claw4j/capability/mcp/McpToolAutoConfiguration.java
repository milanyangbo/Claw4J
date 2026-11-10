package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Optional;

/**
 * MCP 这一层的装配：容器里有几个 {@link McpServerEndpoint}，就有几个 MCP 工具来源。
 *
 * <p>加一个 MCP server 不改这里的代码：往容器里多放一个端点 bean 即可（ADR-0003）。
 * 一个端点都没有时这两个 bean 都不注册，于是"没配 MCP"表现为没有 MCP 工具，而不是启动失败。
 *
 * <p>权限登记不用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}。
 * 那条条件的语义是"容器里已经有了就让位"，于是两个来源各登记各的时，后处理的那一个会
 * 静默不注册——它的工具从此一律按"未登记"被拒。多个登记表是设计上允许的：
 * 鉴权 Advisor 消费的是一张表的列表。
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
     * MCP 工具的权限登记表。登记的内容来自端点声明，
     * 声明成 {@code ToolPermission.read()} 且不带表就是"没有业务表权限"。
     *
     * <p>它是活视图，不是装配期拍下的那张表（票 03）：一个 server 恢复之后才出现的工具，
     * 在这一层必须立刻答得出"要什么权限"。拍快照的代价是那些工具被当作"未登记"而拒绝——
     * 方向是安全的，但现象是能力回来了却调不动，而日志里没有一处说得出为什么。
     *
     * <p>没有这一张表时，MCP 工具会因"未登记权限"被拒——漏登记的失败方向是拒绝，不是放行
     * （ADR-0007）。
     */
    @Bean
    @ConditionalOnBean(McpServerEndpoint.class)
    public ToolPermissionRegistry mcpToolPermissionRegistry(McpToolProvider provider) {
        return provider::permissionFor;
    }

    /**
     * 档位：{@link ToolTier#FULL}。
     *
     * <p>外部 server 的能力清单是运行时从远端拉来的，部署者没有机会逐条审过它到底会做什么，
     * 所以整批归到最宽的一档。逐条判断要对着一个随时会变的外部清单做决定，做不到。
     *
     * <p>同样是活视图：档位由清单组装处（{@code ToolProfile#select}）读，用来判"这个工具
     * 该不该出现"。拍快照的话，恢复之后出现的工具会因为查不到档位让整份清单收集失败——
     * 一个外部 server 的重启会把整轮对话打掉。
     *
     * <p>代价写在明处：一个 {@code messaging} 部署里，MCP 工具一个都不会出现，包括只读的那些。
     * 要让某个 server 进更低的档，是它自己的配置该回答的问题，那时按 server 声明的档在这里取
     * 最小值即可，本票不做。
     */
    @Bean
    @ConditionalOnBean(McpServerEndpoint.class)
    public ToolTierRegistry mcpToolTierRegistry(McpToolProvider provider) {
        return toolName -> provider.knowsTool(toolName)
                ? Optional.of(ToolTier.FULL)
                : Optional.empty();
    }
}
