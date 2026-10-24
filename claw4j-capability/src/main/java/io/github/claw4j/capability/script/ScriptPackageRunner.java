package io.github.claw4j.capability.script;

import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本包的调用入口：**换沙箱不动这里**（{@link ProcessRunnerFactory}）。
 *
 * <p>它做四件事，顺序就是"进程是怎么起来的"：
 * <ol>
 *   <li>按名字找到包。找不到就把有哪些包一并说出来——模型拼错工具名时，
 *       这句话就是它能改的唯一依据。
 *   <li>把参数变成 argv 里的**一个元素**：先过沙箱路径校验。
 *       参数不再需要加引号（票 08）：加引号要挡的是"参数被解释器当成语法"，
 *       而数组形态没有解释器这一步。
 *   <li>argv = 入口脚本 + 参数，交给执行后端。入口来自包清单，不由模型给。
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

    private final Map<String, ScriptPackage> packages = new LinkedHashMap<>();

    private final Map<String, ProcessRunner> runners = new LinkedHashMap<>();

    private final SandboxPolicy policy;

    private final ProcessRunnerFactory factory;

    public ScriptPackageRunner(List<ScriptPackage> packages) {
        this(packages, ProcessRunnerFactory.SANDBOXED);
    }

    /**
     * @param factory 换沙箱的地方；默认 {@link ProcessRunnerFactory#SANDBOXED}
     */
    public ScriptPackageRunner(List<ScriptPackage> packages, ProcessRunnerFactory factory) {
        this(packages, factory, SandboxPolicy.defaults());
    }

    public ScriptPackageRunner(List<ScriptPackage> packages, ProcessRunnerFactory factory, SandboxPolicy policy) {
        Objects.requireNonNull(packages, "packages");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.factory = Objects.requireNonNull(factory, "factory");
        for (ScriptPackage pkg : packages) {
            register(pkg);
        }
    }

    /**
     * 运行时再加一个包：人审通过之后才轮到它（06 号票的第五步）。
     *
     * <p>为什么要能后加：脚本包的清单原本是启动时从目录读出来的，而"生成 → 人审 → 注册"
     * 发生在应用已经跑起来之后。硬要它出现在清单里只有两条路——重启进程，
     * 或者让清单可以被追加；前者把"人点了一下通过"变成一次发布，后者只是给清单一个入口。
     *
     * <p><b>重名仍然直接失败</b>，跟构造时一个口径：两个叫同一个名字的工具会让模型
     * 分不清该调哪个，这里不去重也不覆盖——覆盖的话"人审通过的是这一版"就说不清了。
     */
    public synchronized void register(ScriptPackage pkg) {
        Objects.requireNonNull(pkg, "pkg");
        if (packages.putIfAbsent(pkg.name(), pkg) != null) {
            throw new IllegalStateException("已经有一个叫 " + pkg.name() + " 的脚本包："
                    + "同名工具会让模型分不清该调哪个，这里不去重而是直接失败");
        }
        runners.put(pkg.name(), factory.forPackage(pkg));
    }

    public synchronized List<String> names() {
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
        ProcessRequest request = requestOf(pkg, arguments == null ? List.of() : arguments);
        ProcessOutcome outcome = runners.get(name).run(request);
        if (outcome.status() != ProcessOutcome.Status.COMPLETED) {
            throw new ScriptExecutionException(outcome, "脚本包 " + name + " 的执行状态是 " + outcome.status());
        }
        if (outcome.exitCode() != 0) {
            throw new ScriptExecutionException(outcome, "脚本包 " + name + " 以非零退出");
        }
        return outputOf(outcome.stdout());
    }

    /**
     * argv 只有一处组装，且组装的东西是确定的：入口脚本来自包清单，参数全部过了沙箱。
     *
     * <p><b>每一个参数都要先过 {@link SandboxPolicy#resolveInside}，不做"像不像路径"的预判。</b>
     * 早先那版只对"看着像路径"的参数做校验，而"看着像"是个会漏的判据：
     * 包目录里放一个名叫 {@code leak.txt}、指向外面的符号链接，
     * 模型只要给这个裸文件名就能把外面的文件读出来——它既不含斜杠也不以点开头。
     * 一律校验的代价是零：普通字符串（一个城市名、一句话）本来就落在包目录内，
     * 校验一定通过，只是多走一步。
     *
     * <p><b>没有一处做引号转义，这也是有意的</b>（票 08）：转义要挡的是
     * "参数被命令解释器当成语法"，而这里给的是数组，进程收到的就是这个元素本身，
     * 一个带空格或分号的参数只是"一个带空格或分号的字符串"。
     * 少了"有人会解释它"这一环，转义就从"必须"变成"无用"。
     */
    private ProcessRequest requestOf(ScriptPackage pkg, List<String> arguments) {
        List<String> argv = new ArrayList<>();
        // 绝对路径而不是 "./run.sh"：不经解释器就没人去 PATH 里找，
        // 而"入口脚本一定在包目录里"这件事由 ScriptPackage 构造时保证。
        argv.add(pkg.entrypointPath().toString());
        for (String argument : arguments) {
            if (argument == null) {
                continue;
            }
            argv.add(tokenOf(pkg, argument));
        }
        return new ProcessRequest(List.copyOf(argv), pkg.timeoutMillis());
    }

    private String tokenOf(ScriptPackage pkg, String argument) {
        if (argument.isBlank()) {
            return "";
        }
        Path resolved = policy.resolveInside(pkg.root(), argument);
        return pkg.root().relativize(resolved).toString();
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
    public synchronized List<ScriptPackage> packages() {
        return List.copyOf(packages.values());
    }
}
