package io.github.claw4j.tools.script;

import io.github.claw4j.tools.process.LocalProcessRunner;
import io.github.claw4j.tools.process.ProcessOutcome;
import io.github.claw4j.tools.process.ProcessRequest;
import io.github.claw4j.tools.process.ProcessRunner;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 脚本层的执行后端：把 {@link ProcessRunner} 这条接缝落到"应用容器内的一个受限子进程"上。
 *
 * <p><b>调用方只认 {@link ProcessRunner} 这个接口</b>（{@code ScriptPackageRunner} 就是这么写的）：
 * 换沙箱等于换一个实现，调用方一行不动。这是这条边界唯一可测的地方，
 * 所以"本地执行"绝不写死在调用方。
 *
 * <p><b>两类事在这里分开</b>：约束是策略（下面四条），起进程是执行
 * （{@link LocalProcessRunner}，全仓只有那一个类碰得到 {@code ProcessBuilder}）。
 * 分开的收益在换形态时才看得出来——换一个远端执行器时四条约束不用重写一遍，
 * 而"忘了重写其中一条"的表现是进程能跑、只是少锁了一样东西。
 *
 * <p>四条沙箱约束在这里落地：工作目录、环境、超时、非 root。
 */
public final class SandboxedProcessBackend implements ProcessRunner {

    private final ProcessRunner delegate;

    private final SandboxPolicy policy;

    private final Supplier<String> accountName;

    private final Path workingDirectory;

    SandboxedProcessBackend(ProcessRunner delegate, SandboxPolicy policy, Supplier<String> accountName,
                            Path workingDirectory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.accountName = Objects.requireNonNull(accountName, "accountName");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    /**
     * 按脚本包开一个执行后端：工作目录锁在包目录上，环境从零开始只给 {@code PATH}。
     *
     * <p>环境由 {@link SandboxPolicy#environment()} 给，不由这里挑：
     * "子进程能看见哪些变量"是同一条策略，第 ① 类与第 ② 类沙箱都得照它，
     * 在这里另给一份就等于让两处能给出不同的答案。
     */
    public static SandboxedProcessBackend forPackage(ScriptPackage pkg) {
        return forPackage(pkg, SandboxPolicy.defaults(), () -> System.getProperty("user.name"));
    }

    public static SandboxedProcessBackend forPackage(ScriptPackage pkg, SandboxPolicy policy,
                                                     Supplier<String> accountName) {
        Objects.requireNonNull(pkg, "pkg");
        ProcessRunner delegate = new LocalProcessRunner(pkg.root(), policy.environment());
        return new SandboxedProcessBackend(delegate, policy, accountName, pkg.root());
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
    public ProcessOutcome run(ProcessRequest request) {
        Objects.requireNonNull(request, "request");
        policy.requireNonRoot(accountName.get());
        policy.requireTimeoutWithinCap(request.timeoutMillis());
        return delegate.run(request);
    }
}
