package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * MCP 这一层的装配：容器里有几个 {@link McpServerEndpoint}，就有几个 MCP 工具来源。
 *
 * <p><b>加一个 MCP server 不改这里的代码</b>：往容器里多放一个端点 bean 即可
 * （ADR-0003 的"接新能力只是加配置"）。反过来，一个端点都没有时这两个 bean 都不注册，
 * 于是"没配 MCP"表现为没有 MCP 工具，而不是启动失败。
 *
 * <p><b>权限登记不用 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}</b>。
 * 那条条件的语义是"容器里已经有了就让位"，于是两个来源各登记各的时，
 * 后处理的那一个会静默不注册——它的工具从此一律按"未登记"被拒。
 * 这正是 01 号票修掉的失效姿势，只是换了个 bean 名重演一遍
 * （{@code ToolsAutoConfiguration} 里那条同类条件也一并改了，原因写在那边）。
 * 多个登记表是设计上允许的：鉴权 Advisor 消费的是一张表<b>的列表</b>。
 */
@AutoConfiguration
public class McpToolAutoConfiguration {

    /**
     * 每个端点一条连接。连接在这时候建立、工具清单在这时候取回，
     * 于是"工具清单为空"表现为启动失败（见 {@link McpToolProvider} 的类注释）。
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
     * <p>没有这一张表时，MCP 工具会因"未登记权限"被拒——漏登记的失败方向是<b>拒绝</b>，
     * 不是放行（ADR-0007）。
     */
    @Bean
    @ConditionalOnBean(McpServerEndpoint.class)
    public ToolPermissionRegistry mcpToolPermissionRegistry(McpToolProvider provider) {
        return ToolPermissionRegistry.of(provider.permissions());
    }
}
