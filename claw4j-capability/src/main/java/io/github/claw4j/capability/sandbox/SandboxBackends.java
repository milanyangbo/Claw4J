package io.github.claw4j.capability.sandbox;

import io.github.claw4j.capability.process.CommandRunner;
import io.github.claw4j.capability.script.ProcessRunnerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 按配置挑实现：两条执行路径各自"宿主还是容器"只在这里判一次。
 *
 * <p>两个方法各对应一条已有接缝——脚本侧 {@link ProcessRunnerFactory}、exec 侧
 * {@link CommandRunner}——所以换实现不改调用方，调用方也不会各自判一遍 {@code mode}
 * （两处各判一次时，加第三档必然只改一边）。
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
        if (!properties.containerized()) {
            return ProcessRunnerFactory.SANDBOXED;
        }
        if (cli == null) {
            throw new SandboxUnavailableException("沙箱开着（claw4j.tools.sandbox.mode=" + properties.mode()
                    + "）但 docker CLI 没有装配起来：检查启动日志里那条探测失败的原因。");
        }
        if (properties.workspaceAccess() == SandboxProperties.WorkspaceAccess.NONE) {
            throw new SandboxUnavailableException("claw4j.tools.sandbox.workspace-access=none 不能用在脚本层："
                    + "脚本入口就在包目录里（. /workspace），不挂载的话容器里连要跑的那个文件都没有。"
                    + "脚本层用 ro 或 rw，none 留给 exec。");
        }
        return pkg -> new DockerSandboxRunner(ContainerSpec.of(properties, pkg.root()), cli);
    }

    /**
     * exec 侧的执行器。
     *
     * @param properties      {@code claw4j.tools.sandbox} 的配置
     * @param cli             docker CLI；{@code mode=off} 时可以是 {@code null}
     * @param host            宿主那条 {@code /bin/sh} 执行器
     * @param hostWorkspace   宿主上的工作区，挂进容器并在提示里显示
     * @return 按配置挑好的执行器
     * @throws SandboxUnavailableException 开了沙箱却没有 CLI
     */
    public static CommandRunner execRunner(SandboxProperties properties, DockerCli cli, CommandRunner host,
                                           Path hostWorkspace) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(hostWorkspace, "hostWorkspace");
        if (!properties.containerized()) {
            return host;
        }
        if (cli == null) {
            throw new SandboxUnavailableException("沙箱开着（claw4j.tools.sandbox.mode=" + properties.mode()
                    + "）但 docker CLI 没有装配起来：检查启动日志里那条探测失败的原因。");
        }
        return new ContainerCommandRunner(ContainerSpec.of(properties, hostWorkspace), cli, hostWorkspace);
    }
}
