package io.github.claw4j.capability.mcp.expose;

import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 把自己交出去：{@code claw4j.mcp.server.enabled=true} 时在标准输入输出上起一个 MCP server（P11）。
 *
 * <p>不引 {@code spring-ai-starter-mcp-server}，自己装配 {@link McpSyncServer}：那个 starter
 * 带进来的 {@code ToolCallbackConverterAutoConfiguration} 会收集容器里的
 * {@code ToolCallbackProvider} 并原样暴露，于是 P09 的档位、{@code MeteredToolCallback} 的包装、
 * 权限登记表三件事会同时被绕过，与筛过的那一份同台出现，而它的工具清单看起来完全正常。
 * 自己装配只有一处工具来源：{@link ToolCatalog}。
 *
 * <p>传输写死在 stdio：本地仓库里 {@code spring-ai-starter-mcp-server-webmvc} 只有 1.1.x，
 * 2.0.2 要联网拉，离线构建会红。代价是 stdout 成了协议通道——日志、横幅、框架的启动输出
 * 一个字节都不能跑上去，见 {@code application-mcp-stdio.yml} 与 {@code logback-mcp-stdio.xml}。
 * 这一条不做自动化验证（真要验得起一个子进程、连库、连模型），记在票里。
 *
 * <p>默认关：没配 {@code claw4j.mcp.server.enabled} 时这个类整个不生效，连 transport bean
 * 都不注册——"加了依赖但没开"与"根本没有这个能力"在容器里应当长得一样。
 */
@AutoConfiguration
@ConditionalOnClass(McpSyncServer.class)
@ConditionalOnProperty(prefix = "claw4j.mcp.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpExposureProperties.class)
public class McpServerExposureAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpServerExposureAutoConfiguration.class);

    /**
     * 标准输入输出上的传输。
     *
     * <p>声明成 bean 而不是在下面直接 {@code new}：测试要换一对管道上去（协议层用例连的是真
     * JSON-RPC 帧，但不能碰测试进程自己的 stdin），换的就是这一个 bean。
     */
    @Bean
    @ConditionalOnMissingBean
    public McpServerTransportProvider mcpStdioTransport() {
        return new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in, System.out);
    }

    /**
     * server 本身。工具规格来自 {@link McpExposure}，判断都在那里。
     *
     * @param transport 传输，缺省是标准输入输出那一个
     * @param catalog 模型那一侧的同一份清单，必填：没有它就没有可交出去的工具，
     *     拿一份空清单顶上会让客户端连上一个什么都不会的 server
     * @param registries 所有来源的权限登记表，按工具名查它碰哪些表
     * @param properties 暴露配置：交出去哪几个、外部客户端能读哪些表
     * @return 已经在跑的 MCP server，关闭时由容器调 {@code closeGracefully}
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpSyncServer(McpServerTransportProvider transport,
                                       ObjectProvider<ToolCatalog> catalog,
                                       ObjectProvider<ToolPermissionRegistry> registries,
                                       McpExposureProperties properties) {
        ToolCatalog tools = catalog.getIfAvailable();
        if (tools == null) {
            throw new IllegalStateException("claw4j.mcp.server.enabled=true，但容器里没有工具清单（ToolCatalog）："
                    + "MCP server 交出去的工具必须是模型能用的那一份，另起一份就是两套清单。"
                    + "检查 claw4j-agent 是否在依赖里。");
        }

        List<ToolPermissionRegistry> allRegistries = registries.orderedStream().toList();
        List<SyncToolSpecification> specifications = McpExposure.specifications(tools, allRegistries, properties);

        // 这一行要能回答"这次到底交出去了什么、以谁的身份"。外部客户端没有 IM 身份，
        // 所以它的表范围是这一层的配置，不是人人熟的那张角色表——不写出来就没人想得起来去查。
        log.info("MCP server {} 起来了：agent {}（档位 {}）的工具里交出去 {} 个 {}；"
                        + "外部客户端身份动作 [{}]、能读的表 {}（来自 claw4j.mcp.server.tables）",
                properties.name(), tools.agent(), tools.profile().tier(), specifications.size(),
                specifications.stream().map(specification -> specification.tool().name()).toList(),
                ToolPermission.READ, properties.tables());

        return McpServer.sync(transport)
                .serverInfo(properties.name(), "1.0.0")
                .tools(specifications)
                .build();
    }
}
