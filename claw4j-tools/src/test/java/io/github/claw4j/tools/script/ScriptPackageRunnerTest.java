package io.github.claw4j.tools.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.claw4j.tools.process.ProcessOutcome;
import io.github.claw4j.tools.process.ProcessRequest;
import io.github.claw4j.tools.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 调用方这一侧：argv 怎么组装出来的、出去的只有什么。
 *
 * <p>这一组盯的是模型唯一能把自由文本送进进程的那个位置——参数。
 * 入口来自包清单，参数一律过沙箱路径校验；不再有转义这一步（票 08），
 * 因为数组形态没有解释器，参数里带什么都不改变"它是第几个参数"这件事。
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
     * 参数里的元字符只是"一个字符串"，不是语法（票 08 的正反对照之一）。
     *
     * <p>这条在换实现之前是靠手写引号挡住的，换之后靠的是"没有解释器"。
     * 判据不变——输出里不能出现第二条命令的痕迹——
     * 但"为什么它是绿的"从"有人转义对了"变成"没人会解释它"。
     */
    @Test
    @DisplayName("参数里的 shell 元字符不会变成第二条命令")
    void 元字符不会被解释(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("quoter", parent,
                ScriptPackageFixtures.script("echo \"got=$1\""));

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("quoter", List.of("a; echo pwned")))
                .isEqualTo("got=a; echo pwned");
    }

    /**
     * 命令替换与反引号：同一件事的另一种写法。
     *
     * <p>{@code $(...)} 在解释器手里会先执行再代入；数组形态下它就是一个普通字符串，
     * 于是"参数里写了命令替换"和"参数里写了句话"的待遇完全一样。
     */
    @Test
    @DisplayName("参数里的命令替换不会被先执行")
    void 命令替换不被执行(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("subst", parent,
                ScriptPackageFixtures.script("echo \"got=$1\""));

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("subst", List.of("$(echo pwned)")))
                .isEqualTo("got=$(echo pwned)");
        assertThat(new ScriptPackageRunner(List.of(pkg)).run("subst", List.of("`echo pwned`")))
                .isEqualTo("got=`echo pwned`");
    }

    /**
     * 正面对照：只断言"没被执行"的话，一个把所有参数都拒掉的实现也是绿的。
     *
     * <p>带空格、带中文的参数要原样到得了脚本——这是这条缝上"功能还在"的证据，
     * 少了它，"元字符没被执行"可能只是因为参数根本没传进去。
     */
    @Test
    @DisplayName("带空格与中文的参数原样传给脚本")
    void 空格与中文传得进去(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("args", parent,
                ScriptPackageFixtures.script("echo \"1=[$1]\"", "echo \"2=[$2]\""));

        assertThat(new ScriptPackageRunner(List.of(pkg)).run("args", List.of("深 圳", "a b")))
                .contains("1=[深 圳]")
                .contains("2=[a b]");
    }

    /** 入口脚本缺 shebang 时给的是一句指得出原因的话，不是操作系统的格式错误。 */
    @Test
    @DisplayName("入口没有 shebang 时报错指名缺第一行")
    void 缺shebang时报得明白(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = ScriptPackageFixtures.executable("noshebang", parent, "echo hi\n");

        assertThatThrownBy(() -> new ScriptPackageRunner(List.of(pkg)).run("noshebang", List.of()))
                .hasMessageContaining("shebang");
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
        RecordingRunner recording = new RecordingRunner();

        String output = new ScriptPackageRunner(List.of(pkg), recording).run("swapped", List.of());

        assertThat(output).isEqualTo("recorded");
        // 交给后端的是数组，不是一条命令行字符串——这一条就是票 08 的判据。
        assertThat(recording.requests()).singleElement()
                .satisfies(request -> {
                    assertThat(request.argv()).hasSize(1);
                    assertThat(request.argv().getFirst()).endsWith("run.sh");
                });
    }

    /**
     * 注入进来的记录器：只证明"调用方认 {@code ProcessRunner} 这个接口"，
     * 不碰任何进程——这条缝存在与否，就靠它。
     */
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
