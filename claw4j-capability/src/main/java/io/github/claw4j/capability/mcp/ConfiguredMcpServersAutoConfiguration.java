package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.secret.SpringSecretResolver;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 配置里的 MCP server：{@code claw4j.capability.mcp.servers} 下写一段，就接一个。
 *
 * <p>它和 {@link McpToolAutoConfiguration} 是两条并行的来源，各自出一个 {@code McpToolProvider}。
 * 多份是可以的：Agent 侧按 {@code ObjectProvider<ToolCallbackProvider>} 收，从来只认"所有来源"。
 * 合在一个 bean 里做反而要写"两个列表相加"，那属于调用方的编排。
 *
 * <p>留两路而不是只留配置这一路：有些端点是算出来的——路径要拼、token 要从 vault 取、
 * 传输要包一层。那时写一个 {@code @Bean McpServerEndpoint} 更直接，配置表达不了
 * "运行期才知道"。所以配置是主路，bean 是出口。
 *
 * <p>条件是看配置，不是看容器。写成 {@code @ConditionalOnBean(McpServerEndpoint.class)}
 * 会在另一个自动配置还没解析完的时候被求值，条件恒假、整层静默消失
 * （{@code docs/design/pitfalls.md}）。{@link ServersConfigured} 只读 Environment，
 * 与 bean 的声明顺序无关。
 */
@AutoConfiguration(after = McpToolAutoConfiguration.class)
@EnableConfigurationProperties(McpServerProperties.class)
@Conditional(ConfiguredMcpServersAutoConfiguration.ServersConfigured.class)
public class ConfiguredMcpServersAutoConfiguration {

    /**
     * 每个配置条目一条连接。连接与工具清单都在建这个 bean 时试一次：连不上记 DOWN 并在后台
     * 按退避重试（见 {@link McpToolProvider} 的类注释），配置错误（点名了没导出的工具、
     * 与其他 server 重名）则启动失败。
     *
     * <p>凭据句柄也在这里探一次（P08）：配了 {@code secret:xxx} 却没有 {@code XXX} 这个环境变量，
     * 启动就失败并点名是哪个 server 的哪个键，比等到第一次工具调用时才发现好查。
     * 探的时候只问在不在，真值仍然在出口（起子进程 / 写 header）现取。
     */
    @Bean
    public McpToolProvider configuredMcpToolProvider(McpServerProperties properties, Environment environment) {
        return new McpToolProvider(McpEndpoints.from(properties, SpringSecretResolver.of(environment)).stream()
                .map(endpoint -> new McpConnection(endpoint,
                        properties.getRequestTimeout(), properties.getInitializationTimeout()))
                .toList());
    }

    /**
     * 画像：{@code {CODING, MESSAGING}} 并按外部工具登记，与 {@link McpToolAutoConfiguration}
     * 那条同一个判断（理由写在 {@link LiveMcpTiers}）。
     *
     * <p><b>必须是活视图</b>：配置里的 server 启动那一刻可能连不上（本机很常见），重连之后它的
     * 工具才出现。拍快照的话，那些工具在清单组装处查不到画像，整轮对话直接失败。
     */
    @Bean
    public ToolTierRegistry configuredMcpTierRegistry(
            @Qualifier("configuredMcpToolProvider") McpToolProvider provider) {
        return LiveMcpTiers.of(provider);
    }

    /**
     * 有 server 配置，且没有全被关掉。
     *
     * <p>用 Binder 直接读 Environment 而不是先拿 bean 再判断：这个条件在配置类解析阶段求值，
     * 那时 {@code @EnableConfigurationProperties} 注册的 bean 还不一定在容器里。
     */
    static final class ServersConfigured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return Binder.get(context.getEnvironment())
                    .bind("claw4j.capability.mcp", Bindable.of(McpServerProperties.class))
                    .map(properties -> properties.isEnabled()
                            && properties.getServers().values().stream().anyMatch(McpServerProperties.Server::enabled))
                    .orElse(false);
        }
    }
}
