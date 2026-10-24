package io.github.claw4j.tools.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 沙箱几条约束里不依赖真实进程的那些：路径、超时、身份、环境。
 *
 * <p>这几条单独测是因为它们全是纯函数——不需要起进程就能证伪，
 * 而"起进程才能证伪"的那几条（工作目录、环境真的干净、超时真的生效）
 * 放在 {@code SandboxedProcessBackendTest} 与 {@code LocalProcessRunnerTest} 里用真进程证明。
 * 分开放的好处是：这一组跑得极快，另一组红的时候能立刻知道是"约束没写"还是"没接上"。
 */
class SandboxPolicyTest {

    private final SandboxPolicy policy = SandboxPolicy.defaults();

    @Test
    @DisplayName("包目录内的相对路径正常解析")
    void 包内的相对路径放行(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("note.txt"));

        assertThat(policy.resolveInside(root, "note.txt")).isEqualTo(root.resolve("note.txt"));
    }

    @Test
    @DisplayName("往上跳一级的路径被挡住")
    void 越出目录的点点被挡住(@TempDir Path root) {
        assertThatThrownBy(() -> policy.resolveInside(root, "../outside.txt"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("越出了脚本包自己的目录");
    }

    @Test
    @DisplayName("绝对路径被挡住：相对路径是这条边界的前提")
    void 绝对路径被挡住(@TempDir Path root) {
        assertThatThrownBy(() -> policy.resolveInside(root, "/etc/passwd"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("必须是相对路径");
    }

    /**
     * 符号链接这条最容易漏：只做 {@code ..} 的字面量检查时，
     * 包目录里放一个指向外面的软链就能整目录穿出去，而 {@code ..} 一个字都没出现。
     */
    @Test
    @DisplayName("指向目录外的符号链接被挡住")
    void 符号链接被挡住(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path secret = outside.resolve("secret.txt");
        Files.createFile(secret);
        Files.createSymbolicLink(root.resolve("leak.txt"), secret);

        assertThatThrownBy(() -> policy.resolveInside(root, "leak.txt"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("符号链接");
    }

    @Test
    @DisplayName("超时上限之内放行，之外拒绝")
    void 超时上限() {
        assertThat(policy.requireTimeoutWithinCap(1_000)).isEqualTo(1_000);
        assertThat(policy.requireTimeoutWithinCap(SandboxPolicy.MAX_TIMEOUT_MILLIS))
                .isEqualTo(SandboxPolicy.MAX_TIMEOUT_MILLIS);

        assertThatThrownBy(() -> policy.requireTimeoutWithinCap(SandboxPolicy.MAX_TIMEOUT_MILLIS + 1))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("超过上限");
        assertThatThrownBy(() -> policy.requireTimeoutWithinCap(0))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("正数");
    }

    /**
     * 这一条平时永远不会红（没人拿 root 跑测试），它红的那天说明有人在以 root 跑应用。
     * 之所以还要有它：脚本层的"非 root"在容器里由镜像的 USER 保证，
     * 而"保证"两个字需要一条会失败的代码来兜底。
     */
    @Test
    @DisplayName("以 root 身份执行被拒绝")
    void root身份被拒绝() {
        assertThatCode(() -> policy.requireNonRoot("claw4j")).doesNotThrowAnyException();

        assertThatThrownBy(() -> policy.requireNonRoot(SandboxPolicy.ROOT_ACCOUNT))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("root");
        // 取不到账号时也拒绝：不知道是谁在跑，就按最坏的情况处理
        assertThatThrownBy(() -> policy.requireNonRoot(null))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("取不到");
    }

    /**
     * 环境里只有 PATH：这是"换个执行位置不等于换了个能拿到凭据的位置"那句话的形状。
     * 断言"别的一个都没有"而不是"没有某几个敏感的"——后者是黑名单，加新凭据的人不会回来补。
     */
    @Test
    @DisplayName("沙箱给出的环境只有 PATH")
    void 环境只有PATH() {
        assertThat(policy.environment().keySet()).containsExactly("PATH");
    }
}
