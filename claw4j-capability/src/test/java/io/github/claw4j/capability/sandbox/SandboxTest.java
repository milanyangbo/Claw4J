package io.github.claw4j.capability.sandbox;

import io.github.claw4j.capability.exec.ExecProperties;
import io.github.claw4j.capability.exec.SandboxedExecBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;
import org.springaicommunity.agent.exec.LocalExecBackend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 沙箱这一层现在只剩 exec 一侧（2026-11-18，脚本层退化成技能之后）。
 *
 * <p>三件事要守住：{@code mode=off} 时一个容器都不建（默认零变化）；配了做不到的组合启动即失败、
 * 不静默降级；容器后端接上之后，判定通过的命令整段交给库的 {@link ExecBackend}，宿主那条一次都不跑。
 *
 * <p>脚本侧的容器后端（`DockerSandboxRunner` / `DockerCli`）随脚本层一起删了，所以这个文件从
 * 原来的十几条用例收到这几条——不是覆盖变少，是被测对象本身少了一半。
 */
class SandboxTest {

    private static final String IMAGE = "alpine:3";

    @Test
    @DisplayName("mode=off（默认）时 exec 侧不建容器：返回 null，交给宿主那条")
    void offKeepsRunningOnHost() {
        assertThat(SandboxBackends.execContainer(SandboxProperties.defaults(), Path.of(".")))
                .as("默认配置下不该建容器；建了就是默认零变化这条判据破了")
                .isNull();
    }

    @Test
    @DisplayName("backend=ssh 配了却做不到：启动即失败，不静默回落宿主")
    void sshBackendFailsFast() {
        SandboxProperties properties = new SandboxProperties(SandboxProperties.Mode.ALL,
                SandboxProperties.Backend.SSH, SandboxProperties.WorkspaceAccess.RW, IMAGE, "docker", null);

        assertThatThrownBy(properties::requireUsable)
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("ssh");
    }

    @Test
    @DisplayName("mode=non-main 还没接上：同样启动即失败")
    void nonMainModeFailsFast() {
        SandboxProperties properties = new SandboxProperties(SandboxProperties.Mode.NON_MAIN,
                SandboxProperties.Backend.DOCKER, SandboxProperties.WorkspaceAccess.RW, IMAGE, "docker", null);

        assertThatThrownBy(properties::requireUsable)
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("non-main");
    }

    @Test
    @DisplayName("容器后端接上之后：判定通过就整段交给它，宿主那条一次都不跑")
    void containerBackendTakesOverAfterThePolicyLayer(@TempDir Path workspace) {
        List<String> hostCalls = new ArrayList<>();
        // 宿主那条是真执行器：它要是被调到了，结果会是 "hi" 而不是 "in-container"，断言直接抓到。
        Map<String, String> hostEnv = Map.of("CLAW4J_HOST_ONLY", "yes");
        ExecBackend host = LocalExecBackend.builder()
                .workingDirectory(workspace)
                .cleanEnvironment(true)
                .environment(hostEnv)
                .shellCommand("/bin/sh", "-c")
                .build();
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
                hostEnv.keySet(), System.getProperty("user.name", ""),
                null, null, container);

        ExecResult result = backend.run(ExecSpec.of("echo hi"));

        assertThat(result.stdout()).isEqualTo("in-container");
        assertThat(hostCalls).as("命令只交出去一次，宿主那条路没有被顺手跑一遍").containsExactly("echo hi");
    }

    @Test
    @DisplayName("destroy() 收掉还活着的后台 shell：KillShell 是礼貌，不是回收机制")
    void destroyKillsTrackedBackgroundShells(@TempDir Path workspace) {
        Map<String, String> env = Map.of("PATH", System.getenv("PATH"));
        ExecBackend host = LocalExecBackend.builder()
                .workingDirectory(workspace)
                .cleanEnvironment(true)
                .environment(env)
                .shellCommand("/bin/sh", "-c")
                .build();
        SandboxedExecBackend backend = new SandboxedExecBackend(
                ExecProperties.of(ExecProperties.Mode.FULL, workspace, List.of()), host, env.keySet(), "tester");
        ExecHandle handle = backend.start(ExecSpec.of("sleep 10"));
        assertThat(handle.isAlive()).as("后台 shell 没起来，这条用例就验不到回收").isTrue();

        backend.destroy();

        assertThat(handle.isAlive())
                .as("destroy 之后后台 shell 还活着，等于容器关闭后留下一个没人杀的进程")
                .isFalse();
    }

    @Test
    @Tag("docker")
    @DisplayName("exec 侧用社区的 DockerCliExecBackend：托管容器真的起来，命令在容器里跑，宿主环境不进去")
    void execContainerBackendRunsInsideAContainer(@TempDir Path workspace) throws Exception {
        String hostHome = System.getenv("HOME");
        assertThat(hostHome).as("这条用例拿宿主 HOME 当对照，取不到就没法判").isNotNull();
        SandboxProperties properties = new SandboxProperties(SandboxProperties.Mode.ALL,
                SandboxProperties.Backend.DOCKER, SandboxProperties.WorkspaceAccess.RO, IMAGE, "docker", null);
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
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        }
        catch (IOException failure) {
            return "";
        }
    }
}
