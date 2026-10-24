package io.github.claw4j.tools.script;

import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本包的调用入口：**换沙箱不动这里**（{@link ExecBackendFactory}）。
 *
 * <p>它做四件事，顺序就是"命令是怎么来的"：
 * <ol>
 *   <li>按名字找到包。找不到就把有哪些包一并说出来——模型拼错工具名时，
 *       这句话就是它能改的唯一依据。
 *   <li>把参数变成命令行上的**一个词**：看着像路径的先过沙箱路径校验，
 *       然后统一加引号。
 *   <li>命令 = 入口脚本 + 参数，交给执行后端。入口来自包清单，不由模型给。
 *   <li>只把 stdout 交回去。
 * </ol>
 *
 * <p><b>脚本本体不进上下文，只有输出进</b>（GLOSSARY 的 Script Package）。
 * 这条不是靠"没人把脚本读进来"维持的，而是靠这里只有一条返回路径：
 * 调用方拿到的永远是 stdout，拿不到脚本文件的任何一部分。
 *
 * <p><b>这个沙箱给不了什么</b>，说清楚免得被当成承诺：子进程起在应用容器内，
 * 锁得住"入口、参数、工作目录、环境变量、时长"这五样，
 * 锁不住脚本<b>体内</b>自己去读一个绝对路径、自己去连一次网。
 * 那种越界要靠另一台容器（ADR-0003 的第 ② 类沙箱，06 号票）；
 * 在 README 里只会写这里真的挡住了什么。
 */
public final class ScriptPackageRunner {

    /** 进上下文的输出上限。一个 {@code cat} 就能把上下文撑爆，而撑爆之后没人看得见答案。 */
    public static final int MAX_OUTPUT_CHARS = 8_000;

    private static final String EMPTY_OUTPUT = "（脚本没有输出）";

    private final Map<String, ScriptPackage> packages;

    private final Map<String, ExecBackend> backends;

    private final SandboxPolicy policy;

    public ScriptPackageRunner(List<ScriptPackage> packages) {
        this(packages, ExecBackendFactory.SANDBOXED);
    }

    /**
     * @param factory 换沙箱的地方；默认 {@link ExecBackendFactory#SANDBOXED}
     */
    public ScriptPackageRunner(List<ScriptPackage> packages, ExecBackendFactory factory) {
        this(packages, factory, SandboxPolicy.defaults());
    }

    public ScriptPackageRunner(List<ScriptPackage> packages, ExecBackendFactory factory, SandboxPolicy policy) {
        Objects.requireNonNull(packages, "packages");
        this.policy = Objects.requireNonNull(policy, "policy");
        Map<String, ScriptPackage> byName = new LinkedHashMap<>();
        Map<String, ExecBackend> backends = new LinkedHashMap<>();
        for (ScriptPackage pkg : packages) {
            Objects.requireNonNull(pkg, "pkg");
            if (byName.put(pkg.name(), pkg) != null) {
                throw new IllegalStateException("两个脚本包叫同一个名字 " + pkg.name()
                        + "：同名工具会让模型分不清该调哪个，这里不去重而是直接失败");
            }
            backends.put(pkg.name(), Objects.requireNonNull(factory, "factory").forPackage(pkg));
        }
        this.packages = Map.copyOf(byName);
        this.backends = Map.copyOf(backends);
    }

    public List<String> names() {
        return List.copyOf(packages.keySet());
    }

    /**
     * 跑一个脚本包，返回它写到标准输出上的内容。
     *
     * @throws IllegalArgumentException 没有这个包
     * @throws SandboxViolationException 参数越出沙箱，或执行环境被拦下
     * @throws ScriptExecutionException 脚本跑了但没跑出结果
     */
    public String run(String name, List<String> arguments) {
        ScriptPackage pkg = packages.get(name);
        if (pkg == null) {
            throw new IllegalArgumentException("没有名为 " + name + " 的脚本包；现在有：" + packages.keySet());
        }
        String command = commandOf(pkg, arguments == null ? List.of() : arguments);
        ExecResult result = backends.get(name)
                .run(new ExecSpec(command, pkg.timeoutMillis(), Map.of()));
        if (result.status() != ExecResult.Status.COMPLETED) {
            throw new ScriptExecutionException(result, "脚本包 " + name + " 的执行状态是 " + result.status());
        }
        if (result.exitCode() != 0) {
            throw new ScriptExecutionException(result, "脚本包 " + name + " 以非零退出");
        }
        return outputOf(result.stdout());
    }

    /**
     * 命令行只有一处拼装，且拼的东西是确定的：入口脚本来自包清单，参数全部过了沙箱又加了引号。
     *
     * <p><b>每一个参数都要先过 {@link SandboxPolicy#resolveInside}，不做"像不像路径"的预判。</b>
     * 早先那版只对"看着像路径"的参数做校验，而"看着像"是个会漏的判据：
     * 包目录里放一个名叫 {@code leak.txt}、指向外面的符号链接，
     * 模型只要给这个裸文件名就能把外面的文件读出来——它既不含斜杠也不以点开头。
     * 一律校验的代价是零：普通字符串（一个城市名、一句话）本来就落在包目录内，
     * 校验一定通过，只是多走一步。
     *
     * <p>单引号同样一个都不能少：不加的话一个带空格或分号的参数就能在命令行上
     * 变出第二条命令，而"命令行是谁拼的"这件 ADR-0002 要管的事就从后门回来了。
     */
    private String commandOf(ScriptPackage pkg, List<String> arguments) {
        // "./" 是必须的：命令由 shell 执行，而 shell 只在 PATH 里找命令、不在当前目录找。
        // 少了它，"入口脚本就在包目录里"这件确定的事会变成一次 command not found。
        StringBuilder command = new StringBuilder(quote("./" + Path.of(pkg.entrypoint()).normalize()));
        for (String argument : arguments) {
            if (argument == null) {
                continue;
            }
            command.append(' ').append(tokenOf(pkg, argument));
        }
        return command.toString();
    }

    private String tokenOf(ScriptPackage pkg, String argument) {
        if (argument.isBlank()) {
            return quote("");
        }
        Path resolved = policy.resolveInside(pkg.root(), argument);
        return quote(pkg.root().relativize(resolved).toString());
    }

    /** 单引号包起来，内部的单引号按 POSIX 的写法断开再接上。 */
    private static String quote(String raw) {
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    /**
     * 输出是唯一进上下文的东西，所以它也要有上限。
     * 截断要<b>说清楚被截断了</b>：悄悄截掉的话，模型拿到的是一段看起来完整的文本，
     * 于是它基于半截输出给了一个自信的答案。
     */
    private static String outputOf(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return EMPTY_OUTPUT;
        }
        String text = stdout.strip();
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n…（输出超过 " + MAX_OUTPUT_CHARS + " 字符，已截断）";
    }

    /** 给日志与装配断言：这些包各自会在哪个目录里跑。 */
    public List<ScriptPackage> packages() {
        return new ArrayList<>(packages.values());
    }
}
