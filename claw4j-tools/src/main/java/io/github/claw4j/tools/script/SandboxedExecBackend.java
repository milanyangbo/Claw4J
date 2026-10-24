package io.github.claw4j.tools.script;

import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;
import org.springaicommunity.agent.exec.LocalExecBackend;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 脚本层的执行后端：把 {@link ExecBackend} 这条接缝落到"应用容器内的一个受限子进程"上。
 *
 * <p><b>调用方只认 {@link ExecBackend} 这个接口</b>（{@code ScriptPackageRunner} 就是这么写的）：
 * 换沙箱等于换一个实现，调用方一行不动。这是这条边界唯一可测的地方，
 * 所以"本地执行"绝不写死在调用方。
 *
 * <p><b>Java 侧不自己拼命令行</b>（ADR-0002）。起进程这件事发生在社区包的
 * {@link LocalExecBackend} 里，本项目主代码里没有 {@code Runtime.exec}、
 * 没有 {@code ProcessBuilder}、也没有手写的 shell 调用——
 * 这三样各有一条机器检查盯着（app 侧的架构测试与源码扫描），不靠 review 记。
 * 命令行里唯一由本项目拼出来的部分是"入口脚本 + 参数"，
 * 而且入口来自包清单、参数经过路径校验与引号转义（见 {@code ScriptPackageRunner}）。
 *
 * <p>四条沙箱约束在这里落地：工作目录、环境、超时、非 root。
 */
public final class SandboxedExecBackend implements ExecBackend {

    private final ExecBackend delegate;

    private final SandboxPolicy policy;

    private final Supplier<String> accountName;

    private final Path workingDirectory;

    SandboxedExecBackend(ExecBackend delegate, SandboxPolicy policy, Supplier<String> accountName,
                         Path workingDirectory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.accountName = Objects.requireNonNull(accountName, "accountName");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    /**
     * 按脚本包开一个执行后端：工作目录锁在包目录上，环境从零开始只给 {@code PATH}。
     *
     * <p>不指定 shell：用后端自己的默认（POSIX 上是 bash 的 {@code -c}，
     * Windows 上是那边命令解释器的 {@code /c}）。写死一个 shell 就等于
     * 把"该用哪个 shell"这件与平台有关的事搬进本项目，而它本来不属于这里。
     */
    public static SandboxedExecBackend forPackage(ScriptPackage pkg) {
        return forPackage(pkg, SandboxPolicy.defaults(), () -> System.getProperty("user.name"));
    }

    public static SandboxedExecBackend forPackage(ScriptPackage pkg, SandboxPolicy policy,
                                                  Supplier<String> accountName) {
        Objects.requireNonNull(pkg, "pkg");
        ExecBackend delegate = LocalExecBackend.builder()
                .workingDirectory(pkg.root())
                .cleanEnvironment(true)
                .environment(policy.environment())
                .build();
        return new SandboxedExecBackend(delegate, policy, accountName, pkg.root());
    }

    /** 这个后端把进程起在哪个目录里。断言工作目录时用得到，也用在拒绝信息里。 */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 同步执行：四条约束先过一遍，再交给下层。
     *
     * <p>顺序是有讲究的：身份与超时的检查在进程起来<b>之前</b>。
     * 起来之后再判超时就变成"杀进程"，而杀进程失败（脚本 fork 了子进程）
     * 是一种没人能解释清楚的幽灵负载。
     */
    @Override
    public ExecResult run(ExecSpec spec) {
        Objects.requireNonNull(spec, "spec");
        policy.requireNonRoot(accountName.get());
        policy.requireTimeoutWithinCap(spec.timeoutMillis());
        policy.requireNoInjectedEnvironment(spec.env());
        return delegate.run(spec);
    }

    /**
     * 后台执行一律拒绝。
     *
     * <p>{@link ExecHandle} 是没有超时的：拿到句柄的人想让它跑多久就跑多久，
     * 于是"脚本层有超时"这条约束被一个不在这张票上的开关悄悄打开。
     * 与其留一个看着能用、实则绕过预算的方法，不如让它当场失败，
     * 并说清为什么。将来真要长任务，走的是 ADR-0003 的第 ② 类沙箱（06 号票）。
     */
    @Override
    public ExecHandle start(ExecSpec spec) {
        throw new UnsupportedOperationException(
                "沙箱只提供受超时约束的同步执行：后台句柄没有超时，等于把脚本层的执行预算打开了");
    }
}
