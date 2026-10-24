package io.github.claw4j.capability.script;

import io.github.claw4j.capability.process.LocalProcessRunner;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;
import io.github.claw4j.capability.process.ProcessStartException;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本包这一条链路：目录加载 → 参数过沙箱 → 起真进程 → 输出回上面。
 *
 * <p>两个层次刻意放在一起，但红的时候意义不同：
 * 纯函数那几条（路径、超时、身份、环境）红 = <b>约束没写</b>；
 * 真进程那几条红 = <b>约束没接到进程上</b>。换假的执行器只能验到"我们调没调用它"，
 * 验不到"进程到底起在哪儿、拿到了什么环境"。
 *
 * <p>参数这一组盯的是模型唯一能把自由文本送进进程的位置。判据是一对：
 * 元字符不变成第二条命令（反面），带空格与中文的参数原样到得了脚本（正面）。
 * 只有反面的话，一个把所有参数都拒掉的实现也是绿的。
 */
class ScriptPackageExecutionTest {

    /** 命令解释器在空环境下也会自己设这几个，它们不是从 JVM 继承来的。 */
    private static final Set<String> SHELL_GENERATED = Set.of("PWD", "SHLVL", "_", "OLDPWD");

    private final SandboxPolicy policy = SandboxPolicy.defaults();

    // ------------------------------------------------------------------ 约束层（纯函数）

    @Test
    @DisplayName("包目录内的相对路径正常解析，包里的文件读得到")
    void 包内路径放行(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("note.txt"));

        assertThat(policy.resolveInside(root, "note.txt")).isEqualTo(root.resolve("note.txt"));

        ScriptPackage pkg = ScriptPackageFixtures.executable("reader", root,
                ScriptPackageFixtures.script("cat \"$1\""));
        Files.writeString(pkg.root().resolve("note.txt"), "inside");

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("reader", List.of("note.txt"))).isEqualTo("inside");
    }

    @Test
    @DisplayName("往上跳一级与绝对路径都被挡住")
    void 越出目录的路径被挡住(@TempDir Path root) {
        assertThatThrownBy(() -> policy.resolveInside(root, "../outside.txt"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("越出了脚本包自己的目录");
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
    @DisplayName("超时上限之内放行，之外拒绝且不夹取，零与负数拒绝")
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
     * 环境里只有 PATH：断言"别的一个都没有"而不是"没有某几个敏感的"——
     * 后者是黑名单，加新凭据的人不会回来补。
     */
    @Test
    @DisplayName("以 root 身份执行被拒绝；沙箱给出的环境只有 PATH")
    void 身份与环境约束() {
        assertThatCode(() -> policy.requireNonRoot("claw4j")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireNonRoot(SandboxPolicy.ROOT_ACCOUNT))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("root");
        assertThatThrownBy(() -> policy.requireNonRoot(null))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("取不到");

        assertThat(policy.environment().keySet()).containsExactly("PATH");
    }

    // ------------------------------------------------------------------ 进程层（真起进程）

    @Test
    @DisplayName("进程的工作目录锁在脚本包自己的目录里")
    void 工作目录锁在包目录内(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("cwd", parent,
                ScriptPackageFixtures.script("pwd"));

        ProcessOutcome outcome = SandboxedProcessBackend.forPackage(pkg).run(requestFor(pkg));

        assertThat(outcome.status()).isEqualTo(ProcessOutcome.Status.COMPLETED);
        // macOS 上 /var 是 /private/var 的软链，两边都要 realpath 才比得动
        assertThat(Path.of(outcome.stdout().strip()).toRealPath()).isEqualTo(pkg.root().toRealPath());
    }

    /**
     * "换个执行位置不等于换了个能拿到凭据的位置"：JVM 的环境变量一个都不该漏进去。
     *
     * <p>先断言 {@code PATH} 真的在，是为了防止 {@code env} 压根没跑起来——
     * 那样这条用例会因为"什么都没漏"而假绿。
     */
    @Test
    @DisplayName("执行环境里没有业务凭据：JVM 的环境变量一个都不继承")
    void 执行环境里没有凭据(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("envdump", parent,
                ScriptPackageFixtures.script("env"));

        String dumped = SandboxedProcessBackend.forPackage(pkg).run(requestFor(pkg)).stdout();
        assertThat(dumped).contains("PATH=");

        Set<String> leaked = System.getenv().keySet().stream()
                .filter(key -> !"PATH".equals(key))
                .filter(key -> !SHELL_GENERATED.contains(key))
                .filter(key -> dumped.contains(key + "="))
                .collect(Collectors.toSet());
        assertThat(leaked).isEmpty();
    }

    @Test
    @DisplayName("超时真的生效：跑过上限的脚本被掐断，超上限的请求被拒绝而不是夹取后照跑")
    void 超时生效(@TempDir Path parent) throws IOException {
        ScriptPackage slow = ScriptPackageFixtures.executable("slow", parent,
                ScriptPackageFixtures.script("sleep 5"), 1_000);
        assertThat(SandboxedProcessBackend.forPackage(slow).run(requestFor(slow)).status())
                .isEqualTo(ProcessOutcome.Status.TIMED_OUT);

        ScriptPackage quick = ScriptPackageFixtures.executable("overcap", parent,
                ScriptPackageFixtures.script("echo hi"));
        ProcessRequest overCap = new ProcessRequest(List.of(quick.entrypointPath().toString()), 120_000);

        assertThatThrownBy(() -> SandboxedProcessBackend.forPackage(quick).run(overCap))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("超过上限");
    }

    /** 这条红的那天说明有人在以 root 跑应用：脚本层拒绝跟着变成 root 执行。 */
    @Test
    @DisplayName("root 身份下不起进程")
    void root身份下不起进程(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("asroot", parent,
                ScriptPackageFixtures.script("echo hi"));
        SandboxedProcessBackend backend = SandboxedProcessBackend.forPackage(pkg, SandboxPolicy.defaults(),
                () -> SandboxPolicy.ROOT_ACCOUNT);

        assertThatThrownBy(() -> backend.run(requestFor(pkg)))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("root");
    }

    // ------------------------------------------------------------------ 参数与输出

    @Test
    @DisplayName("参数里的分号、命令替换、反引号都不会变成第二条命令")
    void 元字符不会被解释(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("quoter", parent,
                ScriptPackageFixtures.script("echo \"1=[$1]\"", "echo \"2=[$2]\""));

        String semicolon = new ScriptPackageRunner(List.of(pkg)).run("quoter", List.of("a; echo pwned", "b"));
        assertThat(semicolon.lines().map(String::strip))
                .containsExactly("1=[a; echo pwned]", "2=[b]");

        String substituted = new ScriptPackageRunner(List.of(pkg)).run("quoter",
                List.of("$(echo pwned)", "`echo pwned`"));
        assertThat(substituted.lines().map(String::strip))
                .containsExactly("1=[$(echo pwned)]", "2=[`echo pwned`]");
    }

    /** 正面对照：只断言"没被执行"的话，一个把所有参数都拒掉的实现也是绿的。 */
    @Test
    @DisplayName("带空格与中文的参数原样传给脚本")
    void 空格与中文传得进去(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("args", parent,
                ScriptPackageFixtures.script("echo \"1=[$1]\"", "echo \"2=[$2]\""));

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("args", List.of("深 圳", "a b")))
                .contains("1=[深 圳]")
                .contains("2=[a b]");
    }

    /**
     * 脚本本体不进上下文：脚本里写着一个从不打印的记号，
     * 它出现在返回值里的唯一途径就是有人把脚本文件本身也读了进来。
     */
    @Test
    @DisplayName("脚本本体不进上下文：只有标准输出进")
    void 脚本本体不进上下文(@TempDir Path parent) throws IOException {
        String secret = "BODY-NEVER-PRINTED-" + System.nanoTime();
        ScriptPackage pkg = ScriptPackageFixtures.executable("echoer", parent,
                ScriptPackageFixtures.script("# " + secret, "echo hello"));

        String output = new ScriptPackageRunner(List.of(pkg)).run("echoer", List.of());

        assertThat(output).isEqualTo("hello");
        assertThat(output).doesNotContain(secret);
    }

    /** 一个 {@code cat} 就能把上下文撑爆，撑爆之后没人看得见答案；截断必须说出来。 */
    @Test
    @DisplayName("过长的输出被截断，且说清被截断了")
    void 输出截断(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("flood", parent,
                ScriptPackageFixtures.script("yes x | head -n 5000"));

        String output = new ScriptPackageRunner(List.of(pkg)).run("flood", List.of());

        assertThat(output).hasSizeLessThanOrEqualTo(ScriptPackageRunner.MAX_OUTPUT_CHARS + 100);
        assertThat(output).contains("已截断");
    }

    // ------------------------------------------------------------------ 错误信息（模型能据此自纠）

    @Test
    @DisplayName("缺 shebang 与缺可执行位，报的都是指得出原因的那句话")
    void 起不来的原因说得明白(@TempDir Path parent) throws IOException {
        Path noShebang = parent.resolve("noshebang.sh");
        Files.writeString(noShebang, "echo hi\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(noShebang, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));

        assertThatThrownBy(() -> new LocalProcessRunner(parent, Map.of("PATH", System.getenv("PATH")))
                .run(new ProcessRequest(List.of(noShebang.toString()), 2_000)))
                .isInstanceOf(ProcessStartException.class)
                .hasMessageContaining("第一行")
                .hasMessageContaining("#!");

        Path noExec = Files.writeString(parent.resolve("noexec.sh"), "#!/bin/sh\necho hi\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new LocalProcessRunner(parent, Map.of("PATH", System.getenv("PATH")))
                .run(new ProcessRequest(List.of(noExec.toString()), 2_000)))
                .isInstanceOf(ProcessStartException.class)
                .hasMessageContaining("可执行位");
    }

    @Test
    @DisplayName("非零退出带上退出码与 stderr；包名拼错时把有哪些包一并说出来")
    void 失败带原话(@TempDir Path parent) throws IOException {
        ScriptPackage failing = ScriptPackageFixtures.executable("failing", parent,
                ScriptPackageFixtures.script("echo 'boom' >&2", "exit 3"));

        assertThatThrownBy(() -> new ScriptPackageRunner(List.of(failing)).run("failing", List.of()))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("boom");

        assertThatThrownBy(() -> new ScriptPackageRunner(List.of(failing)).run("unknown", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failing");
    }

    // ------------------------------------------------------------------ 参数过沙箱

    @Test
    @DisplayName("指向目录外的参数被挡住，裸文件名的符号链接也挡")
    void 越界参数被挡住(@TempDir Path parent, @TempDir Path outside) throws IOException {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        ScriptPackage pkg = ScriptPackageFixtures.executable("reader", parent,
                ScriptPackageFixtures.script("cat \"$1\""));
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        assertThatThrownBy(() -> runner.run("reader", List.of("../" + outside.getFileName() + "/secret.txt")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("越出");

        // 裸文件名既不含斜杠也不以点开头，"看着像路径才校验"的判据正好漏掉它
        Files.createSymbolicLink(pkg.root().resolve("leak.txt"), secret);
        assertThatThrownBy(() -> runner.run("reader", List.of("leak.txt")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("符号链接");
    }

    /**
     * 交给后端的是数组，不是一条命令行字符串——这条就是"结构化 argv"的判据。
     * 请求里也没有环境变量这一项：用反射盯住字段，是因为要防的是有人再加一个 env 字段。
     */
    @Test
    @DisplayName("后端可替换：交出去的是 argv 数组，请求里没有环境变量")
    void 后端可替换(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("swapped", parent,
                ScriptPackageFixtures.script("echo real"));
        RecordingRunner recording = new RecordingRunner();

        String output = new ScriptPackageRunner(List.of(pkg), recording).run("swapped", List.of());

        assertThat(output).isEqualTo("recorded");
        assertThat(recording.requests()).singleElement().satisfies(request -> {
            assertThat(request.argv()).hasSize(1);
            assertThat(request.argv().getFirst()).endsWith("run.sh");
        });
        assertThat(java.util.Arrays.stream(ProcessRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("argv", "timeoutMillis");
    }

    // ------------------------------------------------------------------ 目录加载与权限声明

    @Test
    @DisplayName("包目录里的每个子目录都是一个包，名字、描述、入口、超时都读得出来")
    void 目录加载(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("weather"));
        Files.writeString(dir.resolve(ScriptPackageCatalog.MANIFEST),
                "description=查询某地的天气\nentrypoint=forecast.sh\ntimeoutMillis=12000\n",
                StandardCharsets.UTF_8);
        ScriptPackageFixtures.executable("weather", root, "forecast.sh",
                ScriptPackageFixtures.script("echo sunny"), 12_000);

        assertThat(ScriptPackageCatalog.load(root)).singleElement().satisfies(pkg -> {
            assertThat(pkg.name()).isEqualTo("weather");
            assertThat(pkg.description()).isEqualTo("查询某地的天气");
            assertThat(pkg.entrypoint()).isEqualTo("forecast.sh");
            assertThat(pkg.timeoutMillis()).isEqualTo(12_000);
        });
    }

    /** 缺描述的包永远不会被调用，而且不会报错——所以它必须在启动时就失败。 */
    @Test
    @DisplayName("缺 description 或目录不存在，都在加载时失败")
    void 加载失败(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("weather"));
        Files.writeString(dir.resolve(ScriptPackageCatalog.MANIFEST), "entrypoint=forecast.sh",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ScriptPackageCatalog.load(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
        assertThatThrownBy(() -> ScriptPackageCatalog.load(root.resolve("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    /**
     * 权限登记的形状就是这一层的边界：动作是跑脚本，业务表一张都没有。
     * "表是空的"不等于"不需要权限"——没登记过的工具一律按拒绝处理，
     * 所以这条断言盯的是"登记了"且"表是空的"两件事同时成立。
     */
    @Test
    @DisplayName("权限登记是「跑脚本」且业务表为空；只读角色跑不了脚本")
    void 权限登记(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("weatherForecast", parent,
                ScriptPackageFixtures.script("echo sunny"));
        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(List.of(pkg));

        assertThat(provider.toolNames()).containsExactly("weatherForecast");
        assertThat(provider.getToolCallbacks()[0].getToolDefinition().description())
                .contains("weatherForecast");

        ToolPermission permission = provider.permissions().get("weatherForecast");
        assertThat(permission).isNotNull();
        assertThat(permission.action()).isEqualTo(ScriptPackageToolProvider.SCRIPT_ACTION);
        assertThat(permission.tables()).isEmpty();

        assertThat(Principal.readOnly("u-1").canUse(permission)).isFalse();
        assertThat(Principal.of("u-2", "admin", Set.of(Principal.WILDCARD),
                Set.of(ScriptPackageToolProvider.SCRIPT_ACTION)).canUse(permission)).isTrue();
    }

    // ------------------------------------------------------------------ 辅助

    private static ProcessRequest requestFor(ScriptPackage pkg) {
        return new ProcessRequest(List.of(pkg.entrypointPath().toString()), pkg.timeoutMillis());
    }

    /** 只证明调用方认 {@code ProcessRunner} 这个接口，不碰任何进程。 */
    private static final class RecordingRunner implements ProcessRunnerFactory {

        private final List<ProcessRequest> requests = new ArrayList<>();

        @Override
        public ProcessRunner forPackage(ScriptPackage pkg) {
            return request -> {
                requests.add(request);
                return ProcessOutcome.completed(0, "recorded", "");
            };
        }

        List<ProcessRequest> requests() {
            return requests;
        }
    }
}
