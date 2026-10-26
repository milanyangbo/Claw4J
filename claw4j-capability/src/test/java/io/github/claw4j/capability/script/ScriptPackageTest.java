package io.github.claw4j.capability.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本包：从工具入口进去，参数过沙箱，真起一个子进程，只看 stdout 回来。
 *
 * <p>约束要验两遍：一遍在策略上（符号链接、{@code ..} 被挡住），
 * 一遍在进程上（工作目录真的锁在包目录里）。前一条红是规则没写，
 * 后一条红是写了却没接到进程上——两种缺陷在日志里都看不出来。
 */
class ScriptPackageTest {

    @TempDir
    Path work;

    private Path packageRoot;

    @BeforeEach
    void createPackageDirectory() throws IOException {
        packageRoot = Files.createDirectories(work.resolve("packages"));
    }

    @Test
    @DisplayName("脚本目录里每个子目录是一个包，清单里的入口与超时覆盖默认值")
    void catalogReadsEveryPackageDirectory() throws IOException {
        Path scripts = Files.createDirectories(work.resolve("scripts"));
        writeManifest(scripts.resolve("alpha"), "alpha 的说明", null, null);
        writeManifest(scripts.resolve("bravo"), "bravo 的说明", "main.sh", "2500");

        List<ScriptPackage> packages = ScriptPackageCatalog.load(scripts);

        assertThat(packages).extracting(ScriptPackage::name).containsExactly("alpha", "bravo");
        assertThat(packages.getFirst().entrypoint()).isEqualTo(ScriptPackage.DEFAULT_ENTRYPOINT);
        assertThat(packages.getFirst().timeoutMillis()).isEqualTo(ScriptPackage.DEFAULT_TIMEOUT_MILLIS);
        assertThat(packages.getLast().entrypoint()).isEqualTo("main.sh");
        assertThat(packages.getLast().timeoutMillis()).isEqualTo(2_500);
    }

    @Test
    @DisplayName("配了脚本目录却不存在、或子目录没有清单：启动失败，不是静静地少一个工具")
    void catalogRefusesMissingDirectoryAndMissingManifest() throws IOException {
        assertThatThrownBy(() -> ScriptPackageCatalog.load(work.resolve("absent")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置的脚本目录不存在");

        Path scripts = Files.createDirectories(work.resolve("scripts"));
        Files.createDirectories(scripts.resolve("nameless"));

        assertThatThrownBy(() -> ScriptPackageCatalog.load(scripts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ScriptPackageCatalog.MANIFEST);
    }

    @Test
    @DisplayName("工具入口跑真脚本，返回的就是它写到标准输出的那句话")
    void toolEntryReturnsRealProcessStdout() throws IOException {
        ScriptPackage pkg = packageWith("hello", "#!/bin/sh\necho hello-from-script\n", 5_000);

        assertThat(callbackFor(pkg).call("{}")).isEqualTo("hello-from-script");
    }

    @Test
    @DisplayName("工作目录锁在包目录内：脚本看到的 pwd 就是包根")
    void processRunsInsideThePackageDirectory() throws IOException {
        ScriptPackage pkg = packageWith("where", "#!/bin/sh\npwd\n", 5_000);

        String output = callbackFor(pkg).call("{}");

        assertThat(Path.of(output.strip()).toRealPath()).isEqualTo(pkg.root().toRealPath());
    }

    @Test
    @DisplayName("环境从零开始：只有 PATH，应用进程的 HOME 不会漏给脚本")
    void childProcessEnvironmentIsWhitelisted() throws IOException {
        ScriptPackage pkg = packageWith("env", "#!/bin/sh\nenv\n", 5_000);

        String output = callbackFor(pkg).call("{}");

        assertThat(output).contains("PATH=").doesNotContain("HOME=");
    }

    @Test
    @DisplayName("带空格与分号的参数原样到达脚本，不被任何解释器拆开")
    void plainTextArgumentReachesTheScriptVerbatim() throws IOException {
        ScriptPackage pkg = packageWith("echo-arg", "#!/bin/sh\nprintf '%s' \"$1\"\n", 5_000);

        String output = callbackFor(pkg).call("{\"argument\":\"hello world; echo pwned\"}");

        assertThat(output).isEqualTo("hello world; echo pwned");
    }

    @Test
    @DisplayName("参数用 .. 越出包目录时在执行前被拒绝")
    void argumentEscapingThroughParentTraversalIsRejected() throws IOException {
        ScriptPackage pkg = packageWith("escape", "#!/bin/sh\necho should-not-run\n", 5_000);

        assertThatThrownBy(() -> callbackFor(pkg).call("{\"argument\":\"../../etc/passwd\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("越出了脚本包自己的目录");
    }

    @Test
    @DisplayName("包目录里指向外面的符号链接：裸文件名也穿不出去")
    void symlinkInsidePackagePointingOutsideIsRejected() throws IOException {
        ScriptPackage pkg = packageWith("leak", "#!/bin/sh\necho ok\n", 5_000);
        Path outside = Files.writeString(work.resolve("secret.txt"), "top-secret", UTF_8);
        Files.createSymbolicLink(pkg.root().resolve("leak.txt"), outside);

        assertThatThrownBy(() -> SandboxPolicy.defaults().resolveInside(pkg.root(), "leak.txt"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("符号链接");

        assertThatThrownBy(() -> callbackFor(pkg).call("{\"argument\":\"leak.txt\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(SandboxViolationException.class);
    }

    @Test
    @DisplayName("非零退出变成一次失败的工具结果，不把半截 stdout 当成功交回去")
    void nonZeroExitBecomesAToolFailure() throws IOException {
        ScriptPackage pkg = packageWith("fail", "#!/bin/sh\necho 'missing input' >&2\nexit 7\n", 5_000);

        Throwable thrown = catchThrowableOfType(() -> callbackFor(pkg).call("{}"), ToolExecutionException.class);

        assertThat(thrown).hasMessageContaining("以非零退出");
        assertThat(thrown.getCause())
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("退出码=7")
                .hasMessageContaining("missing input");
    }

    @Test
    @DisplayName("超时的脚本被掐断，结论是 TIMED_OUT 而不是「没有输出」")
    void timeoutKillsTheProcessAndReportsTimedOut() throws IOException {
        ScriptPackage pkg = packageWith("slow", "#!/bin/sh\nsleep 5\n", 300);

        Throwable thrown = catchThrowableOfType(() -> callbackFor(pkg).call("{}"), ToolExecutionException.class);

        assertThat(thrown.getCause())
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("TIMED_OUT");
    }

    @Test
    @DisplayName("超长输出被截断，并且说明后面还有")
    void oversizedOutputIsTruncatedWithNotice() throws IOException {
        ScriptPackage pkg = packageWith("loud", "#!/bin/sh\nyes a | head -c 9000\n", 5_000);

        String output = callbackFor(pkg).call("{}");

        assertThat(output).contains("输出超过 8000 字符，已截断").endsWith("已截断）");
    }

    @Test
    @DisplayName("没有输出时说「没有输出」，不交一段空白给模型")
    void emptyOutputIsStatedExplicitly() throws IOException {
        ScriptPackage pkg = packageWith("quiet", "#!/bin/sh\nexit 0\n", 5_000);

        assertThat(callbackFor(pkg).call("{}")).isEqualTo("（脚本没有输出）");
    }

    @Test
    @DisplayName("运行期注册的包立刻出现在工具清单里，并且真的跑得起来")
    void lateRegisteredPackageIsListedAndRunnable() throws IOException {
        ScriptPackage first = packageWith("first", "#!/bin/sh\necho one\n", 5_000);
        ScriptPackage second = packageWith("second", "#!/bin/sh\necho two\n", 5_000);
        ScriptPackageRegistry registry = new ScriptPackageRegistry(List.of(first));
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(first));
        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(registry, runner);

        registry.register(second);

        assertThat(provider.toolNames()).containsExactly("first", "second");
        ToolCallback late = provider.getToolCallbacks()[1];
        assertThat(late.getToolDefinition().name()).isEqualTo("second");
        assertThat(late.call("{}")).isEqualTo("two");
    }

    @Test
    @DisplayName("重名注册直接失败：两个同名工具会让模型分不清调哪个")
    void duplicateRegistrationFails() throws IOException {
        ScriptPackage pkg = packageWith("duplicate", "#!/bin/sh\necho x\n", 5_000);
        ScriptPackageRegistry registry = new ScriptPackageRegistry(List.of(pkg));

        assertThatThrownBy(() -> registry.register(pkg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同名工具");
    }

    @Test
    @DisplayName("成功与被拦下都留痕，观察者自己炸了也不能让执行失败")
    void listenerSeesEveryOutcomeAndCannotBreakExecution() throws IOException {
        ScriptPackage pkg = packageWith("audited", "#!/bin/sh\necho done\n", 5_000);
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));
        ExecutionListeners listeners = new ExecutionListeners();
        listeners.add((name, context, outcome, output) -> {
            throw new IllegalStateException("审计写盘失败");
        });
        List<String> recorded = new CopyOnWriteArrayList<>();
        listeners.add((name, context, outcome, output) -> recorded.add(name + ":" + outcome));
        ScriptPackageToolCallback callback = new ScriptPackageToolCallback(pkg, runner, listeners);

        assertThat(callback.call("{}")).isEqualTo("done");
        assertThatThrownBy(() -> callback.call("{\"argument\":\"../outside\"}"))
                .isInstanceOf(ToolExecutionException.class);

        assertThat(recorded).containsExactly("audited:ok", "audited:rejected");
    }

    @Test
    @DisplayName("入口没有可执行位时给出「忘了 chmod」这句话，而不是操作系统的 Permission denied")
    void entrypointWithoutExecutableBitFailsWithAReadableReason() throws IOException {
        ScriptPackage pkg = packageWith("plain", "#!/bin/sh\necho x\n", 5_000);
        Files.setPosixFilePermissions(pkg.entrypointPath(), PosixFilePermissions.fromString("rw-r--r--"));

        assertThatThrownBy(() -> callbackFor(pkg).call("{}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("可执行位");
    }

    private static void writeManifest(Path root, String description, String entrypoint, String timeoutMillis)
            throws IOException {
        Files.createDirectories(root);
        String manifest = "description=" + description + "\n";
        if (entrypoint != null) {
            manifest += "entrypoint=" + entrypoint + "\n";
        }
        if (timeoutMillis != null) {
            manifest += "timeoutMillis=" + timeoutMillis + "\n";
        }
        Files.writeString(root.resolve(ScriptPackageCatalog.MANIFEST), manifest, UTF_8);
        Files.writeString(root.resolve(entrypoint == null ? ScriptPackage.DEFAULT_ENTRYPOINT : entrypoint),
                "#!/bin/sh\ntrue\n", UTF_8);
    }

    private ScriptPackage packageWith(String name, String script, long timeoutMillis) throws IOException {
        Path root = Files.createDirectories(packageRoot.resolve(name));
        Path entrypoint = root.resolve(ScriptPackage.DEFAULT_ENTRYPOINT);
        Files.writeString(entrypoint, script, UTF_8);
        Files.setPosixFilePermissions(entrypoint, PosixFilePermissions.fromString("rwxr-xr-x"));
        return ScriptPackage.of(name, root, ScriptPackage.DEFAULT_ENTRYPOINT, "测试脚本包", timeoutMillis);
    }

    private static ScriptPackageToolCallback callbackFor(ScriptPackage pkg) {
        return new ScriptPackageToolCallback(pkg, new ScriptPackageRunner(List.of(pkg)));
    }

    private static <T extends Throwable> T catchThrowableOfType(ThrowingCallable callable, Class<T> type) {
        try {
            callable.call();
        }
        catch (Throwable failure) {
            assertThat(failure).isInstanceOf(type);
            return type.cast(failure);
        }
        throw new AssertionError("预期抛出 " + type.getSimpleName() + "，实际没有抛");
    }

    @FunctionalInterface
    private interface ThrowingCallable {

        void call() throws Throwable;
    }
}
