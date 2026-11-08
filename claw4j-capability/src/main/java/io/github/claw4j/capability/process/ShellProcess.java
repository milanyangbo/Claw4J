package io.github.claw4j.capability.process;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个跑在后台的 shell：持有进程、持续收流、按需交回**增量**。
 *
 * <p><b>它为什么在这里而不是在 {@code exec} 包里</b>：它只跟"起进程"这件事有关，与工具形态无关；
 * 而"起进程"在全仓只有两个被点名的类（{@link LocalProcessRunner}、{@link ShellCommandRunner}），
 * 这里不 new 任何进程对象，所以不多出一个入口。
 *
 * <p><b>为什么是增量而不是全量</b>：后台命令的输出可能一直在长，每次问都回全量的话，
 * 一条 {@code tail -f} 五分钟就能把上下文吃光。调用方说"自上次以来新增了什么"，
 * 这句话也正是 Spring AI 的 {@code BashOutput} 要求的语义。
 *
 * <p><b>过滤掉的就不再有第二次机会</b>：带过滤问一次，没匹配上的那部分同样被算作"已读"。
 * 这是为了让"增量"这件事永远是单调的——否则过滤之后位置不动，下一次问会把同一批行再筛一遍。
 */
public final class ShellProcess {

    private final String id;

    private final Process process;

    private final StringBuilder stdout = new StringBuilder();

    private final StringBuilder stderr = new StringBuilder();

    /** 已经被取走的位置。收流线程只往后追加，这里只往后读，两者都靠 {@code this} 的锁。 */
    private int readStdout;

    private int readStderr;

    ShellProcess(String id, Process process) {
        this.id = Objects.requireNonNull(id, "id");
        this.process = Objects.requireNonNull(process, "process");
    }

    public String id() {
        return id;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * 只在该进程已经结束时给得出值；还在跑时抛 {@link IllegalThreadStateException}——
     * 这正是 {@link Process#exitValue()} 的语义，也是 Spring AI 的 {@code BashOutput} 在等的那一种。
     */
    public int exitCode() {
        return process.exitValue();
    }

    public void kill() {
        process.destroyForcibly();
    }

    /**
     * @param filter 行过滤（正则）；{@code null} 或空串表示不过滤
     * @return 自上次调用以来的新增输出；两路合成一份，{@code stderr} 在 {@code stdout} 之后
     */
    public synchronized String newOutput(String filter) {
        String fresh = stdout.substring(readStdout) + stderr.substring(readStderr);
        readStdout = stdout.length();
        readStderr = stderr.length();
        if (filter == null || filter.isBlank()) {
            return fresh;
        }
        Pattern pattern = Pattern.compile(filter);
        StringBuilder kept = new StringBuilder();
        for (String line : fresh.split("\n", -1)) {
            if (pattern.matcher(line).find()) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    void appendStdout(String chunk) {
        append(stdout, chunk);
    }

    void appendStderr(String chunk) {
        append(stderr, chunk);
    }

    private synchronized void append(StringBuilder buffer, String chunk) {
        buffer.append(chunk);
    }
}
