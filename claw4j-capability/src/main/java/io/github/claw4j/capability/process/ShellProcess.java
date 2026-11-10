package io.github.claw4j.capability.process;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个跑在后台的 shell：持有进程、持续收流、按需交回增量。
 *
 * <p>它放在 process 包而不是 {@code exec} 包，是因为它只跟"起进程"有关，与工具形态无关。
 * 这里不 new 任何进程对象，所以起进程的入口仍然只有两个（{@link LocalProcessRunner}、
 * {@link ShellCommandRunner}）。
 *
 * <p>给增量而不是全量：后台命令的输出可能一直在长，每次问都回全量的话，一条 {@code tail -f}
 * 五分钟就能把上下文吃光。调用方说"自上次以来新增了什么"，这也正是 Spring AI 的
 * {@code BashOutput} 要求的语义。
 *
 * <p>过滤掉的就不再有第二次机会：带过滤问一次，没匹配上的那部分同样算已读。这样"增量"始终单调，
 * 否则过滤之后位置不动，下一次问会把同一批行再筛一遍。
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

    /**
     * 后台 shell 的编号。
     *
     * @return 形如 {@code shell_<n>} 的 id
     */
    public String id() {
        return id;
    }

    /**
     * 进程还在不在跑。
     *
     * @return 还活着返回 {@code true}
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * 只在该进程已经结束时给得出值，还在跑时抛 {@link IllegalThreadStateException}——
     * 这正是 {@link Process#exitValue()} 的语义，也是 Spring AI 的 {@code BashOutput} 在等的那一种。
     *
     * @return 进程的退出码
     * @throws IllegalThreadStateException 进程还在跑
     */
    public int exitCode() {
        return process.exitValue();
    }

    /** 强制结束这个后台进程。 */
    public void kill() {
        process.destroyForcibly();
    }

    /**
     * 取走自上次调用以来的新增输出，{@code stderr} 接在 {@code stdout} 之后。
     *
     * @param filter 行过滤（正则）；{@code null} 或空串表示不过滤
     * @return 自上次调用以来的新增输出；带过滤时只留匹配的行
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
