package io.github.claw4j.capability.sandbox;

import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本层的容器后端：把一次脚本执行翻成 {@code docker run}，交给 docker CLI。
 *
 * <p>它实现的是脚本层那条已有的接缝 {@link ProcessRunner}，所以 {@code ScriptPackageRunner}
 * 一行不动——那个 {@code ProcessRunnerFactory} 的注释里就写着"换容器、换远端执行器，
 * 改的都是这个实现"。
 *
 * <p>四条约束仍然先生效：{@link io.github.claw4j.capability.script.SandboxPolicy} 判超时、非 root、
 * 参数不越界，这一层只管"在哪跑"。顺序不能反：容器起在参数校验之前，等于用一台容器去跑一条
 * 本来就不该跑的路径。
 *
 * <p>容器里不放宿主的环境变量（{@code PATH} 也不放）：镜像自带的那份才是对的，把宿主的
 * {@code PATH} 塞进去只会得到"命令找不到"的另一种写法。四条约束里的 {@code PATH-only}
 * 在容器里由镜像的 {@code ENV} 表达。
 */
public final class DockerSandboxRunner implements ProcessRunner {

    private final ContainerSpec spec;

    private final DockerCli cli;

    /**
     * 建一个容器后端。
     *
     * @param spec 镜像、挂载方式与宿主目录
     * @param cli  docker CLI
     */
    public DockerSandboxRunner(ContainerSpec spec, DockerCli cli) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.cli = Objects.requireNonNull(cli, "cli");
    }

    /**
     * 跑一次脚本包，同步等结束。
     *
     * <p>argv 里落在包目录下的绝对路径翻成容器路径（入口脚本就是这种），其余原样：
     * 参数在 {@code SandboxPolicy#resolveInside} 那一步已经是包内的相对路径。
     *
     * @param request 脚本层给的 argv 与超时
     * @return docker CLI 的结果；脚本的退出码就是它的退出码
     */
    @Override
    public ProcessOutcome run(ProcessRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> inner = new ArrayList<>(request.argv().size());
        for (String argument : request.argv()) {
            inner.add(containerArgument(argument));
        }
        return cli.run(spec, List.copyOf(inner), request.timeoutMillis());
    }

    /**
     * 容器规格，给日志与排障用。
     *
     * @return 这一层的规格
     */
    public ContainerSpec spec() {
        return spec;
    }

    private String containerArgument(String argument) {
        try {
            Path path = Path.of(argument);
            return path.isAbsolute() ? spec.containerPath(path) : argument;
        }
        catch (InvalidPathException notAPath) {
            // 模型给的普通字符串可能根本不是合法路径（含 \0 之类），那不是错误，原样交进去。
            return argument;
        }
    }
}
