package io.github.claw4j.capability.exec;

import io.github.claw4j.capability.process.ShellCommandRunner;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code exec} 这一层的装配（票 01）。
 *
 * <p><b>开关只有 {@code mode} 一个</b>：{@code deny} 时一个 bean 都不产出。没有额外的
 * {@code enabled}——两个开关管同一件事，迟早出现"enabled=true 但 mode=deny"要人猜（pitfalls 第十条）。
 *
 * <p><b>工作区不存在就启动失败并点名</b>，与"目录为空的知识库"同一个取向：一个跑不了命令的
 * {@code exec} 在模型那边的表现是"这个工具老是失败"，而真实原因是部署配错了地方。
 *
 * <p><b>权限登记在这里</b>（ADR-0007）：动作 {@code exec_command}，表清单为空（它不碰业务表）。
 * {@code mode=ask} 时登记成"要人先批"，其余档登记成普通动作——审批这一层只看这个标注，
 * 它不读 {@code mode}，两处各判一次就会分叉。
 */
@AutoConfiguration
@Conditional(ExecAutoConfiguration.NotDenied.class)
@EnableConfigurationProperties(ExecProperties.class)
public class ExecAutoConfiguration {

    /** 权限表与档位表都按这个工具名登记，写两份字面量迟早对不上。 */
    public static final String TOOL_NAME = "exec";

    /** 动作名。角色配置里写的是它，默认只给 admin。 */
    public static final String ACTION = "exec_command";

    @Bean
    @ConditionalOnMissingBean
    public ShellCommandRunner shellCommandRunner(ExecProperties properties) {
        Path workspace = properties.workspace().toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalStateException("claw4j.tools.exec.workspace 不是一个已存在的目录：" + workspace
                    + "。exec 只在这个目录里跑命令；建好它，或者把这条配置指到别处。");
        }
        return new ShellCommandRunner(workspace);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecTools execTools(ExecProperties properties, ShellCommandRunner shellCommandRunner) {
        return new ExecTools(properties, shellCommandRunner);
    }

    @Bean
    public ToolCallbackProvider execToolCallbackProvider(ExecTools execTools) {
        return MethodToolCallbackProvider.builder().toolObjects(execTools).build();
    }

    @Bean
    public ToolPermissionRegistry execPermissionRegistry(ExecProperties properties) {
        ToolPermission permission = properties.mode() == ExecProperties.Mode.ASK
                ? ToolPermission.requiringApproval(ACTION)
                : ToolPermission.of(ACTION);
        return ToolPermissionRegistry.of(Map.of(TOOL_NAME, permission));
    }

    /**
     * 档位：{@link ToolTier#CODING}，与 {@code run_script} 同档。
     *
     * <p>这一档存在的理由就是 {@code MESSAGING} 能把"跑代码"整类拿掉——exec 属于那一类，
     * 放进 {@code MESSAGING} 就等于给"只做消息"的部署开了一个 shell。
     */
    @Bean
    public ToolTierRegistry execTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(TOOL_NAME), ToolTier.CODING);
    }

    /** {@code mode=deny} 就是不装配。只认这一个值，其余（含写错的）交给绑定去报错。 */
    static final class NotDenied implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String mode = context.getEnvironment().getProperty("claw4j.tools.exec.mode", "full");
            return mode == null || !"deny".equalsIgnoreCase(mode.strip());
        }
    }
}
