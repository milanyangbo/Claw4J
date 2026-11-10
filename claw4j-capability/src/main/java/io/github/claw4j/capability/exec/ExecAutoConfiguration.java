package io.github.claw4j.capability.exec;

import io.github.claw4j.capability.process.ShellCommandRunner;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springaicommunity.agent.tools.ShellTools;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * exec 这一层的装配：工具形态来自 Spring AI，沙箱与权限登记由这里给出（票 01）。
 *
 * <p>工具本身是库的（{@link ShellTools}：{@code Bash} / {@code BashOutput} / {@code KillShell}），
 * 这一层只提供两端：沙箱后端（{@link SandboxedExecBackend}）与权限登记。自己写一个 {@code @Tool exec}
 * 也做得到，但那要重做输出截断、后台句柄、{@code bash_id} 命名这些与"准不准跑"无关的东西。
 *
 * <p>开关只有 {@code mode} 一个：{@code deny} 时一个 bean 都不产出。没有额外的 {@code enabled}——
 * 两个开关管同一件事，迟早出现"enabled=true 但 mode=deny"要人猜（pitfalls 第十条）。
 *
 * <p>工作区不存在就启动失败并点名：一个跑不了命令的 {@code exec} 在模型那边表现为"这个工具老是失败"，
 * 而真实原因是部署配错了地方。
 *
 * <p>权限登记在这里（ADR-0007）：动作 {@code exec_command}，表清单为空。三个工具名一起登记——
 * {@code BashOutput} 与 {@code KillShell} 只读已经跑起来的那个命令，但它们同样碰得到它的产物。
 */
@AutoConfiguration
@Conditional(ExecAutoConfiguration.NotDenied.class)
@EnableConfigurationProperties(ExecProperties.class)
public class ExecAutoConfiguration {

    /**
     * 三个工具名，取自 {@link ShellTools} 的 {@code @Tool(name = …)}。
     *
     * <p>与 OpenClaw 的对应：{@code Bash} 对 {@code exec}，{@code BashOutput} 与 {@code KillShell}
     * 对 {@code process}（{@code docs/tools/exec.md:9}）。名字是库给的，改不了：改了就得再包一层工具。
     */
    public static final List<String> TOOL_NAMES = List.of("Bash", "BashOutput", "KillShell");

    /** 动作名。角色配置里写的是它，默认只给 admin。 */
    public static final String ACTION = "exec_command";

    /** 起进程的执行器，工作目录取配置里的 workspace；目录不存在就启动失败。 */
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

    /** 工具对象：库的 {@link ShellTools}，执行后端换成沙箱这一份。 */
    @Bean
    @ConditionalOnMissingBean
    public ShellTools shellTools(ExecProperties properties, ShellCommandRunner shellCommandRunner) {
        return ShellTools.builder()
                .execBackend(new SandboxedExecBackend(properties, shellCommandRunner))
                .build();
    }

    /** 把 {@link ShellTools} 的三个方法暴露成工具。 */
    @Bean
    public ToolCallbackProvider execToolCallbackProvider(ShellTools shellTools) {
        return MethodToolCallbackProvider.builder().toolObjects(shellTools).build();
    }

    /** 权限登记：动作 {@code exec_command}，{@code ask} 档附带"需要人批"。 */
    @Bean
    public ToolPermissionRegistry execPermissionRegistry(ExecProperties properties) {
        ToolPermission permission = properties.mode() == ExecProperties.Mode.ASK
                ? ToolPermission.requiringApproval(ACTION)
                : ToolPermission.of(ACTION);
        Map<String, ToolPermission> permissions = new LinkedHashMap<>();
        for (String name : TOOL_NAMES) {
            permissions.put(name, permission);
        }
        return ToolPermissionRegistry.of(permissions);
    }

    /**
     * 档位：{@link ToolTier#CODING}，与 {@code run_script} 同档。
     *
     * <p>这一档存在的理由就是 {@code MESSAGING} 能把"跑代码"整类拿掉——exec 属于那一类，
     * 放进 {@code MESSAGING} 就等于给"只做消息"的部署开了一个 shell。
     */
    @Bean
    public ToolTierRegistry execTierRegistry() {
        return ToolTierRegistry.sameFor(TOOL_NAMES, ToolTier.CODING);
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
