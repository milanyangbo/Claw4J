package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.secret.SpringSecretResolver;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
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
 * <p>它和 {@link McpToolAutoConfiguration} 是<b>两条并行的来源</b>，各自出一个
 * {@code McpToolProvider} 与一张登记表。多份是可以的：Agent 侧按
 * {@code ObjectProvider<ToolCallbackProvider>} 与 {@code List<ToolPermissionRegistry>} 收，
 * 从来只认"所有来源"，不认某一份。合在一个 bean 里做反而要写"两个列表相加"，
 * 而那属于调用方的编排，不属于某一种来源。
 *
 * <p>留两路而不是只留配置这一路：有些端点<b>是算出来的</b>——路径要拼、token 要从 vault 取、
 * 传输要包一层。那种情况下写一个 {@code @Bean McpServerEndpoint} 更直接，
 * 配置表达不了"运行期才知道"。所以配置是主路，bean 是出口，两者都在。
 *
 * <p><b>条件是看配置，不是看容器。</b>写成 {@code @ConditionalOnBean(McpServerEndpoint.class)}
 * 会在"另一个自动配置还没解析完"的时候被求值，于是条件恒假、整层静默消失——
 * 这个仓库为它返工过一次（{@code docs/design/pitfalls.md}）。{@link ServersConfigured}
 * 只读 Environment，与 bean 的声明顺序无关。
 */
@AutoConfiguration(after = McpToolAutoConfiguration.class)
@EnableConfigurationProperties(McpServerProperties.class)
@Conditional(ConfiguredMcpServersAutoConfiguration.ServersConfigured.class)
public class ConfiguredMcpServersAutoConfiguration {

    /**
     * 每个配置条目一条连接。与会话层同一个 fail-fast：连接与工具清单都在建这个 bean 时完成，
     * 于是"配了一个连不上的 server"是启动失败，不是等到用户问起才变成"模型说它没这个能力"。
     *
     * <p>凭据句柄也在这里探一次（P08）：配了 {@code secret:xxx} 却没有 {@code XXX} 这个环境变量，
     * 启动就失败并点名是哪个 server 的哪个键——比第一次工具调用时才发现好查得多。
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
     * 配置里那些 server 的权限登记表。登记的是端点声明的那一份（默认只读、不碰业务表）。
     *
     * @param provider 按名字限定：容器里可能还有 {@link McpToolAutoConfiguration} 那一份，
     *                 不限定的话"注入了哪一份"取决于 bean 顺序
     */
    @Bean
    public ToolPermissionRegistry configuredMcpToolPermissionRegistry(
            @Qualifier("configuredMcpToolProvider") McpToolProvider provider) {
        return ToolPermissionRegistry.of(provider.permissions());
    }

    /**
     * 档位：{@link ToolTier#FULL}，与 {@link McpToolAutoConfiguration} 那条同一个判断
     * （理由写在那里）：外部清单运行时才知道，整批归最宽的一档。
     *
     * <p>两条并行来源各出一张档位表，与各出一张权限表是同一件事——
     * 装配侧收的是"所有登记表"，不认某一份。
     */
    @Bean
    public ToolTierRegistry configuredMcpTierRegistry(
            @Qualifier("configuredMcpToolProvider") McpToolProvider provider) {
        return ToolTierRegistry.sameFor(provider.permissions().keySet(), ToolTier.FULL);
    }

    /**
     * 有 server 配置、且没有全被关掉。
     *
     * <p>用 Binder 直接读 Environment 而不是先拿 bean 再判断：这个条件在配置类解析阶段求值，
     * 那时候 `@EnableConfigurationProperties` 注册的 bean 还不一定在容器里。
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
