package io.github.claw4j.capability.sandbox;

import io.github.claw4j.capability.script.ProcessRunnerFactory;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.exec.docker.DockerCliExecBackend;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 按配置挑实现：两条执行路径各自"宿主还是容器"只在这里判一次。
 *
 * <p>两条路径用的是两套东西，不是"一套东西写两遍"：
 *
 * <ul>
 *   <li><b>exec</b>：社区给的 {@link DockerCliExecBackend}（模块
 *       {@code spring-ai-agent-utils-docker-cli}）。托管容器、后台句柄、超时在容器内杀 PID、
 *       JVM 退出回收、崩溃后按 label 清理，全在库里。</li>
 *   <li><b>脚本</b>：本项目的 {@link DockerSandboxRunner}。那一侧接缝是 argv
 *       （{@code ProcessRunner}），而库的 {@code ExecSpec} 是一条命令字符串；把脚本参数
 *       拼成字符串交给 {@code sh} 等于让解释器再看一遍，正是 ADR-0002 要挡的。</li>
 * </ul>
 *
 * <p>{@code mode=off} 时返回的<b>就是今天那个实现本身</b>，不是"一个行为相同的包装"：
 * 默认零变化这条判据要能被断言（{@code SandboxTest#offKeepsRunningOnHost}），
 * 包装一层之后"零变化"只能靠读代码相信。
 */
public final class SandboxBackends {

    private SandboxBackends() {
    }

    /**
     * 脚本侧的执行后端工厂。
     *
     * @param properties {@code claw4j.tools.sandbox} 的配置
     * @param cli        docker CLI；{@code mode=off} 时可以是 {@code null}
     * @return 按配置挑好的工厂
     * @throws SandboxUnavailableException 开了沙箱却没有 CLI，或挂载方式是 {@code none}
     */
    public static ProcessRunnerFactory scriptFactory(SandboxProperties properties, DockerCli cli) {
        Objects.requireNonNull(properties, "properties");
        properties.requireUsable();
        if (!properties.containerized()) {
            return ProcessRunnerFactory.SANDBOXED;
        }
        if (cli == null) {
            throw new SandboxUnavailableException("沙箱开着（claw4j.tools.sandbox.mode=" + properties.mode()
                    + "）但 docker CLI 没有装配起来：检查启动日志里那条探测失败的原因。");
        }
        if (properties.workspaceAccess() == SandboxProperties.WorkspaceAccess.NONE) {
            throw new SandboxUnavailableException("claw4j.tools.sandbox.workspace-access=none 不能用在脚本层："
                    + "脚本入口就在包目录里（" + SandboxProperties.CONTAINER_WORKDIR + "），"
                    + "不挂载的话容器里连要跑的那个文件都没有。脚本层用 ro 或 rw，none 留给 exec。");
        }
        return pkg -> new DockerSandboxRunner(ContainerSpec.of(properties, pkg.root()), cli);
    }

    /**
     * exec 侧的容器后端：{@code mode=off} 时返回 {@code null}，表示继续跑在宿主上。
     *
     * <p>{@code build()} 会真的把托管容器建起来并在起不来时 fail-fast，所以这一处**就是** exec 侧的
     * 可用性探测，不必再单独探一次。
     *
     * <p>挂载只支持读写：库的 {@code mount(Path, String)} 没有只读选项。所以
     * {@code workspace-access=ro} 在 exec 侧不生效——这一条会由装配处打一条 WARN 说出来，
     * 不静默（只读要等 P17b/c 的限额那一层）。没有 {@code workspace-access=none} 的对应写法：
     * 不挂载的话模型看不见工作区，exec 也就没有意义。
     *
     * @param properties {@code claw4j.tools.sandbox} 的配置
     * @param workspace  宿主上的工作区，挂进容器的 {@value SandboxProperties#CONTAINER_WORKDIR}
     * @return 容器后端；{@code mode=off} 时为 {@code null}
     */
    public static ExecBackend execContainer(SandboxProperties properties, Path workspace) {
        Objects.requireNonNull(properties, "properties");
        properties.requireUsable();
        if (!properties.containerized()) {
            return null;
        }
        Objects.requireNonNull(workspace, "workspace");
        return DockerCliExecBackend.builder()
                .dockerCommand(properties.dockerBinary())
                .image(properties.image())
                .mount(workspace.toAbsolutePath().normalize(), SandboxProperties.CONTAINER_WORKDIR)
                .containerWorkingDirectory(SandboxProperties.CONTAINER_WORKDIR)
                .build();
    }
}
