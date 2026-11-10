package io.github.claw4j.capability.builtin.system;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.github.claw4j.capability.builtin.system.SystemStatusTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * 运行时状态这一层的装配。
 *
 * <p>不挂任何开关：这一层读本进程自己的指标，不依赖外部运行时、不碰业务数据、没有副作用，
 * 于是"有没有这一层"不需要是一个决定。它与脚本层（{@code claw4j.scripts.directory}）、
 * 生成器（{@code claw4j.generator.staging-directory}）那种"放开执行能力"的开关不是一回事。
 *
 * <p>权限登记在这里而不是写在 Agent 侧：只有写工具的人知道它要不要受表级约束（ADR-0007）。
 * 登记的动作是 {@code read_system}，表清单为空——JVM 指标不是业务数据，所以不受"能读哪些表"约束，
 * 但仍受动作约束：角色配置里没有这个动作的身份看不到运行时指标。漏登记的后果是拒绝而不是放行。
 */
@AutoConfiguration
public class SystemStatusAutoConfiguration {

    /** 这一层导出的动作名。角色配置里写的是它。 */
    public static final String ACTION = "read_system";

    /**
     * 工具名。权限表与档位表都按它登记，写两份字面量的后果是"其中一处改了、另一处没改"，
     * 而档位那一处漏改的表现是启动失败：点名的名字与工具真实名字对不上。
     */
    private static final String TOOL_NAME = "systemStatus";

    /** 工具对象：默认采样器，不带任何测试替身。 */
    @Bean
    public ToolCallbackProvider systemStatusToolCallbackProvider() {
        return MethodToolCallbackProvider.builder().toolObjects(new SystemStatusTools()).build();
    }

    /** 权限登记：动作 {@code read_system}，表清单为空。 */
    @Bean
    public ToolPermissionRegistry systemStatusPermissionRegistry(ToolCallbackProvider systemStatusToolCallbackProvider) {
        return ToolPermissionRegistry.of(Map.of(TOOL_NAME, ToolPermission.of(ACTION)));
    }

    /**
     * 档位：{@link ToolTier#MINIMAL}。
     *
     * <p>读的是本进程自己的指标，不碰业务数据、不出网、没有副作用，所以任何一个档位都该有它——
     * "这个服务现在什么样"是连只做聊天的部署也会被问到的问题。
     */
    @Bean
    public ToolTierRegistry systemStatusTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(TOOL_NAME), ToolTier.MINIMAL);
    }
}
