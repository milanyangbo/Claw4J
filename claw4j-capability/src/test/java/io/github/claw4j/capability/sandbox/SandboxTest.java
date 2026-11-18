package io.github.claw4j.capability.sandbox;

import io.github.claw4j.capability.exec.ExecProperties;
import io.github.claw4j.capability.exec.SandboxedExecBackend;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;
import io.github.claw4j.capability.process.ShellCommandRunner;
import io.github.claw4j.capability.script.ProcessRunnerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 沙箱这一层：开关怎么判、argv 怎么拼、探不通时怎么失败。
 *
 * <p>真隔离那两条带 {@code @Tag("docker")}（方法级，不是类级）：这一类里还有六条不需要容器，
 * 类级打标签会把它们一起排除出回归。方法级标签的排除效果与类级一致，2026-11-18 实测过
 * （一条带标签的方法在默认 {@code excludedGroups} 下没有跑）。
 *
 * <p>本机 Docker Hub 不通时，真隔离那两条用本地已有的镜像跑：
 * {@code ./mvnw -Pdocker -pl claw4j-capability test -Dtest=SandboxTest -Dclaw4j.test.sandbox.image=<本地镜像>}。
 */
class SandboxTest {

    /** 真隔离用的镜像：要带 POSIX shell。默认值在 Hub 通时可用，通不了就换本地已有的那个。 */
    private static final String IMAGE = System.getProperty("claw4j.test.sandbox.image", "alpine:3");

    @Test
    @DisplayName("mode 不配时拿到的就是今天那个工厂本身：默认零变化要能被断言，不是靠读代码相信")
    void offKeepsRunningOnHost() {
        assertThat(SandboxBackends.scriptFactory(SandboxProperties.defaults(), null))
                .as("off 时返回包装层的话，这条判据就变成「行为看起来一样」，而它必须是「就是同一个」")
                .isSameAs(ProcessRunnerFactory.SANDBOXED);
    }

    @Test
    @DisplayName("mode 三档各自对主/非主会话的判定：non-main 只看非主会话，其余两档不看会话")
    void modeDecidesPerSession() {
        SandboxProperties off = properties(SandboxProperties.Mode.OFF, SandboxProperties.Backend.DOCKER,
                SandboxProperties.WorkspaceAccess.RO);
        SandboxProperties nonMain = properties(SandboxProperties.Mode.NON_MAIN, SandboxProperties.Backend.DOCKER,
                SandboxProperties.WorkspaceAccess.RO);
        SandboxProperties all = properties(SandboxProperties.Mode.ALL, SandboxProperties.Backend.DOCKER,
                SandboxProperties.WorkspaceAccess.RO);

        assertThat(off.sandboxed(SandboxSession.MAIN)).isFalse();
        assertThat(off.sandboxed(SandboxSession.OTHER)).isFalse();
        assertThat(nonMain.sandboxed(SandboxSession.MAIN))
                .as("主对话留在宿主：那是人直接看着的那一段")
                .isFalse();
        assertThat(nonMain.sandboxed(SandboxSession.OTHER)).isTrue();
        assertThat(all.sandboxed(SandboxSession.MAIN)).isTrue();
        assertThat(all.sandboxed(SandboxSession.OTHER)).isTrue();
    }

    @Test
    @DisplayName("容器 argv 是数组：命令里带空格、分号也只是一个元素，宿主那条 sh 从不参与")
    void argvIsNotAShellString(@TempDir Path workspace) throws IOException {
        Path root = Files.createDirectories(workspace.resolve("pkg"));
        ContainerSpec spec = new ContainerSpec("alpine:3", SandboxProperties.WorkspaceAccess.RO, root);

        List<String> argv = spec.argv(List.of("/workspace/run.sh", "a; rm -rf /", "x y"), Map.of());

        assertThat(argv).startsWith("run", "--rm", "-i", "--workdir", "/workspace");
        assertThat(argv).contains("--volume", root + ":/workspace:ro");
        assertThat(argv).containsSequence("alpine:3", "/workspace/run.sh");
        assertThat(argv)
                .as("参数带分号与空格也各自占一个元素：数组形态里没有解释器这一环")
                .endsWith("a; rm -rf /", "x y");
        assertThat(argv)
                .as("拼成一整条命令字符串就说明有人又在构造命令行：那等于把注入面放回来")
                .noneMatch(element -> element.contains("--volume ") || element.startsWith("/bin/sh"));
    }

    @Test
    @DisplayName("镜像、挂载方式、容器工作目录都来自配置，不写死在 Java 里")
    void containerSpecComesFromProperties(@TempDir Path workspace) throws IOException {
        Path root = Files.createDirectories(workspace.resolve("pkg"));
        SandboxProperties readWrite = new SandboxProperties(SandboxProperties.Mode.ALL,
                SandboxProperties.Backend.DOCKER, SandboxProperties.WorkspaceAccess.RW, "busybox:1.36", "podman");
        ContainerSpec rw = ContainerSpec.of(readWrite, root);

        assertThat(rw.image()).isEqualTo("busybox:1.36");
        assertThat(rw.argv(List.of("/workspace/run.sh"), Map.of())).contains("--volume", root + ":/workspace:rw");
        assertThat(rw.workdir()).isEqualTo("/workspace");

        SandboxProperties noMount = new SandboxProperties(SandboxProperties.Mode.ALL,
                SandboxProperties.Backend.DOCKER, SandboxProperties.WorkspaceAccess.NONE, null, null);
        ContainerSpec none = ContainerSpec.of(noMount, root);

        assertThat(none.argv(List.of("sh", "-c", "pwd"), Map.of()))
                .as("不挂载时不该出现 --volume：写了一个空挂载等于把宿主的空目录挂进去")
                .doesNotContain("--volume");
        assertThat(none.workdir()).isEqualTo("/");
    }

    @Test
    @DisplayName("配了 docker 而守护进程探不通 → 启动失败，并点名是守护进程那条；镜像不在则点名镜像")
    void unavailableDockerFailsFast(@TempDir Path workspace) {
        DockerCli noDaemon = DockerCli.of("docker", workspace,
                fakeRunner(request -> request.program().equals("docker") && request.argv().contains("info")
                        ? ProcessOutcome.completed(1, "", "Cannot connect to the Docker daemon at unix:///var/run/docker.sock")
                        : ProcessOutcome.completed(0, "abc", "")));

        assertThatThrownBy(() -> noDaemon.requireUsable("alpine:3"))
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("守护进程")
                .hasMessageContaining("不会悄悄回落到宿主");

        DockerCli noImage = DockerCli.of("docker", workspace,
                fakeRunner(request -> request.argv().contains("image")
                        ? ProcessOutcome.completed(1, "", "No such image: alpine:3")
                        : ProcessOutcome.completed(0, "29.9.2", "")));

        assertThatThrownBy(() -> noImage.requireUsable("alpine:3"))
                .as("两条探测要分开报：修法一个是把 docker 起来，一个是把镜像拉下来")
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("镜像")
                .hasMessageContaining("alpine:3");
    }

    @Test
    @DisplayName("配了 ssh、或配了 non-main，都启动失败：留一个配了不生效的取值比不支持更坏")
    void unimplementedBackendIsRejected() {
        SandboxProperties ssh = properties(SandboxProperties.Mode.ALL, SandboxProperties.Backend.SSH,
                SandboxProperties.WorkspaceAccess.RO);
        assertThatThrownBy(ssh::requireUsable)
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("ssh")
                .hasMessageContaining("还没有实现");

        SandboxProperties nonMain = properties(SandboxProperties.Mode.NON_MAIN, SandboxProperties.Backend.DOCKER,
                SandboxProperties.WorkspaceAccess.RO);
        assertThatThrownBy(nonMain::requireUsable)
                .as("「这一段算不算主对话」要在清单那一层判，脚本层与 exec 层答不出来")
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("non-main")
                .hasMessageContaining("票 06");
    }

    @Test
    @Tag("docker")
    @DisplayName("真隔离：脚本跑在另一台容器里，工作目录是 /workspace，主机名不是宿主那个")
    void commandRunsInsideContainer(@TempDir Path workspace) throws IOException {
        Path root = Files.createDirectories(workspace.resolve("pkg"));
        Path entrypoint = script(root, "hostname; pwd");
        DockerCli cli = usableCli();
        String host = hostName();

        ProcessOutcome outcome = new DockerSandboxRunner(
                new ContainerSpec(IMAGE, SandboxProperties.WorkspaceAccess.RO, root), cli)
                .run(new ProcessRequest(List.of(entrypoint.toString()), 60_000));

        assertThat(outcome.exitCode()).as("docker 的 stderr：%s", outcome.stderr()).isZero();
        assertThat(outcome.status()).isEqualTo(ProcessOutcome.Status.COMPLETED);
        List<String> lines = outcome.stdout().strip().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).as("工作目录挂在 --workdir 上").isEqualTo("/workspace");
        assertThat(lines.get(0))
                .as("主机名与宿主相同就说明命令根本没进容器（宿主是 %s）", host)
                .isNotEqualTo(host);
    }

    @Test
    @Tag("docker")
    @DisplayName("容器里看不到宿主的环境变量：HOME 是镜像那个，不是跑测试这台机器的")
    void hostEnvironmentIsNotInherited(@TempDir Path workspace) throws IOException {
        String hostHome = System.getenv("HOME");
        assertThat(hostHome).as("这条用例拿宿主 HOME 当对照，取不到就没法判").isNotNull();
        Path root = Files.createDirectories(workspace.resolve("pkg"));
        Path entrypoint = script(root, "echo \"HOME=$HOME\"");
        DockerCli cli = usableCli();

        ProcessOutcome outcome = new DockerSandboxRunner(
                new ContainerSpec(IMAGE, SandboxProperties.WorkspaceAccess.RO, root), cli)
                .run(new ProcessRequest(List.of(entrypoint.toString()), 60_000));

        assertThat(outcome.stdout().strip())
                .as("宿主 HOME 出现在容器里，说明有人把整份环境照抄进去了")
                .doesNotContain(hostHome);
    }

    @Test
    @DisplayName("容器后端接上之后：判定之后整段交给它，宿主那条 /bin/sh 一次都不跑")
    void containerBackendTakesOverAfterThePolicyLayer(@TempDir Path workspace) {
        List<String> hostCalls = new java.util.ArrayList<>();
        // 宿主那条是真执行器：它要是被调到了，结果会是 "hi" 而不是 "in-container"，断言直接抓到。
        ShellCommandRunner host = new ShellCommandRunner(workspace);
        ExecBackend container = new ExecBackend() {

            @Override
            public ExecResult run(ExecSpec spec) {
                hostCalls.add(spec.command());
                return ExecResult.completed(0, "in-container", "");
            }

            @Override
            public ExecHandle start(ExecSpec spec) {
                throw new UnsupportedOperationException("这条用例只走同步那条");
            }
        };
        SandboxedExecBackend backend = new SandboxedExecBackend(
                ExecProperties.of(ExecProperties.Mode.FULL, workspace, List.of()), host,
                Map.of("CLAW4J_HOST_ONLY", "yes"), System.getProperty("user.name", ""),
                null, null, container);

        ExecResult result = backend.run(ExecSpec.of("echo hi"));

        assertThat(result.stdout()).isEqualTo("in-container");
        assertThat(hostCalls).as("命令只交出去一次，宿主那条路没有被顺手跑一遍").containsExactly("echo hi");
    }

    @Test
    @Tag("docker")
    @DisplayName("exec 侧用社区的 DockerCliExecBackend：托管容器真的起来，命令在容器里跑，宿主环境不进去")
    void execContainerBackendRunsInsideAContainer(@TempDir Path workspace) throws Exception {
        String hostHome = System.getenv("HOME");
        assertThat(hostHome).as("这条用例拿宿主 HOME 当对照，取不到就没法判").isNotNull();
        SandboxProperties properties = new SandboxProperties(SandboxProperties.Mode.ALL,
                SandboxProperties.Backend.DOCKER, SandboxProperties.WorkspaceAccess.RO, IMAGE, "docker");
        ExecBackend container = SandboxBackends.execContainer(properties, workspace);
        try {
            assertThat(container).as("mode=all 时 exec 侧必须拿到容器后端，而不是 null").isNotNull();

            ExecResult hostname = container.run(ExecSpec.of("hostname"));
            assertThat(hostname.exitCode()).as("stderr：%s", hostname.stderr()).isZero();
            assertThat(hostname.stdout().strip())
                    .as("主机名与宿主相同就说明命令没进容器")
                    .isNotEqualTo(hostName());

            ExecResult env = container.run(ExecSpec.of("echo \"HOME=$HOME\""));
            assertThat(env.stdout().strip())
                    .as("库不继承宿主环境；这里出现宿主 HOME 就说明有人给它塞了一份")
                    .doesNotContain(hostHome);
        }
        finally {
            if (container instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }    }

    private static SandboxProperties properties(SandboxProperties.Mode mode, SandboxProperties.Backend backend,
                                                SandboxProperties.WorkspaceAccess access) {
        return new SandboxProperties(mode, backend, access, "alpine:3", "docker");
    }

    /** 探测通过的那一份：先探真 docker，探不过就直接失败——这条用例本来就要真容器。 */
    private static DockerCli usableCli() {
        SandboxProperties properties = new SandboxProperties(SandboxProperties.Mode.ALL,
                SandboxProperties.Backend.DOCKER, SandboxProperties.WorkspaceAccess.RO, IMAGE, "docker");
        DockerCli cli = DockerCli.resolve(properties);
        cli.requireUsable(IMAGE);
        return cli;
    }

    private static Path script(Path root, String body) throws IOException {
        Path script = root.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        }
        catch (IOException failure) {
            return "";
        }
    }

    /**
     * 假执行器：按 argv 决定答什么。用它验"探测失败怎么报"，不必真的有 docker。
     *
     * @param answer 给定请求给出的结果
     * @return 只答不跑的 ProcessRunner
     */
    private static ProcessRunner fakeRunner(java.util.function.Function<ProcessRequest, ProcessOutcome> answer) {
        return new ProcessRunner() {
            @Override
            public ProcessOutcome run(ProcessRequest request) {
                return answer.apply(request);
            }
        };
    }
}
