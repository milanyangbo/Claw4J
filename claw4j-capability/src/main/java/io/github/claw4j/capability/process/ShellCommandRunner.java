package io.github.claw4j.capability.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 把一条 shell 命令交给 {@code /bin/sh -c} 执行——**全仓唯一**允许出现 shell 解释器的地方。
 *
 * <p>{@link LocalProcessRunner} 走的是另一条路（argv 直接执行，不经解释器），那条路对脚本包是对的：
 * 参数是数据，不该被当成语法。这里是另一种能力——{@code exec}，模型要的就是 shell 语义
 * （管道、重定向、{@code &&}），照 OpenClaw 的 {@code exec} 做（ADR-0002 修订，票 01）。
 * 两条路的区别不在"哪个更安全"，在**这条字符串是谁给的**：脚本包的 argv 由包清单定死，
 * exec 的命令由模型给。
 *
 * <p><b>它只负责执行，不负责判定。</b>白名单、工作目录边界、环境里那三个不能改的键、
 * 非 root、超时上限，全在 {@code SandboxedExecBackend} 里判完才进来。分开放的理由是那些判定要能单独测：
 * 混在起进程的代码里，测它们就得真起一个进程。
 *
 * <p><b>两个入口</b>：{@link #run} 同步等到结束（超时即杀），{@link #start} 起一个后台的
 * {@link ShellProcess} 交出去，由调用方按需读增量、自己收尾。两个都要，是因为
 * Spring AI 的 {@code Bash} 工具两个都问——{@code runInBackground} 为真时它走后者。
 *
 * <p><b>收流那二十行与 {@link LocalProcessRunner} 各有一份</b>，没有抽公共工具：
 * 抽出来就多一个"谁在起进程"的入口，而这里要守的正是"起进程的入口只有两个、都被点名"。
 */
public final class ShellCommandRunner {

    /** 解释器。只给 {@code sh}，不给 bash（见票 01 的"不要做"）。 */
    private static final String SHELL = "/bin/sh";

    /** 进程退出之后，最多再等多久收流：孙进程可能还攥着管道。 */
    private static final long DRAIN_MILLIS = 2_000;

    /** 后台 shell 的编号。取时间戳起头，同一个 JVM 里重启不了，也就不必持久化。 */
    private static final AtomicLong BACKGROUND_SEQUENCE = new AtomicLong(System.currentTimeMillis());

    private final Path workingDirectory;

    public ShellCommandRunner(Path workingDirectory) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath();
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 同步跑完一条命令。
     *
     * @param command       整条 shell 命令
     * @param directory     工作目录；调用方已经判过它没出 workspace
     * @param environment   完整的环境（调用方已经合并好继承与覆盖）
     * @param timeoutMillis 墙上时钟上限
     */
    public ProcessOutcome run(String command, Path directory, Map<String, String> environment, long timeoutMillis) {
        Process process = startProcess(command, directory, environment);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread out = drainInto(process.getInputStream(), stdout);
        Thread err = drainInto(process.getErrorStream(), stderr);
        out.start();
        err.start();
        boolean exited;
        try {
            exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ProcessStartException("等待命令结束时被中断", failure);
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
     * 起一个后台 shell。**不设超时**：后台那条路的意义就是跑得比一次调用久，
     * 到点该不该停由调用方决定（Spring AI 的 {@code KillShell} 就是那个决定）。
     */
    public ShellProcess start(String command, Path directory, Map<String, String> environment) {
        Process process = startProcess(command, directory, environment);
        ShellProcess shell = new ShellProcess("shell_" + BACKGROUND_SEQUENCE.incrementAndGet(), process);
        drainInto(process.getInputStream(), shell::appendStdout).start();
        drainInto(process.getErrorStream(), shell::appendStderr).start();
        return shell;
    }

    private Process startProcess(String command, Path directory, Map<String, String> environment) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(environment, "environment");

        ProcessBuilder builder = new ProcessBuilder(SHELL, "-c", command);
        builder.directory(directory.toFile());
        // 清空再整体放回：环境由调用方算好，这里不猜"要保留哪几个"。
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.redirectErrorStream(false);
        try {
            return builder.start();
        }
        catch (IOException failure) {
            throw new ProcessStartException("命令没起起来（工作目录 " + directory + "）：" + failure.getMessage(), failure);
        }
    }

    private static Thread drainInto(InputStream source, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (InputStream in = source) {
                in.transferTo(target);
            }
            catch (IOException ignored) {
                // 进程被掐断时管道会先关：那一刻收多少算多少，不影响"超时"这个结论。
            }
        }, "claw4j-exec-drain");
        // 孙进程攥着管道时这条线程可能退不出来；设成守护线程，别拖住 JVM 退出。
        thread.setDaemon(true);
        return thread;
    }

    /** 逐块读、逐块交出去：后台那份要能"边跑边看"，攒成一个字符串再给就看不见进度了。 */
    private static Thread drainInto(InputStream source, Consumer<String> sink) {
        Thread thread = new Thread(() -> {
            char[] buffer = new char[4_096];
            try (Reader reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sink.accept(new String(buffer, 0, read));
                }
            }
            catch (IOException ignored) {
                // 同上：管道被关掉那一刻停手。
            }
        }, "claw4j-exec-stream");
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
