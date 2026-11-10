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
 * 脚本包的调用入口：按名字找包，参数过沙箱，起子进程，只交回 stdout。
 *
 * <p>执行后端来自 {@link ProcessRunnerFactory}，换沙箱形态不动这个类。按名字找不到包时，
 * 异常里连现有的包名一起给出：模型拼错工具名时，那是它唯一的修改依据。
 *
 * <p>每个参数在 argv 里各占一个元素，先过 {@link SandboxPolicy#resolveInside}，不做引号转义
 * （票 08）。转义要挡的是"参数被解释器当成语法"，而数组形态里没有解释器这一环。
 * 入口脚本来自包清单，不由模型提供。
 *
 * <p>脚本本体不进上下文，只有输出进（GLOSSARY 的 Script Package）。这条靠结构维持：
 * 这里只有一条返回路径，调用方拿不到脚本文件的任何一部分。
 *
 * <p>子进程起在应用容器内，锁得住入口、参数、工作目录、环境变量与时长这五样，锁不住脚本
 * 体内自己去读绝对路径或连网。后一种越界要靠另一台容器（ADR-0003 的第 ② 类沙箱）。
 */
public final class ScriptPackageRunner {

    /** 进上下文的输出上限：一次 {@code cat} 就足以占满上下文，占满之后调用方看不到任何答案。 */
    public static final int MAX_OUTPUT_CHARS = 8_000;

    private static final String EMPTY_OUTPUT = "（脚本没有输出）";

    private final Map<String, ScriptPackage> packages = new LinkedHashMap<>();

    private final Map<String, ProcessRunner> runners = new LinkedHashMap<>();

    private final SandboxPolicy policy;

    private final ProcessRunnerFactory factory;

    /**
     * 按包清单建一个执行入口，执行后端用 {@link ProcessRunnerFactory#SANDBOXED}。
     *
     * @param packages 启动时从目录读出来的包
     */
    public ScriptPackageRunner(List<ScriptPackage> packages) {
        this(packages, ProcessRunnerFactory.SANDBOXED);
    }

    /**
     * 按包清单建一个执行入口，执行后端由调用方给定。
     *
     * @param packages 启动时从目录读出来的包
     * @param factory 换沙箱的地方；默认 {@link ProcessRunnerFactory#SANDBOXED}
     */
    public ScriptPackageRunner(List<ScriptPackage> packages, ProcessRunnerFactory factory) {
        this(packages, factory, SandboxPolicy.defaults());
    }

    /**
     * 按包清单建一个执行入口，执行后端与沙箱策略都由调用方给定。
     *
     * @param packages 启动时从目录读出来的包
     * @param factory 换沙箱的地方
     * @param policy 参数、环境、超时与身份的判据
     */
    public ScriptPackageRunner(List<ScriptPackage> packages, ProcessRunnerFactory factory, SandboxPolicy policy) {
        Objects.requireNonNull(packages, "packages");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.factory = Objects.requireNonNull(factory, "factory");
        for (ScriptPackage pkg : packages) {
            register(pkg);
        }
    }

    /**
     * 运行时登记一个人审通过的包（06 号票的第五步）。
     *
     * <p>要能后加，是因为"生成 → 人审 → 注册"发生在应用已经跑起来之后。让新包进清单只有两条路：
     * 重启进程，或者让清单可追加。前者把"人点了一下通过"变成一次发布，后者只多一个入口。
     *
     * <p>重名直接失败，与构造时同一口径：两个同名工具会让模型分不清该调哪个；覆盖则让
     * "人审通过的是这一版"说不清。
     *
     * @param pkg 人审通过的脚本包
     */
    public synchronized void register(ScriptPackage pkg) {
        Objects.requireNonNull(pkg, "pkg");
        if (packages.putIfAbsent(pkg.name(), pkg) != null) {
            throw new IllegalStateException("已经有一个叫 " + pkg.name() + " 的脚本包："
                    + "同名工具会让模型分不清该调哪个，这里不去重而是直接失败");
        }
        runners.put(pkg.name(), factory.forPackage(pkg));
    }

    /**
     * 清单里现有的包名，按登记顺序。
     *
     * @return 包名的只读快照
     */
    public synchronized List<String> names() {
        return List.copyOf(packages.keySet());
    }

    /**
     * 跑一个脚本包，取它写到标准输出上的内容。
     *
     * <p>非零退出与超时都算失败，不把半截 stdout 当结果交回去：一次失败的工具结果能让模型改
     * 参数重试，一段看起来正常的半截输出不能。
     *
     * @param name 包名
     * @param arguments 传给脚本的参数；{@code null} 当空列表
     * @return 截断后的 stdout；没有输出时是一句说明
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
     * 组装 argv：入口脚本来自包清单，参数全部过沙箱，只有这一处组装。
     *
     * <p>每个参数都先过 {@link SandboxPolicy#resolveInside}，不做"像不像路径"的预判。那个判据会漏：
     * 包目录里放一个指向外面的符号链接，名为 {@code leak.txt}，模型给这个裸文件名就能读出外部文件，
     * 而它既不含斜杠也不以点开头。一律校验的代价为零，普通字符串本来就在包目录内。
     *
     * <p>不做引号转义（票 08）：这里给的是数组，进程收到的就是元素本身，带空格或分号的参数
     * 只是一个字符串。少了"有人会解释它"这一环，转义从"必须"变成"无用"。
     *
     * @param pkg 要执行的包
     * @param arguments 模型给的参数列表，其中的 {@code null} 项被跳过
     * @return 交给执行后端的请求
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
     * 截断输出并在后面注明被截断了。
     *
     * <p>必须说出来：悄悄截掉的话，模型拿到的是一段看起来完整的文本，于是它基于半截输出
     * 给出一个自信的答案。
     *
     * @param stdout 进程的标准输出
     * @return 去掉首尾空白、必要时截断的文本
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

    /**
     * 包清单，按登记顺序；给日志与装配断言用。
     *
     * @return 包的只读快照
     */
    public synchronized List<ScriptPackage> packages() {
        return List.copyOf(packages.values());
    }
}
