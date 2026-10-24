package io.github.claw4j.tools.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 调用方这一侧：命令怎么拼出来的、出去的只有什么。
 *
 * <p>这一组盯的是 ADR-0002 剩下的那条缝——命令仍然是要拼的，
 * 能做的是把"拼什么"变成确定的：入口来自包清单，参数要么被判成路径过沙箱、
 * 要么被加引号。参数那条缝是模型唯一能把自由文本送进命令行的地方。
 */
class ScriptPackageRunnerTest {

    /**
     * 脚本本体不进上下文（GLOSSARY 的 Script Package 定义）。
     *
     * <p>这条断言盯的是"只有输出进"里的<b>只有</b>：脚本里写着一个从不打印的记号，
     * 它出现在返回值里的唯一途径就是有人把脚本文件本身也读了进来。
     * 换成"返回值的长度"或"返回值包含预期内容"都盯不到这件事。
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

    @Test
    @DisplayName("模型给的参数原样传给脚本")
    void 参数传给脚本(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("greeter", parent,
                ScriptPackageFixtures.script("echo \"got=$1\""));

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("greeter", List.of("深圳")))
                .isEqualTo("got=深圳");
    }

    /**
     * 参数里带引号和分号：这是"命令行是谁拼的"那条边界上最常被撞的地方。
     * 少了引号转义，{@code ; rm -rf} 就会在命令行上变成第二条命令。
     */
    @Test
    @DisplayName("参数里的 shell 元字符不会变成第二条命令")
    void 元字符被转义(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("quoter", parent,
                ScriptPackageFixtures.script("echo \"got=$1\""));

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("quoter", List.of("a; echo pwned")))
                .isEqualTo("got=a; echo pwned");
    }

    /** 模型想让脚本读沙箱外的文件：这是"脚本想碰目录之外的东西"在参数上进来的形状。 */
    @Test
    @DisplayName("指向目录外的参数被挡住")
    void 越界参数被挡住(@TempDir Path parent, @TempDir Path outside) throws IOException {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        ScriptPackage pkg = ScriptPackageFixtures.executable("reader", parent,
                ScriptPackageFixtures.script("cat \"$1\""));

        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        assertThatThrownBy(() -> runner.run("reader", List.of("../" + outside.getFileName() + "/secret.txt")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("越出");
    }

    /**
     * 裸文件名也要过沙箱：包目录里放一个指向外面的符号链接，
     * 模型只要给这个名字就能把外面的文件读出来——而这个名字既不含斜杠也不以点开头，
     * "看着像路径才校验"的判据正好漏掉它。
     */
    @Test
    @DisplayName("包内指向外部的符号链接，光给文件名也读不出来")
    void 裸文件名上的符号链接被挡住(@TempDir Path parent, @TempDir Path outside) throws IOException {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        ScriptPackage pkg = ScriptPackageFixtures.executable("reader3", parent,
                ScriptPackageFixtures.script("cat \"$1\""));
        Files.createSymbolicLink(pkg.root().resolve("leak.txt"), secret);

        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg));

        assertThatThrownBy(() -> runner.run("reader3", List.of("leak.txt")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("符号链接");
    }

    @Test
    @DisplayName("包内的文件正常读到")
    void 包内文件读得到(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("reader2", parent,
                ScriptPackageFixtures.script("cat \"$1\""));
        Files.writeString(pkg.root().resolve("note.txt"), "inside");

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("reader2", List.of("note.txt")))
                .isEqualTo("inside");
    }

    /** 报错要说清有哪些包：模型拼错工具名时，这句话是它能改的唯一依据。 */
    @Test
    @DisplayName("包不存在时把有哪些包一并说出来")
    void 未知包名(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("known", parent,
                ScriptPackageFixtures.script("echo hi"));

        assertThatThrownBy(() -> new ScriptPackageRunner(List.of(pkg)).run("unknown", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("known");
    }

    /** 超时/非零退出要有原话，模型才有东西可改（GLOSSARY 的"自纠错"）。 */
    @Test
    @DisplayName("脚本跑失败时把退出码与 stderr 带上")
    void 失败带原话(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("failing", parent,
                ScriptPackageFixtures.script("echo 'boom' >&2", "exit 3"));

        assertThatThrownBy(() -> new ScriptPackageRunner(List.of(pkg)).run("failing", List.of()))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("boom");
    }

    /**
     * 输出也有上限：一个 {@code cat} 就能把上下文撑爆，撑爆之后没人看得见答案。
     * 截断必须说出来，否则模型拿到的是一段看起来完整的半截文本。
     */
    @Test
    @DisplayName("过长的输出被截断，且说清被截断了")
    void 输出截断(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("flood", parent,
                ScriptPackageFixtures.script("yes x | head -n 5000"));

        String output = new ScriptPackageRunner(List.of(pkg)).run("flood", List.of());

        assertThat(output).hasSizeLessThanOrEqualTo(ScriptPackageRunner.MAX_OUTPUT_CHARS + 100);
        assertThat(output).contains("已截断");
    }

    /** 换沙箱换的是注入进来的那个后端，调用方这一侧一行不动。 */
    @Test
    @DisplayName("后端可替换：注入记录器时脚本一次都没真的跑")
    void 后端可替换(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("swapped", parent,
                ScriptPackageFixtures.script("echo real"));
        RecordingExecBackend recording = new RecordingExecBackend();

        String output = new ScriptPackageRunner(List.of(pkg), recording).run("swapped", List.of());

        assertThat(output).isEqualTo("recorded");
        assertThat(recording.specs()).singleElement()
                .satisfies(spec -> assertThat(spec.command()).contains("./run.sh"));
    }

    /**
     * 注入进来的记录器：只证明"调用方认 {@code ExecBackend} 这个接口"，
     * 不碰任何进程——这条缝存在与否，就靠它。
     */
    private static final class RecordingExecBackend implements ExecBackendFactory {

        private final List<ExecSpec> specs = new ArrayList<>();

        @Override
        public ExecBackend forPackage(ScriptPackage pkg) {
            return new ExecBackend() {
                @Override
                public ExecResult run(ExecSpec spec) {
                    specs.add(spec);
                    return ExecResult.completed(0, "recorded", "");
                }

                @Override
                public ExecHandle start(ExecSpec spec) {
                    throw new UnsupportedOperationException("这条用例不需要后台执行");
                }
            };
        }

        List<ExecSpec> specs() {
            return specs;
        }
    }
}
