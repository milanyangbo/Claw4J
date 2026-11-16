package io.github.claw4j.capability.script;

import io.github.claw4j.capability.process.ProcessOutcome;

/**
 * 脚本跑了但没成功：超时，或非零退出。
 *
 * <p>与 {@link SandboxViolationException} 的分工：那个是"被拦下来，根本没跑"，这个是"跑了，
 * 但没跑出结果"。消息里带上退出码与**两个流**的原话，因为自纠错的前提是模型拿到具体的失败原因
 * （GLOSSARY 的"自纠错"）：抛一句笼统的"执行失败"，它没有任何东西可改。
 *
 * <p><b>stdout 也要带</b>（票 13）：脚本自己说的原因几乎都在 stdout 上
 * （{@code 读不到内容：…}、{@code 没读到可摘要的正文。}），只有崩溃才落在 stderr。
 * 此前只带 stderr，于是"打印中文时编码崩掉"那种失败在模型眼里只剩一句无关的
 * {@code FutureWarning}，它只能猜"真正的报错没出现在 stderr 里"——事实是报错根本不在 stderr 上。
 */
public class ScriptExecutionException extends RuntimeException {

    /**
     * 用执行结果与一句摘要组装消息。
     *
     * @param outcome 进程的执行结果，退出码与两个流从这里取
     * @param summary 说明哪个包、怎么失败的
     */
    public ScriptExecutionException(ProcessOutcome outcome, String summary) {
        super(summary + "（" + detailsOf(outcome) + "）");
    }

    /**
     * 退出码 + 两个流各自的第一行非空内容。
     *
     * <p>两个流都为空时写"两个流都是空的"：那说明进程被信号杀掉、或 exec 就没起来，
     * 是一条与"脚本打印了原因"完全不同的结论，不该长成同一个样子。
     */
    private static String detailsOf(ProcessOutcome outcome) {
        String stdout = oneLine(outcome.stdout());
        String stderr = oneLine(outcome.stderr());
        if (stdout.isEmpty() && stderr.isEmpty()) {
            return "退出码=" + outcome.exitCode() + "，两个流都是空的";
        }
        StringBuilder details = new StringBuilder("退出码=").append(outcome.exitCode());
        if (!stdout.isEmpty()) {
            details.append("，stdout=").append(stdout);
        }
        if (!stderr.isEmpty()) {
            details.append("，stderr=").append(stderr);
        }
        return details.toString();
    }

    /** 可能很长也可能没有；只取第一行非空内容，够模型判断就够了。 */
    private static String oneLine(String stream) {
        if (stream == null || stream.isBlank()) {
            return "";
        }
        return stream.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
    }
}
