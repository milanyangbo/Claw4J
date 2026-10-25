package io.github.claw4j.capability.script;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个完整功能：脚本包执行。
 *
 * <p>沙箱约束有两层：策略层挡住越界路径，进程层真的把工作目录锁在包目录里。
 * 只测前者时，"写了约束但没接到进程上"照样绿；只测后者时，
 * 包目录里的一个符号链接就能把整目录穿出去。
 */
class ScriptPackageTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("参数里的 .. 与绝对路径都被挡下：能碰到的只有包目录")
    void rejectsPathsThatEscapeThePackageRoot() {
        ScriptPackage pkg = packageWith("escape", "pwd");

        assertThatThrownBy(() -> SandboxPolicy.defaults().resolveInside(pkg.root(), "../secret.txt"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("越出");
        assertThatThrownBy(() -> SandboxPolicy.defaults().resolveInside(pkg.root(), "/etc/passwd"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("相对路径");
    }

    @Test
    @DisplayName("包目录里指向外面的符号链接会被挡下：路径里一个 .. 都没有也能穿出去")
    void rejectsSymlinkThatLeaksOutsideTheRoot() throws IOException {
        ScriptPackage pkg = packageWith("symlink", "pwd");
        Path outside = Files.writeString(temp.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(pkg.root().resolve("leak.txt"), outside);

        assertThatThrownBy(() -> SandboxPolicy.defaults().resolveInside(pkg.root(), "leak.txt"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("符号链接");
    }

    @Test
    @DisplayName("参数里的元字符就是普通字符：argv 不经解释器，不会被当成命令")
    void passesMetacharactersAsOneArgument() {
        ScriptPackage pkg = packageWith("echo-arg", "printf '%s' \"$1\"");
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        String output = runner.run("echo-arg", List.of("a; rm -rf /tmp/x $(id) `date`"));

        assertThat(output).isEqualTo("a; rm -rf /tmp/x $(id) `date`");
    }

    @Test
    @DisplayName("裸文件名也要过沙箱：包里的 leak.txt 是软链时，模型不用写斜杠就能读外面")
    void blocksSymlinkArgumentThatLooksLikeAPlainFileName() throws IOException {
        ScriptPackage pkg = packageWith("leak-arg", "printf '%s' \"$1\"");
        Path outside = Files.writeString(temp.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(pkg.root().resolve("leak.txt"), outside);
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        assertThatThrownBy(() -> runner.run("leak-arg", List.of("leak.txt")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("符号链接");
    }

    @Test
    @DisplayName("进程真的起在包目录里：换沙箱实现时工作目录不能跟着丢")
    void runsProcessInsideThePackageDirectory() throws IOException {
        ScriptPackage pkg = packageWith("pwd", "pwd");
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        String output = runner.run("pwd", List.of());

        assertThat(Path.of(output).toRealPath()).isEqualTo(pkg.root().toRealPath());
    }

    @Test
    @DisplayName("非零退出时把退出码与脚本自己的 stderr 一起给回来")
    void reportsNonZeroExitWithItsOwnStderr() {
        ScriptPackage pkg = packageWith("failing", "echo boom >&2\nexit 3");
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        assertThatThrownBy(() -> runner.run("failing", List.of()))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("退出码=3")
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("跑过时长上限就掐断，而不是挂着等它自己结束")
    void terminatesScriptAtItsTimeout() {
        ScriptPackage pkg = packageWith("slow", "sleep 5", 300);
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> runner.run("slow", List.of()))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("TIMED_OUT");

        assertThat(System.nanoTime() - startedAt).isLessThan(3_000_000_000L);
    }

    @Test
    @DisplayName("输出超过上限时截断并说明，模型不会把半截输出当成完整答案")
    void truncatesOutputAndSaysSo() {
        ScriptPackage pkg = packageWith("chatty", "awk 'BEGIN { for (i = 0; i < 9000; i++) printf \"x\" }'");
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        String output = runner.run("chatty", List.of());

        assertThat(output).contains("输出超过 8000 字符，已截断").endsWith("已截断）");
    }

    @Test
    @DisplayName("脚本什么都没打印时明确说“没有输出”，而不是回一个空串")
    void reportsEmptyOutput() {
        ScriptPackage pkg = packageWith("silent", "true");
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        assertThat(runner.run("silent", List.of())).isEqualTo("（脚本没有输出）");
    }

    @Test
    @DisplayName("构造即校验：名字不合函数名规格或缺描述的包，启动时就失败")
    void refusesPackageThatCannotBePresentedToAModel() throws IOException {
        Path root = Files.createDirectories(temp.resolve("bad"));
        Files.writeString(root.resolve("run.sh"), "#!/bin/sh\ntrue\n");

        assertThatThrownBy(() -> ScriptPackage.of("词数 统计", root, "数词的脚本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("脚本包名");
        assertThatThrownBy(() -> ScriptPackage.of("word-count", root, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺 description");
    }

    @Test
    @DisplayName("目录里有一个包写坏了就整层起不来，不静默少一个工具")
    void catalogFailsInsteadOfSkippingBrokenPackage() throws IOException {
        Path scripts = Files.createDirectories(temp.resolve("scripts"));
        writePackageDirectory(scripts.resolve("good"), "#!/bin/sh\ntrue\n", "description=好包");
        writePackageDirectory(scripts.resolve("broken"), "#!/bin/sh\ntrue\n", null);

        assertThatThrownBy(() -> ScriptPackageCatalog.load(scripts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("package.properties");
        assertThatThrownBy(() -> ScriptPackageCatalog.load(temp.resolve("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("人审通过后加进来的包立刻可调：清单与执行后端必须是同一件事")
    void newlyRegisteredPackageBecomesACallableTool() throws IOException {
        Path scripts = Files.createDirectories(temp.resolve("scripts"));
        ScriptPackageAutoConfiguration config = new ScriptPackageAutoConfiguration();
        ScriptPackageRegistry registry = config.scriptPackageRegistry(scripts.toString());
        ScriptPackageRunner runner = config.scriptPackageRunner(registry);
        ExecutionListeners listeners = config.executionListeners();
        ScriptPackageToolProvider provider = config.scriptPackageToolProvider(registry, runner, listeners);

        assertThat(provider.toolNames()).isEmpty();

        ScriptPackage published = packageWith("published", "pwd");
        registry.register(published);

        assertThat(provider.toolNames()).containsExactly("published");
        ToolCallback callback = provider.getToolCallbacks()[0];
        assertThat(Path.of(callback.call("{}")).toRealPath()).isEqualTo(published.root().toRealPath());
    }

    @Test
    @DisplayName("成功与被沙箱拦下都留一条痕：只记成功的审计答不了谁在捅边界")
    void recordsEveryOutcomeIncludingRejections() throws IOException {
        ScriptPackage pkg = packageWith("audited", "pwd");
        Files.createSymbolicLink(pkg.root().resolve("leak.txt"), Files.writeString(temp.resolve("s"), "s"));
        ScriptPackageRegistry registry = new ScriptPackageRegistry(List.of(pkg));
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));
        List<String> outcomes = new CopyOnWriteArrayList<>();
        ExecutionListeners bus = new ExecutionListeners();
        bus.add((name, context, outcome, output) -> outcomes.add(outcome));

        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(registry, runner, bus);
        ToolCallback callback = provider.getToolCallbacks()[0];

        callback.call("{}");
        assertThatThrownBy(() -> callback.call("{\"argument\":\"leak.txt\"}"))
                .isInstanceOf(ToolExecutionException.class);

        assertThat(outcomes).containsExactly("ok", "rejected");
    }

    @Test
    @DisplayName("留痕失败不能让一次成功的执行变成失败：审计是观察者，不是依赖")
    void listenerFailureDoesNotBreakExecution() throws IOException {
        ScriptPackage pkg = packageWith("resilient", "printf '%s' fine");
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));
        List<String> recorded = new CopyOnWriteArrayList<>();
        ExecutionListeners bus = new ExecutionListeners();
        bus.add((name, context, outcome, output) -> {
            throw new IllegalStateException("审计文件写不进去");
        });
        bus.add((name, context, outcome, output) -> recorded.add(outcome));

        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(
                new ScriptPackageRegistry(List.of(pkg)), runner, bus);

        String output = provider.getToolCallbacks()[0].call("{}");

        assertThat(output).isEqualTo("fine");
        assertThat(recorded).containsExactly("ok");
    }

    private ScriptPackage packageWith(String name, String body) {
        return packageWith(name, body, ScriptPackage.DEFAULT_TIMEOUT_MILLIS);
    }

    private ScriptPackage packageWith(String name, String body, long timeoutMillis) {
        try {
            Path root = Files.createDirectories(temp.resolve(name));
            Path entrypoint = root.resolve("run.sh");
            Files.writeString(entrypoint, "#!/bin/sh\n" + body + "\n");
            Files.setPosixFilePermissions(entrypoint, PosixFilePermissions.fromString("rwxr-xr-x"));
            return ScriptPackage.of(name, root, "run.sh", "测试包 " + name, timeoutMillis);
        }
        catch (IOException failure) {
            throw new IllegalStateException("准备脚本包失败", failure);
        }
    }

    private static void writePackageDirectory(Path directory, String script, String manifest)
            throws IOException {
        Files.createDirectories(directory);
        Path entrypoint = Files.writeString(directory.resolve("run.sh"), script);
        Files.setPosixFilePermissions(entrypoint, PosixFilePermissions.fromString("rwxr-xr-x"));
        if (manifest != null) {
            Files.writeString(directory.resolve("package.properties"), manifest + "\n");
        }
    }
}
