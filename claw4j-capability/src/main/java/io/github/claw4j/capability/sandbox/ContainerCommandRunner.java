package io.github.claw4j.capability.sandbox;

import io.github.claw4j.capability.process.CommandRunner;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ShellProcess;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code exec} 那侧的容器实现：一条 shell 命令 → {@code docker run ... sh -c <命令>}。
 *
 * <p>容器里用 {@code sh -c} 是对的，而且与脚本层相反：那条字符串是<strong>模型给的</strong>，
 * 它要的就是管道、重定向、{@code &&}（{@code ShellCommandRunner} 的类注释里写着这个区别）。
 * 不同的是宿主那一侧：{@code docker run} 的每个参数各占一个数组元素，宿主的 {@code /bin/sh}
 * 从不参与——所以命令里的引号、分号不会被解释两次。
 *
 * <p>工作目录报的是<strong>宿主</strong>那个：审批请求、拒绝信息、日志里读到的都是它，写容器路径会让人
 * 在宿主机上找不到对应目录。容器里那个固定是 {@code /workspace}，由 {@link ContainerSpec} 给。
 */
public final class ContainerCommandRunner implements CommandRunner {

    private final ContainerSpec spec;

    private final DockerCli cli;

    private final Path workingDirectory;

    /**
     * 建一个容器执行器。
     *
     * @param spec             容器规格
     * @param cli              docker CLI
     * @param workingDirectory 宿主上的工作目录，只用于提示与判定
     */
    public ContainerCommandRunner(ContainerSpec spec, DockerCli cli, Path workingDirectory) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.cli = Objects.requireNonNull(cli, "cli");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    /**
     * 同步跑完一条命令。
     *
     * @param command       整条 shell 命令，交给容器里的 {@code sh -c}
     * @param directory     宿主工作目录，容器模式下不参与执行
     * @param environment   容器里能看到的全部环境变量
     * @param timeoutMillis 墙上时钟上限
     * @return 执行结果
     */
    @Override
    public ProcessOutcome run(String command, Path directory, Map<String, String> environment, long timeoutMillis) {
        return cli.run(spec, inner(command), environment, timeoutMillis);
    }

    /**
     * 起一个后台容器。
     *
     * @param command     整条 shell 命令
     * @param directory   宿主工作目录，容器模式下不参与执行
     * @param environment 容器里能看到的全部环境变量
     * @return 后台句柄
     */
    @Override
    public ShellProcess start(String command, Path directory, Map<String, String> environment) {
        return cli.start(spec, inner(command), environment);
    }

    /**
     * 宿主上的工作目录。
     *
     * @return 构造时给定的路径
     */
    @Override
    public Path workingDirectory() {
        return workingDirectory;
    }

    private static List<String> inner(String command) {
        Objects.requireNonNull(command, "command");
        return List.of("sh", "-c", command);
    }
}
