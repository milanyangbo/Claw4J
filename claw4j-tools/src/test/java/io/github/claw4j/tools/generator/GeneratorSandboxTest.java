package io.github.claw4j.tools.generator;

import io.github.claw4j.tools.process.ProcessOutcome;
import io.github.claw4j.tools.process.ProcessRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 ② 类沙箱：跑的是未审核代码，它<b>不写候选目录</b>。
 *
 * <p>与第 ① 类（{@code SandboxedProcessBackend}，包目录内执行）分清的那一条，
 * 在这份测试里是一条具体的断言：沙箱目录不是候选目录。
 * 这两套代码长得越像，"为什么要有两个"就越需要一个说得出来的理由，
 * 而这个理由就是下面第一条用例。
 */
class GeneratorSandboxTest {

    @TempDir
    Path parent;

    // ------------------------------------------------------------------ 位置

    @Test
    @DisplayName("自测跑在临时副本里，不跑在候选目录里")
    void 沙箱目录不是候选目录() throws IOException {
        Path candidate = candidate("copy", "#!/bin/sh\npwd\n");

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessOutcome outcome = sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 1_000));

        assertThat(sandbox.sandboxDirectory()).isNotEqualTo(candidate);
        assertThat(outcome.stdout()).contains(sandbox.sandboxDirectory().toString());
        // 跑完即删：脚本写下的任何东西都不留在这个进程里。
        assertThat(Files.exists(sandbox.sandboxDirectory())).isFalse();
    }

    /** 脚本在自测里写文件，写不到它自己待审的那份目录里。 */
    @Test
    @DisplayName("自测写的文件不落在候选目录里")
    void 自测的写入落不回候选目录() throws IOException {
        Path candidate = candidate("writer", "#!/bin/sh\necho written > created.txt\npwd\n");

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessOutcome outcome = sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 1_000));

        assertThat(outcome.stdout()).isNotBlank();
        assertThat(candidate.resolve("created.txt")).doesNotExist();
    }

    // ------------------------------------------------------------------ 环境

    /**
     * 不继承应用进程的环境。
     *
     * <p>断言先确认"宿主机上确实有别的变量"，再确认沙箱里没有它——
     * 少了前一步的话，换一台一个多余变量都没有的机器，这条用例就变成永真。
     *
     * <p>"调用方往里塞变量"这一条不再有运行时检查：{@link ProcessRequest} 里
     * 没有环境变量这一项，塞不进来。这条用例盯的是同一个事实的另一半——
     * 环境只有沙箱给的那些。
     */
    @Test
    @DisplayName("沙箱环境里没有应用进程的任何环境变量")
    void 环境不继承() throws IOException {
        Path candidate = candidate("env", "#!/bin/sh\nenv | sort\n");
        // PWD / SHLVL / _ / OLDPWD 是命令解释器自己生成的，不是从应用进程继承来的：
        // 它们在任何 shell 里都会出现，把它们算进"继承"里这条断言就永远红。
        Set<String> shellGenerated = Set.of("PWD", "SHLVL", "_", "OLDPWD");
        Set<String> hostKeys = System.getenv().keySet().stream()
                .filter(key -> !"PATH".equals(key))
                .filter(key -> !shellGenerated.contains(key))
                .collect(Collectors.toSet());

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessOutcome outcome = sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 1_000));

        assertThat(hostKeys).as("宿主机上要有至少一个非 PATH 的环境变量，否则这条断言是永真的").isNotEmpty();
        for (String key : hostKeys) {
            assertThat(outcome.stdout()).doesNotContain(key + "=");
        }
        assertThat(outcome.stdout()).contains("PATH=");
    }

    // ------------------------------------------------------------------ 预算与非 root

    @Test
    @DisplayName("超过自测上限的超时被拒，而不是夹取后照跑")
    void 超时超出上限被拒() throws IOException {
        Path candidate = candidate("timeout", "#!/bin/sh\nsleep 1\n");

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessRequest request = new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 60_000);

        assertThatThrownBy(() -> sandbox.run(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自测超时");
    }

    @Test
    @DisplayName("root 身份拒绝自测")
    void root身份拒绝自测() throws IOException {
        Path candidate = candidate("root", "#!/bin/sh\necho hi\n");

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local(), () -> "root");

        assertThatThrownBy(() -> sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 1_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非 root");
    }

    @Test
    @DisplayName("脚本失败时退出码非零，stderr 原样可取")
    void 失败退出码与错误输出() throws IOException {
        Path candidate = candidate("failing", "#!/bin/sh\necho bad 1>&2\nexit 3\n");

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessOutcome outcome = sandbox.run(new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 2_000));

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.stderr().strip()).isEqualTo("bad");
    }

    /**
     * 入口缺 shebang 时自测给的是一句能改的话，不是操作系统的格式错误。
     *
     * <p>静态检查会先挡一道，这一条盯的是"漏过静态检查之后"（比如人手工改了暂存区）
     * 执行侧也得说得明白。
     */
    @Test
    @DisplayName("入口缺 shebang 时报错指名缺第一行")
    void 缺shebang时报得明白() throws IOException {
        Path candidate = candidate("noshebang", "echo hi\n");

        GeneratorSandbox sandbox = GeneratorSandbox.forCandidate(candidate, IsolationSpec.local());
        ProcessRequest request = new ProcessRequest(
                List.of(sandbox.sandboxDirectory().resolve("run.sh").toString()), 1_000);

        assertThatThrownBy(() -> sandbox.run(request)).hasMessageContaining("shebang");
    }

    // ------------------------------------------------------------------ 给不了的东西要写明

    /**
     * 规格不许撒谎：网络这一项在本地形态下是"没隔离"，而且必须写清为什么。
     *
     * <p>这条用例防的是一类很具体的退化：把这一项改成 {@code ISOLATED}
     * （或者一个叫 {@code networkIsolated} 的布尔量）会让规格看起来更漂亮，
     * 而 README 里那句"网络受限"从此有了一段没有测试支撑的依据。
     */
    @Test
    @DisplayName("网络这一项如实写成「没隔离」，并写明原因")
    void 网络一项不撒谎() {
        IsolationSpec spec = IsolationSpec.local();

        assertThat(spec.network()).isEqualTo(IsolationSpec.Network.NOT_ISOLATED);
        assertThat(spec.networkNote()).isNotBlank();
        assertThat(spec.timeoutMillis()).isLessThanOrEqualTo(IsolationSpec.MAX_TIMEOUT_MILLIS);
    }

    /** 超时的上限本身也要有人看着：放宽上限就是放宽"未审核代码能跑多久"。 */
    @Test
    @DisplayName("自测上限比脚本层更紧")
    void 自测上限更紧() {
        assertThat(IsolationSpec.MAX_TIMEOUT_MILLIS).isLessThan(
                io.github.claw4j.tools.script.SandboxPolicy.MAX_TIMEOUT_MILLIS);
    }

    // ------------------------------------------------------------------ 辅助

    private Path candidate(String name, String script) throws IOException {
        Path root = Files.createDirectories(parent.resolve(name));
        Path file = root.resolve("run.sh");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return root;
    }
}
