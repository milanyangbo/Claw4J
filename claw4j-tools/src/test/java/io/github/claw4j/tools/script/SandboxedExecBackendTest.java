package io.github.claw4j.tools.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 起真进程验沙箱：工作目录、环境、超时、后台句柄。
 *
 * <p>为什么说这四条必须起真进程：它们全都发生在"子进程被 fork 出来的那一刻"，
 * 换成假的 {@code ExecBackend} 就只能验到"我们有没有调用它"，
 * 验不到"它到底把进程起在了哪儿"。{@code SandboxPolicyTest} 里那组纯函数
 * 验的是约束本身，这一组验的是约束真的接到了进程上。
 */
class SandboxedExecBackendTest {

    @Test
    @DisplayName("进程的工作目录锁在脚本包自己的目录里")
    void 工作目录锁在包目录内(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("cwd", parent,
                ScriptPackageFixtures.script("pwd"));

        ExecResult result = SandboxedExecBackend.forPackage(pkg)
                .run(new ExecSpec("./run.sh", pkg.timeoutMillis(), Map.of()));

        assertThat(result.status()).isEqualTo(ExecResult.Status.COMPLETED);
        // macOS 上 /var 是 /private/var 的软链，两边都要 realpath 才比得动
        assertThat(Path.of(result.stdout().strip()).toRealPath()).isEqualTo(pkg.root().toRealPath());
    }

    /**
     * "换个执行位置不等于换了个能拿到凭据的位置"。
     *
     * <p>断言的是"JVM 环境里除了 PATH 一个都没漏进去"，而不是"没有某几个敏感的"：
     * 后者是黑名单，谁新加一个 {@code XXX_TOKEN} 都不会想到回来补一条。
     * 先断言 {@code PATH} 真的在，是为了防止 {@code env} 压根没跑起来
     * ——那样这条用例会因为"什么都没漏"而假绿。
     */
    @Test
    @DisplayName("执行环境里没有业务凭据：JVM 的环境变量一个都不继承")
    void 执行环境里没有凭据(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("envdump", parent,
                ScriptPackageFixtures.script("env"));

        ExecResult result = SandboxedExecBackend.forPackage(pkg)
                .run(new ExecSpec("./run.sh", pkg.timeoutMillis(), Map.of()));

        String dumped = result.stdout();
        // 先证明 env 真的跑起来了：输出为空的话"什么都没漏"会是一条假绿
        assertThat(dumped).contains("PATH=");

        Set<String> leaked = System.getenv().keySet().stream()
                .filter(key -> !"PATH".equals(key))
                .filter(key -> !SHELL_GENERATED.contains(key))
                .filter(key -> dumped.contains(key + "="))
                .collect(Collectors.toSet());
        assertThat(leaked).isEmpty();
    }

    /**
     * shell 在空环境下也会自己设这三个，它们不是从 JVM 继承来的。
     *
     * <p>为什么值得写出来：不清空环境时它们也在，于是漏掉这份排除名单，
     * 这条用例会在"环境根本没洗"和"洗过了"两种情况下都红或都绿。
     * 排除的是 shell 自己的规矩（PWD 是工作目录、SHLVL 从 1 开始），
     * 不是任何一种凭据。
     */
    private static final Set<String> SHELL_GENERATED = Set.of("PWD", "SHLVL", "_", "OLDPWD");

    @Test
    @DisplayName("超时真的生效：跑过上限的脚本被掐断")
    void 超时生效(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("slow", parent,
                ScriptPackageFixtures.script("sleep 5"), 1_000);

        ExecResult result = SandboxedExecBackend.forPackage(pkg)
                .run(new ExecSpec("./run.sh", pkg.timeoutMillis(), Map.of()));

        assertThat(result.status()).isEqualTo(ExecResult.Status.TIMED_OUT);
    }

    /** {@code ExecSpec} 不给超时时是 120 秒的默认，那已经越过了沙箱的上限。 */
    @Test
    @DisplayName("不给超时的调用被拒绝，而不是按默认值放行")
    void 不给超时被拒绝(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("notimeout", parent,
                ScriptPackageFixtures.script("echo hi"));

        assertThatThrownBy(() -> SandboxedExecBackend.forPackage(pkg).run(ExecSpec.of("./run.sh")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    @DisplayName("后台执行被拒绝：句柄没有超时，等于把预算打开")
    void 后台执行被拒绝(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("background", parent,
                ScriptPackageFixtures.script("sleep 1"));

        assertThatThrownBy(() -> SandboxedExecBackend.forPackage(pkg).start(ExecSpec.of("./run.sh")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("超时");
    }

    /** 这条红的那天说明有人在以 root 跑应用：脚本层拒绝跟着变成 root 执行。 */
    @Test
    @DisplayName("root 身份下不起进程")
    void root身份下不起进程(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("asroot", parent,
                ScriptPackageFixtures.script("echo hi"));

        SandboxedExecBackend backend = SandboxedExecBackend.forPackage(pkg, SandboxPolicy.defaults(),
                () -> SandboxPolicy.ROOT_ACCOUNT);

        assertThatThrownBy(() -> backend.run(new ExecSpec("./run.sh", pkg.timeoutMillis(), Map.of())))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("root");
    }
}
