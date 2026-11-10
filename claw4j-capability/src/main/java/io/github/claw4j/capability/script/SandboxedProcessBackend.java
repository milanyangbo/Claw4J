package io.github.claw4j.capability.script;

import io.github.claw4j.capability.process.LocalProcessRunner;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 脚本层的执行后端：把 {@link ProcessRunner} 这条接缝落到"应用容器内的一个受限子进程"上。
 *
 * <p>调用方只认 {@link ProcessRunner} 这个接口，换沙箱等于换一个实现，调用方一行不动。
 * 这是这条边界唯一可测的地方，所以"本地执行"不写死在调用方。
 *
 * <p>约束与执行在这里分开：约束是策略（见 {@link SandboxPolicy}），起进程是执行
 * （{@link LocalProcessRunner}，全仓只有那一个类碰得到 {@code ProcessBuilder}）。换远端执行器时
 * 四条约束不用重写，"忘了重写其中一条"这种失效也就不会出现。
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
     * <p>环境由 {@link SandboxPolicy#environment()} 给，不由这里挑："子进程能看见哪些变量"
     * 是同一条策略，两个执行位置都得照它，在这里另给一份等于让两处能给出不同的答案。
     *
     * @param pkg 要执行的包
     * @return 包目录为工作目录、只带 {@code PATH} 的后端
     */
    public static SandboxedProcessBackend forPackage(ScriptPackage pkg) {
        return forPackage(pkg, SandboxPolicy.defaults(), () -> System.getProperty("user.name"));
    }

    /**
     * 按脚本包开一个执行后端，策略与账号来源由调用方给定。
     *
     * @param pkg 要执行的包
     * @param policy 四条约束的判据
     * @param accountName 当前执行账号的取值来源，测试用它固定身份
     * @return 该包的执行后端
     */
    public static SandboxedProcessBackend forPackage(ScriptPackage pkg, SandboxPolicy policy,
                                                     Supplier<String> accountName) {
        Objects.requireNonNull(pkg, "pkg");
        ProcessRunner delegate = new LocalProcessRunner(pkg.root(), policy.environment());
        return new SandboxedProcessBackend(delegate, policy, accountName, pkg.root());
    }

    /**
     * 这个后端把进程起在哪个目录里。断言工作目录时用得到，也用在拒绝信息里。
     *
     * @return 包目录的绝对路径
     */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 同步执行：身份与超时先过一遍，再交给下层起进程。
     *
     * <p>顺序有讲究：两项检查都在进程起来之前。起来之后再判超时就变成"杀进程"，而杀进程可能
     * 失败（脚本 fork 了子进程），留下没人解释得清的幽灵负载。
     *
     * @param request 要执行的 argv 与超时
     * @return 进程的执行结果
     * @throws SandboxViolationException 身份是 root，或超时越界
     */
    @Override
    public ProcessOutcome run(ProcessRequest request) {
        Objects.requireNonNull(request, "request");
        policy.requireNonRoot(accountName.get());
        policy.requireTimeoutWithinCap(request.timeoutMillis());
        return delegate.run(request);
    }
}
