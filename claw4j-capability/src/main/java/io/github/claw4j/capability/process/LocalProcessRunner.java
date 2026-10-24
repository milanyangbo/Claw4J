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
 * 全仓库唯一起进程的地方。
 *
 * <p>它为什么必须存在：ADR-0002 判的是"Java 侧不构造一条命令行交给命令解释器"，
 * 而这条判据在脚本层原来是不成立的——社区包的执行接缝只收一个 {@code String command}，
 * 起进程时自己加解释器的前缀，于是本项目拼好的那条字符串是被解释的
 * （票 08 之前唯一的防线是一处手写引号）。数组形态过不去那条接缝，
 * 所以起进程这件事收回到本项目里，做成这一个被点名的类。
 *
 * <p><b>代价写清楚</b>：全仓禁 {@code ProcessBuilder} 的那条规则现在有一个例外，
 * 例外就是这个类的全限定名，写在 {@code AppArchitectureTest} 里。
 * 规则从"任何地方都不许"收窄成"除了这一个被点名的类以外都不许"——
 * 收窄而不是删除，是因为删掉之后"Java 侧不起进程"就没有任何机器证据了。
 *
 * <p><b>它不做的事</b>：不判工作目录该锁在哪、不判环境该给哪些、不判超时上限。
 * 那些是 {@code SandboxPolicy} 与第 ② 类沙箱的约束，由调用方在把请求交进来之前过一遍。
 * 这里只负责"按给的 argv 起一个进程，到点掐断，把两路输出收回来"。
 */
public final class LocalProcessRunner implements ProcessRunner {

    /** 进程退出之后，最多再等多久收流：孙进程可能还攥着管道，等一个无限期是不行的。 */
    private static final long DRAIN_MILLIS = 2_000;

    private final Path workingDirectory;

    private final Map<String, String> environment;

    public LocalProcessRunner(Path workingDirectory, Map<String, String> environment) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

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
     * 起进程之前先说清楚"为什么起不来"。
     *
     * <p>没有可执行位时操作系统给的是 {@code Permission denied}，那句话离
     * "忘了设可执行位"隔了一层；缺 shebang 时给的是 {@code Exec format error}，
     * 更看不懂。这两件事在"不再经解释器"之后才变成硬要求——
     * 交给解释器时没有 shebang 也能跑，因为解释它的人就是解释器。
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
