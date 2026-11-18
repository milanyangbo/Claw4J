package io.github.claw4j.capability.exec;

import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.exec.LocalExecBackend;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Arrays;
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

    /**
     * 宿主上的执行后端：库的 {@code LocalExecBackend}，工作目录取配置里的 workspace；目录不存在就启动失败。
     *
     * <p>三样由这里定死：工作目录（就是 workspace，换目录由模型在命令里 {@code cd} 表达）、
     * 环境（整份清空后只放进程自己的那份 —— {@code cleanEnvironment(true)} 加 {@code environment(..)}）、
     * 解释器（显式给 {@code sh}，不用库默认的 bash：ADR-0002 的口径是不绑定 bash 专有语法）。
     *
     * <p>超时上限不在这里：{@code ExecSpec} 自己 clamp（默认 2 分钟、封顶 10 分钟）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ExecBackend execHostBackend(ExecProperties properties) {
        Path workspace = properties.workspace().toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalStateException("claw4j.tools.exec.workspace 不是一个已存在的目录：" + workspace
                    + "。exec 只在这个目录里跑命令；建好它，或者把这条配置指到别处。");
        }
        return LocalExecBackend.builder()
                .workingDirectory(workspace)
                .cleanEnvironment(true)
                .environment(System.getenv())
                .shellCommand("/bin/sh", "-c")
                .build();
    }

    /**
     * 工具对象：库的 {@link ShellTools}，执行后端换成带了判定的这一份。
     *
     * <p>审批端口与复核器从容器里取（票 05）：端口实现在 agent 侧（它要落库、要发卡片），
     * 这里只认接口。取不到时后端退到 {@link ExecApprovalPort#denyAll()}——需要人批的命令
     * 这一轮拿不到结论，按 {@code askFallback} 处置（默认拒）。
     *
     * <p><b>命令只跑在宿主上</b>（2026-11-18 裁决：容器化整体回滚，等上游给
     * {@code DockerCliExecBackend.Builder} 加限额参数）。这里不留"以后接容器"的空洞参数：
     * 一个恒为 {@code null} 的字段会让人以为那条路已经通了。
     *
     * @param properties {@code claw4j.tools.exec} 的配置
     * @param execHostBackend 宿主上的执行后端
     * @param approvals 审批端口，可能不存在
     * @param reviewer 复核器，可能不存在
     * @return 库的工具对象
     */
    @Bean
    @ConditionalOnMissingBean
    public ShellTools shellTools(ExecProperties properties, ExecBackend execHostBackend,
                                 ObjectProvider<ExecApprovalPort> approvals,
                                 ObjectProvider<ExecReviewer> reviewer) {
        return ShellTools.builder()
                .execBackend(new SandboxedExecBackend(properties, execHostBackend,
                        System.getenv().keySet(), System.getProperty("user.name", ""),
                        approvals.getIfAvailable(ExecApprovalPort::denyAll),
                        reviewer.getIfAvailable(ExecReviewer::fallback)))
                .build();
    }

    /**
     * 把 {@link ShellTools} 的三个方法暴露成工具，并给每个工具绑一层调用上下文。
     *
     * <p>上下文（谁在问、哪段对话）只在框架的 {@code ToolContext} 上有，而审批要它来落一条请求；
     * 判定发生在后端的 {@code run} 里，那里拿不到 {@code ToolContext}。所以包在这一层，
     * 见 {@link ExecContextToolCallback}。
     *
     * @param shellTools 库的工具对象
     * @return 绑了上下文的三个工具
     */
    @Bean
    public ToolCallbackProvider execToolCallbackProvider(ShellTools shellTools) {
        ToolCallbackProvider raw = MethodToolCallbackProvider.builder().toolObjects(shellTools).build();
        return () -> Arrays.stream(raw.getToolCallbacks())
                .map(ExecContextToolCallback::new)
                .toArray(ToolCallback[]::new);
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
