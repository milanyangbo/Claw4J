package io.github.claw4j.tools.process;

import java.util.Objects;

/**
 * 一次进程执行的结果。
 *
 * <p>{@code status} 与 {@code exitCode} 是两个不同的问题，别合并：
 * "跑完了但没成功"（退出码非零）与"没跑完"（超时）要分开，
 * 因为调用方对这两件事的处理不同——前者把 stderr 交给模型让它改脚本，
 * 后者说明这个脚本不该在这一层跑。
 *
 * @param status 有没有跑完
 * @param exitCode 退出码；超时时为 {@code -1}
 * @param stdout 标准输出
 * @param stderr 标准错误
 */
public record ProcessOutcome(Status status, int exitCode, String stdout, String stderr) {

    public enum Status {

        /** 进程自己退出了（退出码可能是非零）。 */
        COMPLETED,

        /** 到了上限还没退出，进程已被强制结束。 */
        TIMED_OUT
    }

    public ProcessOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    public static ProcessOutcome completed(int exitCode, String stdout, String stderr) {
        return new ProcessOutcome(Status.COMPLETED, exitCode, stdout, stderr);
    }

    /** 超时那一份：退出码没有意义，取 -1。 */
    public static ProcessOutcome timedOut(String stdout, String stderr) {
        return new ProcessOutcome(Status.TIMED_OUT, -1, stdout, stderr);
    }
}
