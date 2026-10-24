package io.github.claw4j.tools.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全仓唯一起进程的那一个类：它起的进程到底收到了什么。
 *
 * <p>这一组盯的是票 08 的判据——<b>参数不经任何解释器</b>。
 * 反面对照（带分号、带命令替换的参数不会被执行）与正面对照（带空格、
 * 带中文的参数原样到得了进程）缺一个都不行：
 * 只有反面的话，"把所有参数都拒掉"的实现也是绿的。
 */
class LocalProcessRunnerTest {

    private static final String SHEBANG = "#!/bin/sh\n";

    @TempDir
    Path parent;

    @Test
    @DisplayName("参数里的分号不会变成第二条命令")
    void 分号不被解释() throws IOException {
        Path script = script("echoer", "echo \"1=[$1]\"", "echo \"2=[$2]\"");

        ProcessOutcome outcome = run(script, List.of("a; echo pwned", "b"));

        assertThat(outcome.status()).isEqualTo(ProcessOutcome.Status.COMPLETED);
        // 解释器在场的话，"echo pwned" 会变成第三条输出；只有两行就说明没人解释它。
        assertThat(outcome.stdout().lines().map(String::strip)).containsExactly("1=[a; echo pwned]", "2=[b]");
    }

    @Test
    @DisplayName("参数里的命令替换与反引号不被先执行")
    void 命令替换不被执行() throws IOException {
        Path script = script("subst", "echo \"1=[$1]\"", "echo \"2=[$2]\"");

        ProcessOutcome outcome = run(script, List.of("$(echo pwned)", "`echo pwned`"));

        // 少一行说明命令替换被执行了（它的输出会顶掉或补上一行）
        assertThat(outcome.stdout().lines().map(String::strip))
                .containsExactly("1=[$(echo pwned)]", "2=[`echo pwned`]");
    }

    /** 正面对照：带空格与带中文的参数原样到得了脚本。 */
    @Test
    @DisplayName("带空格与中文的参数原样传给脚本")
    void 空格与中文原样传入() throws IOException {
        Path script = script("args", "echo \"1=[$1]\"", "echo \"2=[$2]\"");

        ProcessOutcome outcome = run(script, List.of("深 圳", "a b"));

        assertThat(outcome.stdout()).contains("1=[深 圳]").contains("2=[a b]");
    }

    @Test
    @DisplayName("工作目录由沙箱给定，进程就跑在那儿")
    void 工作目录生效() throws IOException {
        Path script = script("pwd", "pwd");

        ProcessOutcome outcome = run(script, List.of());

        assertThat(Path.of(outcome.stdout().strip()).toRealPath()).isEqualTo(parent.toRealPath());
    }

    @Test
    @DisplayName("环境由沙箱给定，调用方塞不进去")
    void 环境只有沙箱给的那些() throws IOException {
        Path script = script("env", "echo \"V=$CLAW4J_TEST_MARKER\"");

        ProcessOutcome outcome = new LocalProcessRunner(parent, Map.of("CLAW4J_TEST_MARKER", "given")).run(
                new ProcessRequest(List.of(script.toString()), 2_000));

        assertThat(outcome.stdout()).contains("V=given");
    }

    /**
     * "调用方往执行环境里塞变量"没有入口：请求里根本没有环境变量这一项。
     *
     * <p>这条盯的是票 08 顺带换掉的那条运行时检查——
     * 环境只在构造执行器时被给出一次，请求里带不了。
     * 用反射盯住"有哪些字段"而不是盯"值对不对"，是因为要防的是有人再加一个 env 字段。
     */
    @Test
    @DisplayName("执行请求里没有环境变量这一项")
    void 请求里没有环境变量() {
        List<String> components = Arrays.stream(ProcessRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("argv", "timeoutMillis");
    }

    @Test
    @DisplayName("跑过上限的进程被掐断，状态是超时")
    void 超时被掐断() throws IOException {
        Path script = script("slow", "sleep 10");

        ProcessOutcome outcome = run(script, List.of(), 500);

        assertThat(outcome.status()).isEqualTo(ProcessOutcome.Status.TIMED_OUT);
        assertThat(outcome.exitCode()).isEqualTo(-1);
    }

    @Test
    @DisplayName("非零退出与 stderr 都拿得回来")
    void 退出码与错误输出() throws IOException {
        Path script = script("failing", "echo bad 1>&2", "exit 3");

        ProcessOutcome outcome = run(script, List.of());

        assertThat(outcome.status()).isEqualTo(ProcessOutcome.Status.COMPLETED);
        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.stderr().strip()).isEqualTo("bad");
    }

    /** 不再经解释器之后，第一行不给解释器就是起不来；报错要指名缺的是第一行。 */
    @Test
    @DisplayName("缺 shebang 时报的是「第一行」，不是操作系统的格式错误")
    void 缺shebang时报得明白() throws IOException {
        Path script = parent.resolve("noshebang.sh");
        Files.writeString(script, "echo hi\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));

        assertThatThrownBy(() -> run(script, List.of()))
                .isInstanceOf(ProcessStartException.class)
                .hasMessageContaining("第一行")
                .hasMessageContaining("#!");
    }

    @Test
    @DisplayName("没有可执行位时报的是「可执行位」")
    void 没有可执行位时报得明白() throws IOException {
        Path script = Files.writeString(parent.resolve("noexec.sh"), SHEBANG + "echo hi\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> run(script, List.of()))
                .isInstanceOf(ProcessStartException.class)
                .hasMessageContaining("可执行位");
    }

    // ------------------------------------------------------------------ 辅助

    private ProcessOutcome run(Path script, List<String> arguments) {
        return run(script, arguments, 5_000);
    }

    private ProcessOutcome run(Path script, List<String> arguments, long timeoutMillis) {
        return new LocalProcessRunner(parent, Map.of("PATH", System.getenv("PATH")))
                .run(request(script, arguments, timeoutMillis));
    }

    private static ProcessRequest request(Path script, List<String> arguments, long timeoutMillis) {
        List<String> argv = new java.util.ArrayList<>();
        argv.add(script.toString());
        argv.addAll(arguments);
        return new ProcessRequest(List.copyOf(argv), timeoutMillis);
    }

    /** 造一个真能跑的入口脚本：第一行给解释器，并且设上可执行位。 */
    private Path script(String name, String... lines) throws IOException {
        Path script = parent.resolve(name + ".sh");
        Files.writeString(script, SHEBANG + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }
}
