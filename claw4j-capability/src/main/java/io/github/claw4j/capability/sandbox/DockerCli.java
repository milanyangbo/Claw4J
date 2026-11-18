package io.github.claw4j.capability.sandbox;

import io.github.claw4j.capability.process.LocalProcessRunner;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 找得到、探得通、跑得起来的 {@code docker}。
 *
 * <p><b>它只服务脚本层。</b> {@code exec} 那侧的容器由社区的
 * {@code DockerCliExecBackend}（{@code spring-ai-agent-utils-docker-cli}）负责——它做托管容器、
 * 后台句柄、超时后在容器内杀 PID、JVM 退出时回收，这些都不该再写一遍。
 * 脚本层用不了它：那一侧的接口是 {@code ExecSpec}（一条命令字符串），而脚本的参数必须是
 * argv（ADR-0002，参数不能被解释器再看一遍）。
 *
 * <p>为什么是 CLI 而不是 Java 的 docker 客户端：{@code docker run} 与任何别的命令没有区别，
 * 而 {@link LocalProcessRunner} 就是那条被架构测试点名过的接缝（全仓只有两个类碰
 * {@code ProcessBuilder}）。多引一个客户端库等于多开第三条起进程的路。
 */
public final class DockerCli {

    /** 探测的墙上时钟上限：冷启动的守护进程可能慢，但 15 秒还没应答就是没有。 */
    private static final long PROBE_TIMEOUT_MILLIS = 15_000;

    private final String binary;

    private final Path workingDirectory;

    private final ProcessRunner runner;

    private DockerCli(String binary, Path workingDirectory, ProcessRunner runner) {
        this.binary = Objects.requireNonNull(binary, "binary");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * 测试用的一档：docker 换成任何一条能应答的"程序"，探测与 argv 拼装照样成立。
     *
     * @param binary           程序名或绝对路径
     * @param workingDirectory docker CLI 在哪个目录里起
     * @param runner           真正起进程的执行器
     * @return 指向这套执行器的 CLI
     */
    static DockerCli of(String binary, Path workingDirectory, ProcessRunner runner) {
        return new DockerCli(binary, workingDirectory, runner);
    }

    /**
     * 按配置找到 docker 并建好执行器：找不到就启动失败，不退化成"用宿主跑"。
     *
     * @param properties {@code claw4j.tools.sandbox} 的配置
     * @return 指向真实 docker 的 CLI
     * @throws SandboxUnavailableException 配的名字在 PATH 里找不到，或不是可执行文件
     */
    public static DockerCli resolve(SandboxProperties properties) {
        Path directory = Path.of(".").toAbsolutePath().normalize();
        String binary = resolveBinary(properties.dockerBinary());
        // 二进制形态：docker 是编译好的可执行文件，没有 shebang，脚本入口那条检查对它不成立。
        return new DockerCli(binary, directory, LocalProcessRunner.binaries(directory, clientEnvironment()));
    }

    /**
     * 探一次：守护进程在不在、镜像有没有。任何一条不过就抛，消息里点名是哪一条。
     *
     * <p>两条分开报，因为修法完全不同：一个是"把 docker 起来"，一个是"把镜像拉下来"。
     *
     * @param image 要用的镜像
     * @throws SandboxUnavailableException 守护进程不在，或镜像不在本机
     */
    public void requireUsable(String image) {
        ProcessOutcome daemon = probe(List.of(binary, "info", "--format", "{{.ServerVersion}}"));
        if (!succeeded(daemon)) {
            throw new SandboxUnavailableException("claw4j.tools.sandbox.backend=docker，但 docker 守护进程探不通"
                    + "（命令：" + binary + " info）。先把它跑起来（Docker Desktop / dockerd），"
                    + "或者把沙箱关掉（claw4j.tools.sandbox.mode=off）——"
                    + "这一层不会悄悄回落到宿主继续跑，那样你以为有隔离，其实没有。"
                    + firstLine(daemon));
        }
        ProcessOutcome imageCheck = probe(List.of(binary, "image", "inspect", image, "--format", "{{.Id}}"));
        if (!succeeded(imageCheck)) {
            throw new SandboxUnavailableException("claw4j.tools.sandbox.image=" + image + " 本机没有，也拉不下来"
                    + "（命令：" + binary + " image inspect " + image + "）。先 docker pull " + image
                    + "，或者换一个本地已有的镜像。"
                    + firstLine(imageCheck));
        }
    }

    /**
     * 跑一次容器，同步等结束。脚本层的容器是一次性的：{@code --rm}，跑完就删。
     *
     * <p>一次性容器与 exec 侧那个托管容器是两个决定：脚本是"一个包、一次调用、一个结果"，
     * 每次跑完就该消失；exec 是"一段会话里反复敲命令"，容器留着才有意义（那也是库里做的）。
     *
     * @param spec    容器规格
     * @param inner   容器里要执行的 argv
     * @param timeout 墙上时钟上限
     * @return docker CLI 的结果；脚本的退出码就是它的退出码
     */
    public ProcessOutcome run(ContainerSpec spec, List<String> inner, long timeout) {
        List<String> argv = new ArrayList<>();
        argv.add(binary);
        argv.addAll(spec.argv(inner, Map.of()));
        return runner.run(new ProcessRequest(List.copyOf(argv), timeout));
    }

    /** docker CLI 的路径：日志与失败信息里要能看出用的是哪一个。 */
    String binary() {
        return binary;
    }

    private ProcessOutcome probe(List<String> argv) {
        try {
            return runner.run(new ProcessRequest(argv, PROBE_TIMEOUT_MILLIS));
        }
        catch (RuntimeException failure) {
            // 探测本身起不来（二进制没了、没权限）也是"不可用"，不是"跳过探测"。
            return ProcessOutcome.completed(-1, "", failure.getMessage());
        }
    }

    private static boolean succeeded(ProcessOutcome outcome) {
        return outcome.status() == ProcessOutcome.Status.COMPLETED && outcome.exitCode() == 0;
    }

    private static String firstLine(ProcessOutcome outcome) {
        String text = outcome.stderr() == null || outcome.stderr().isBlank() ? outcome.stdout() : outcome.stderr();
        if (text == null || text.isBlank()) {
            return "";
        }
        String first = text.strip().lines().findFirst().orElse("");
        return first.isBlank() ? "" : "\n原始输出：" + first;
    }

    private static String resolveBinary(String configured) {
        if (configured.contains("/") || configured.contains("\\")) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (!Files.isExecutable(path)) {
                throw new SandboxUnavailableException("claw4j.tools.sandbox.docker-binary=" + configured
                        + " 不是一个可执行文件（解析成 " + path + "）。写绝对路径，或者写一个在 PATH 里的名字。");
            }
            return path.toString();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(File.pathSeparator)) {
                if (directory.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(directory).resolve(configured);
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }
        throw new SandboxUnavailableException("claw4j.tools.sandbox.docker-binary=" + configured
                + " 在 PATH 里找不到。装一个 docker CLI，或者把这一项写成绝对路径。"
                + "这一层不会因为找不到 docker 就改用宿主跑——那样隔离就没了，而且没人会知道。");
    }

    /**
     * docker CLI 自己的环境：**不是容器的环境**，容器那份由 {@link ContainerSpec#argv} 逐个 {@code --env} 给。
     *
     * <p>只挑它真正需要的四项：{@code PATH}（CLI 自己有时候要调 helper）、{@code HOME}
     * 与 {@code DOCKER_CONFIG}（上下文与凭据在 {@code ~/.docker} 下）、{@code DOCKER_HOST}
     * （没走默认 socket 时）。整个宿主环境照抄进来会把一堆凭据交出去，而这里连容器都还没起。
     */
    private static Map<String, String> clientEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String key : List.of("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONFIG")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                environment.put(key, value);
            }
        }
        return Map.copyOf(environment);
    }
}
