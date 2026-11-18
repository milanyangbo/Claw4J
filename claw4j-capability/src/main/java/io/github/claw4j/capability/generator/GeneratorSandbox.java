package io.github.claw4j.capability.generator;

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
 * 第 ② 类沙箱：未审核代码的自测位置（ADR-0003）。
 *
 * <p>与第 ① 类的分界落在代码上就是一句：自测跑在<b>一份临时副本</b>里，不跑在候选目录里。
 * 候选目录放着"等着被人审的"脚本与这次提交的元数据，而自测跑的是模型刚写出来的东西——
 * 让它写进自己待审的那份目录，等于允许它改自己将要被审的内容（甚至给自己写一个审批标记）。
 * 副本还有一个好处：脚本在自测里写下的任何东西，跑完即删。
 *
 * <p><b>执行器是库的</b>（{@link LocalExecBackend}），本类不自己起进程：环境整份清空后只给
 * {@link IsolationSpec#environment()}、超时到点由库先 {@code destroy()} 再 {@code forcibly}。
 * 2026-11-18 之前这里挂的是本项目自己的 {@code LocalProcessRunner}，那条路已按
 * `AGENTS.md`「执行类能力必须用现成实现」删掉。
 *
 * <p>给不了的三样不拿去当承诺：网络隔离（{@link IsolationSpec.Network#NOT_ISOLATED}）、
 * 内存与 CPU 上限、脚本体内自己去读一个绝对路径。静态检查挡的是"明着写"的那部分，剩下的靠部署层。
 */
public final class GeneratorSandbox {

    private final IsolationSpec spec;

    private final Supplier<String> accountName;

    private final Path sandboxDirectory;

    private final LocalExecBackend backend;

    private GeneratorSandbox(IsolationSpec spec, Supplier<String> accountName,
                             Path sandboxDirectory, LocalExecBackend backend) {
        this.spec = spec;
        this.accountName = accountName;
        this.sandboxDirectory = sandboxDirectory;
        this.backend = backend;
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

    /**
     * 测试用：脚本跑在哪个账号下由这里决定，用来验非 root 那条约束。
     *
     * @param candidateDirectory 候选包目录，只读
     * @param spec 超时、输出上限与环境
     * @param accountName 当前执行账号的取值来源
     * @return 已经准备好副本的沙箱
     */
    public static GeneratorSandbox forCandidate(Path candidateDirectory, IsolationSpec spec,
                                                Supplier<String> accountName) {
        Objects.requireNonNull(candidateDirectory, "candidateDirectory");
        Objects.requireNonNull(spec, "spec");
        Path copy = copyToTemporaryDirectory(candidateDirectory);
        LocalExecBackend backend = LocalExecBackend.builder()
                .workingDirectory(copy)
                .cleanEnvironment(true)
                .environment(spec.environment())
                .shellCommand("/bin/sh", "-c")
                .build();
        return new GeneratorSandbox(spec, accountName, copy, backend);
    }

    /**
     * 进程真正跑在哪个目录里：这条是"与第 ① 类分清"可被断言的地方。
     *
     * @return 临时副本的绝对路径
     */
    public Path sandboxDirectory() {
        return sandboxDirectory;
    }

    /**
     * 在沙箱副本里跑一次，跑完删掉副本。
     *
     * @param command       要跑的命令（候选脚本的绝对路径）
     * @param timeoutMillis 这次自测的超时；环境来自 {@link IsolationSpec}，不经这里传入
     * @return 执行结果，输出已按 {@link IsolationSpec#maxOutputChars()} 截断
     * @throws IllegalStateException 当前账号是 root，或取不到账号名
     * @throws IllegalArgumentException 请求的超时超过 {@link IsolationSpec#timeoutMillis()}
     */
    public ExecResult run(String command, long timeoutMillis) {
        Objects.requireNonNull(command, "command");
        String account = accountName.get();
        if (account == null || account.isBlank() || "root".equals(account)) {
            throw new IllegalStateException("自测必须跑在非 root 账号下，当前是 " + account + "（ADR-0003 第 ② 类）");
        }
        if (timeoutMillis > this.spec.timeoutMillis()) {
            throw new IllegalArgumentException("自测超时必须在 (0, " + this.spec.timeoutMillis()
                    + "] 内，拿到的是 " + timeoutMillis + "ms");
        }
        try {
            return backend.run(new ExecSpec(command, timeoutMillis, Map.of()));
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
