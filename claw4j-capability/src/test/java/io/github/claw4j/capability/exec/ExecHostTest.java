package io.github.claw4j.capability.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code claw4j.tools.exec.host} 的合法性：配了做不到的位置就起不来。
 *
 * <p>这条规则防的是一句假话。{@code host=sandbox} 而沙箱没开时，命令会<b>静默</b>跑在宿主上——
 * 配置看着是"进了容器"，实际没有；那种部署出的安全问题，第一反应会是去翻容器配置。
 * 所以它必须是启动失败，而不是回落。
 */
class ExecHostTest {

    @Test
    @DisplayName("不写 host 时落在本机：auto 是默认，没有沙箱也成立")
    void autoNeedsNoSandbox() {
        ExecProperties properties = properties(ExecProperties.Host.AUTO);

        assertThat(properties.host()).isEqualTo(ExecProperties.Host.AUTO);
        assertThatCode(() -> properties.requireUsable(false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("gateway 就是本机：控制面与执行面是同一个进程，不算「配了做不到」")
    void gatewayIsThisMachine() {
        assertThatCode(() -> properties(ExecProperties.Host.GATEWAY).requireUsable(false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("host=sandbox 但沙箱没开：启动失败，不许静默回落宿主")
    void sandboxWithoutSandboxFailsFast() {
        assertThatThrownBy(() -> properties(ExecProperties.Host.SANDBOX).requireUsable(false))
                .as("要人知道该改哪一行，也要知道为什么")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host=sandbox")
                .hasMessageContaining("sandbox.mode=off");
    }

    @Test
    @DisplayName("host=sandbox 且 mode=all：合法，命令进容器")
    void sandboxWithSandboxIsLegal() {
        assertThatCode(() -> properties(ExecProperties.Host.SANDBOX).requireUsable(true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("host=node 启动失败：本仓没有多宿主")
    void nodeFailsFast() {
        assertThatThrownBy(() -> properties(ExecProperties.Host.NODE).requireUsable(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host=node")
                .hasMessageContaining("多宿主");
    }

    private ExecProperties properties(ExecProperties.Host host) {
        return new ExecProperties(ExecProperties.Mode.FULL, null, null, null, host, null, null,
                Path.of("."), List.of());
    }
}
