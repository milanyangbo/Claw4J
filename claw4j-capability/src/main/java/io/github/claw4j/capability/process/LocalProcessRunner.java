package io.github.claw4j.capability.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 脚本层的起进程实现：按 argv 直接执行，不经任何解释器。
 *
 * <p>它必须存在，是因为 ADR-0002 判的是"Java 侧不构造一条命令行交给解释器"，而社区包的执行接缝
 * 只收一个 {@code String command}，起进程时自己加解释器前缀——本项目拼好的字符串会被解释。
 * 数组形态过不去那条接缝，于是起进程收回到本项目，做成这个被点名的类。
 *
 * <p>代价写清楚：全仓禁 {@code ProcessBuilder} 的规则现在有两个例外（这里与
 * {@link ShellCommandRunner}），写在 {@code BuildContractTest} 与 {@code ToolsBoundaryTest} 里。
 * 收窄而不是删除，是因为删掉之后"Java 侧不起进程"就没有任何机器证据。
 *
 * <p>它不判工作目录该锁在哪、环境该给哪些、超时上限是多少——那些是 {@code SandboxPolicy} 的约束，
 * 由调用方在把请求交进来之前过一遍。这里只负责"按给的 argv 起一个进程，到点掐断，把两路输出收回来"。
 */
public final class LocalProcessRunner implements ProcessRunner {

    /** 进程退出之后，最多再等多久收流：孙进程可能还攥着管道，等一个无限期是不行的。 */
    private static final long DRAIN_MILLIS = 2_000;

    private final Path workingDirectory;

    private final Map<String, String> environment;

    /**
     * 用一个固定工作目录与一份完整环境建执行器。
     *
     * <p>环境在这里定死而不是每次执行现算：请求里没有环境变量这一项（票 08），
     * 于是"给了子进程什么"在构造那一刻就看得见。
     *
     * @param workingDirectory 子进程的工作目录
     * @param environment 子进程能看到的全部环境变量
     */
    public LocalProcessRunner(Path workingDirectory, Map<String, String> environment) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    /**
     * 子进程的工作目录。
     *
     * @return 构造时给定的目录
     */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 起一个进程，等到结束或超时，收回两路输出。
     *
     * <p>起进程之前先检查入口：不是普通文件、没有可执行位、第一行不是 shebang，都在这里失败。
     *
     * @param request argv 与超时
     * @return 执行结果；超时时状态是 {@link ProcessOutcome.Status#TIMED_OUT}
     * @throws ProcessStartException 入口不合格，或进程起不来
     */
    @Override
    public ProcessOutcome run(ProcessRequest request) {
        Objects.requireNonNull(request, "request");
        Path program = requireRunnable(workingDirectory, request.program());
        ProcessBuilder builder = new ProcessBuilder(request.argv());
        builder.directory(workingDirectory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        // 两路输出分开收：混成一路之后"退出码非零时该给模型看哪一句"就说不清了。
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        }
        catch (IOException failure) {
            throw new ProcessStartException("起进程失败：" + program + "（工作目录 " + workingDirectory + "）", failure);
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread out = drain(process.getInputStream(), stdout);
        Thread err = drain(process.getErrorStream(), stderr);
        out.start();
        err.start();
        boolean exited;
        try {
            exited = process.waitFor(request.timeoutMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ProcessStartException("等待 " + program + " 时被中断", failure);
        }
        if (!exited) {
            process.destroyForcibly();
            join(out);
            join(err);
            return ProcessOutcome.timedOut(text(stdout), text(stderr));
        }
        join(out);
        join(err);
        return ProcessOutcome.completed(process.exitValue(), text(stdout), text(stderr));
    }

    /**
     * 在起进程之前判明入口可执行，并给出说得清的失败原因。
     *
     * <p>直接交给操作系统时它给的是 {@code Permission denied} 与 {@code Exec format error}，
     * 两句都离"忘了设可执行位""缺 shebang"隔了一层。这两件事在不再经解释器之后才成为硬要求：
     * 交给解释器时没有 shebang 也能跑，因为解释它的就是解释器。
     *
     * @param workingDirectory 解析相对入口时用的目录
     * @param program argv 的第一项
     * @return 入口的绝对路径
     * @throws ProcessStartException 不是普通文件、没有可执行位，或第一行不是 shebang
     */
    private static Path requireRunnable(Path workingDirectory, String program) {
        Path path = Path.of(program);
        Path resolved = path.isAbsolute() ? path : workingDirectory.resolve(path);
        if (!Files.isRegularFile(resolved)) {
            throw new ProcessStartException("程序 " + program + " 在 " + resolved + " 里不是一个普通文件");
        }
        if (!Files.isExecutable(resolved)) {
            throw new ProcessStartException("入口 " + program + " 没有可执行位："
                    + "脚本不再经解释器执行，直接执行要求它自己是可执行的");
        }
        if (!hasShebang(resolved)) {
            throw new ProcessStartException("入口 " + program + " 的第一行不是 shebang（#! 开头给出解释器）："
                    + "直接执行时没人替它解释，缺这一行进程起不来");
        }
        return resolved;
    }

    private static boolean hasShebang(Path script) {
        try {
            return Files.lines(script, StandardCharsets.UTF_8).findFirst().map(line -> line.startsWith("#!")).orElse(false);
        }
        catch (IOException failure) {
            throw new ProcessStartException("读入口 " + script + " 失败", failure);
        }
    }

    private static Thread drain(InputStream source, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (InputStream in = source) {
                in.transferTo(target);
            }
            catch (IOException ignored) {
                // 进程被掐断时管道会先关：那一刻收多少算多少，不影响"超时"这个结论。
            }
        }, "claw4j-process-drain");
        // 孙进程攥着管道时这条线程可能退不出来；设成守护线程，别拖住 JVM 退出。
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(DRAIN_MILLIS);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static String text(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
