package io.github.claw4j.capability.builtin.system;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.capability.builtin.system.SystemStatusTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * 运行时状态这一层的装配。
 *
 * <p><b>它不挂任何开关</b>：这一层读的是本进程自己的指标，不依赖外部运行时、
 * 不碰业务数据、也没有副作用，于是"有没有这一层"不需要是一个决定——
 * 与脚本层（{@code claw4j.scripts.directory}）与生成器（{@code claw4j.generator.staging-directory}）
 * 那种"放开执行能力"的开关不是一回事。用开关管它只会让"少配一行"变成一种失效姿势。
 *
 * <p>权限登记在<b>这里</b>而不是写在 Agent 侧：只有写工具的人知道它要不要受表级约束
 * （ADR-0007）。这里登记的是 {@code read_system} 且表清单为空——
 * JVM 指标不是业务数据，于是它不受"你能读哪些表"约束，
 * 但<b>仍然受动作约束</b>：角色配置里没有这个动作的身份看不到运行时指标。
 * 漏登记的后果是拒绝而不是放行。
 */
@AutoConfiguration
public class SystemStatusAutoConfiguration {

    /** 这一层导出的动作名。角色配置里写的是它。 */
    public static final String ACTION = "read_system";

    @Bean
    public ToolCallbackProvider systemStatusToolCallbackProvider() {
        return MethodToolCallbackProvider.builder().toolObjects(new SystemStatusTools()).build();
    }

    @Bean
    public ToolPermissionRegistry systemStatusPermissionRegistry(ToolCallbackProvider systemStatusToolCallbackProvider) {
        return ToolPermissionRegistry.of(Map.of("systemStatus", ToolPermission.of(ACTION)));
    }
}
