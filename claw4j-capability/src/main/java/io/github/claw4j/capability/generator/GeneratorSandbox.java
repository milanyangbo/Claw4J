package io.github.claw4j.capability.generator;

import io.github.claw4j.capability.process.LocalProcessRunner;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 第 ② 类沙箱：未审核代码的自测位置（ADR-0003）。
 *
 * <p>与第 ① 类（{@code SandboxedProcessBackend}）的分界落在代码上就是一句：自测跑在一份临时
 * 副本里，不跑在候选目录里。候选目录放着"等着被人审的"脚本与这次提交的元数据，而自测跑的是
 * 模型刚写出来的东西——让它写进自己待审的那份目录，等于允许它改自己将要被审的内容
 * （甚至给自己写一个审批标记）。副本还有一个好处：脚本在自测里写下的任何东西，跑完即删。
 *
 * <p>两条约束在这里落地：
 * <ul>
 *   <li>位置：临时目录，跑完递归删除。</li>
 *   <li>预算：{@link IsolationSpec#timeoutMillis()} 是上限。</li>
 * </ul>
 *
 * <p>环境那一条不由这里"拒绝注入"来保证：{@link ProcessRequest} 里没有环境变量这一项，
 * 环境只在起进程时被给出一次（{@link LocalProcessRunner}），于是"调用方塞一个变量进来"
 * 在类型上就不存在。结构上的保证比运行时再判一次更靠得住。
 *
 * <p>给不了的三样不拿去当承诺：网络隔离（{@link IsolationSpec.Network#NOT_ISOLATED}）、
 * 内存与 CPU 上限（纯 Java 对子进程没有可移植的限制手段）、脚本体内自己去读一个绝对路径
 * （进程沙箱锁得住入口与参数，锁不住脚本自己的行为）。静态检查挡的是"明着写"的那部分，
 * 剩下的靠部署层。
 */
public final class GeneratorSandbox implements ProcessRunner {

    private final IsolationSpec spec;

    private final Supplier<String> accountName;

    private final Path sandboxDirectory;

    private final ProcessRunner delegate;

    private GeneratorSandbox(IsolationSpec spec, Supplier<String> accountName,
                             Path sandboxDirectory, ProcessRunner delegate) {
        this.spec = spec;
        this.accountName = accountName;
        this.sandboxDirectory = sandboxDirectory;
        this.delegate = delegate;
    }

    /**
     * 把候选目录复制到一份临时目录里，在那儿起进程。
     *
     * <p>复制而不是把工作目录指过去，理由见类注释：自测跑的是未审核代码，它不该有写自己待审
     * 目录的机会。代价是一份文件拷贝，而脚本包通常只有几千字节。
     *
     * @param candidateDirectory 候选包目录，只读
     * @param spec 超时、输出上限与环境
     * @return 已经准备好副本的沙箱
     */
    public static GeneratorSandbox forCandidate(Path candidateDirectory, IsolationSpec spec) {
        return forCandidate(candidateDirectory, spec, () -> System.getProperty("user.name"));
    }

    /** 测试用：脚本跑在哪个账号下由这里决定，用来验非 root 那条约束。 */
    public static GeneratorSandbox forCandidate(Path candidateDirectory, IsolationSpec spec,
                                                Supplier<String> accountName) {
        Objects.requireNonNull(candidateDirectory, "candidateDirectory");
        Objects.requireNonNull(spec, "spec");
        Path copy = copyToTemporaryDirectory(candidateDirectory);
        ProcessRunner delegate = new LocalProcessRunner(copy, spec.environment());
        return new GeneratorSandbox(spec, accountName, copy, delegate);
    }

    /** 进程真正跑在哪个目录里：这条是"与第 ① 类分清"可被断言的地方。 */
    public Path sandboxDirectory() {
        return sandboxDirectory;
    }

    /**
     * 在沙箱副本里跑一次，跑完删掉副本。
     *
     * @param request 要跑的命令与超时；环境来自 {@link IsolationSpec}，不经这里传入
     * @return 进程结果，输出已按 {@link IsolationSpec#maxOutputChars()} 截断
     * @throws IllegalStateException 当前账号是 root，或取不到账号名
     * @throws IllegalArgumentException 请求的超时超过 {@link IsolationSpec#timeoutMillis()}
     */
    @Override
    public ProcessOutcome run(ProcessRequest request) {
        Objects.requireNonNull(request, "request");
        String account = accountName.get();
        if (account == null || account.isBlank() || "root".equals(account)) {
            throw new IllegalStateException("自测必须跑在非 root 账号下，当前是 " + account + "（ADR-0003 第 ② 类）");
        }
        long timeout = request.timeoutMillis();
        if (timeout > this.spec.timeoutMillis()) {
            throw new IllegalArgumentException("自测超时必须在 (0, " + this.spec.timeoutMillis()
                    + "] 内，拿到的是 " + timeout + "ms");
        }
        try {
            return delegate.run(request);
        }
        finally {
            deleteRecursively(sandboxDirectory);
        }
    }

    private static Path copyToTemporaryDirectory(Path source) {
        Path target;
        try {
            target = Files.createTempDirectory("claw4j-selftest-");
            try (Stream<Path> walk = Files.walk(source)) {
                List<Path> entries = walk.toList();
                for (Path entry : entries) {
                    Path destination = target.resolve(source.relativize(entry).toString());
                    if (Files.isDirectory(entry)) {
                        Files.createDirectories(destination);
                    }
                    else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(entry, destination);
                        makeExecutableIfScript(entry, destination);
                    }
                }
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException("为自测准备沙箱目录失败", failure);
        }
        return target;
    }

    /**
     * 入口脚本必须有可执行位，否则自测拿到的第一句话是 {@code Permission denied}——
     * 那是"忘了 chmod"，跟脚本写得好不好一点关系都没有。
     */
    private static void makeExecutableIfScript(Path source, Path copy) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Files.setPosixFilePermissions(copy, Files.getPosixFilePermissions(source));
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
        catch (IOException failure) {
            // 清理失败不影响自测结果：临时目录由操作系统回收，而把异常抛出去会盖掉真正的失败原因。
            System.err.println("删除自测沙箱目录 " + directory + " 失败：" + failure.getMessage());
        }
    }
}
