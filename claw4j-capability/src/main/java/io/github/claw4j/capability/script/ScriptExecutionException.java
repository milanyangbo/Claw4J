package io.github.claw4j.capability.script;

import io.github.claw4j.capability.process.ProcessOutcome;

/**
 * 脚本跑了但没成功：超时，或非零退出。
 *
 * <p>与 {@link SandboxViolationException} 的分工：那个是"被拦下来，根本没跑"，这个是"跑了，
 * 但没跑出结果"。消息里带上退出码与 stderr 的原话，因为自纠错的前提是模型拿到具体的失败原因
 * （GLOSSARY 的"自纠错"）：抛一句笼统的"执行失败"，它没有任何东西可改。
 */
public class ScriptExecutionException extends RuntimeException {

    /**
     * 用执行结果与一句摘要组装消息。
     *
     * @param outcome 进程的执行结果，退出码与 stderr 从这里取
     * @param summary 说明哪个包、怎么失败的
     */
    public ScriptExecutionException(ProcessOutcome outcome, String summary) {
        super(summary + "（退出码=" + outcome.exitCode() + "，stderr=" + oneLine(outcome.stderr()) + "）");
    }

    /** stderr 可能很长也可能没有；只取第一行非空内容，够模型判断就够了。 */
    private static String oneLine(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "空";
        }
        return stderr.lines().filter(line -> !line.isBlank()).findFirst().orElse("空");
    }
}
