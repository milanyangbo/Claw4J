package io.github.claw4j.tools.generator;

import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;
import org.springaicommunity.agent.exec.LocalExecBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 第 ② 类沙箱：<b>未审核代码</b>的自测位置（ADR-0003）。
 *
 * <p>与第 ① 类（{@code SandboxedExecBackend}）分清的那一条，在代码里是这样落地的：
 * <b>自测跑在一份临时副本里，不跑在候选目录里</b>。
 * 候选目录里放着"等着被人审的"脚本与这次提交的元数据，
 * 而自测跑的是模型刚写出来的东西——让它写进自己待审的那份目录，
 * 等于允许它改自己将要被审的内容（甚至给自己写一个审批标记）。
 * 副本还有一个好处：脚本在自测里写下的任何东西，跑完即删，留不下来。
 *
 * <p>三条约束在这里落地：
 * <ul>
 *   <li><b>位置</b>：临时目录，跑完递归删除。
 *   <li><b>环境</b>：只有 {@link IsolationSpec#environment()}，不继承应用进程任何一项。
 *   <li><b>预算</b>：{@link IsolationSpec#timeoutMillis()}，调用方给的环境一律拒（同 ①）。
 * </ul>
 *
 * <p><b>给不了的三样</b>，不拿去当承诺：网络隔离（{@link IsolationSpec.Network#NOT_ISOLATED}）、
 * 内存与 CPU 上限（纯 Java 对子进程没有可移植的限制手段）、
 * 脚本体内自己去读一个绝对路径（进程沙箱锁得住入口与参数，锁不住脚本自己的行为）。
 * 静态检查挡的是"明着写"的那部分，剩下的靠部署层。
 */
public final class GeneratorSandbox implements ExecBackend {

    private final IsolationSpec spec;

    private final Supplier<String> accountName;

    private final Path sandboxDirectory;

    private final ExecBackend delegate;

    private GeneratorSandbox(IsolationSpec spec, Supplier<String> accountName,
                             Path sandboxDirectory, ExecBackend delegate) {
        this.spec = spec;
        this.accountName = accountName;
        this.sandboxDirectory = sandboxDirectory;
        this.delegate = delegate;
    }

    /**
     * 把候选目录<b>复制</b>到一份临时目录里，在那儿起进程。
     *
     * <p>复制而不是直接把工作目录指过去，理由见类注释：自测跑的是未审核代码，
     * 它不该有写自己待审目录的机会。代价是一份文件拷贝，
     * 而脚本包通常只有几千字节。
     */
    public static GeneratorSandbox forCandidate(Path candidateDirectory, IsolationSpec spec) {
        return forCandidate(candidateDirectory, spec, () -> System.getProperty("user.name"));
    }

    public static GeneratorSandbox forCandidate(Path candidateDirectory, IsolationSpec spec,
                                                Supplier<String> accountName) {
        Objects.requireNonNull(candidateDirectory, "candidateDirectory");
        Objects.requireNonNull(spec, "spec");
        Path copy = copyToTemporaryDirectory(candidateDirectory);
        ExecBackend delegate = LocalExecBackend.builder()
                .workingDirectory(copy)
                .cleanEnvironment(true)
                .environment(spec.environment())
                .build();
        return new GeneratorSandbox(spec, accountName, copy, delegate);
    }

    /** 进程真正跑在哪个目录里：这条是"与第 ① 类分清"可被断言的地方。 */
    public Path sandboxDirectory() {
        return sandboxDirectory;
    }

    @Override
    public ExecResult run(ExecSpec spec) {
        Objects.requireNonNull(spec, "spec");
        String account = accountName.get();
        if (account == null || account.isBlank() || "root".equals(account)) {
            throw new IllegalStateException("自测必须跑在非 root 账号下，当前是 " + account + "（ADR-0003 第 ② 类）");
        }
        long timeout = spec.timeoutMillis();
        if (timeout <= 0 || timeout > this.spec.timeoutMillis()) {
            throw new IllegalArgumentException("自测超时必须在 (0, " + this.spec.timeoutMillis()
                    + "] 内，拿到的是 " + timeout + "ms");
        }
        if (spec.env() != null && !spec.env().isEmpty()) {
            throw new IllegalArgumentException("自测环境由沙箱独占给出，调用方不能往里塞环境变量：" + spec.env().keySet());
        }
        try {
            return delegate.run(new ExecSpec(spec.command(), timeout, Map.of()));
        }
        finally {
            deleteRecursively(sandboxDirectory);
        }
    }

    /** 后台执行一律拒绝，理由与第 ① 类相同：句柄没有超时，等于把预算打开了。 */
    @Override
    public ExecHandle start(ExecSpec spec) {
        throw new UnsupportedOperationException("自测只提供受超时约束的同步执行：后台句柄没有超时");
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
