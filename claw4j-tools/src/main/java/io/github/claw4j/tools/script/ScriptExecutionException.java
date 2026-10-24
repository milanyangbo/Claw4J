package io.github.claw4j.tools.script;

import org.springaicommunity.agent.common.exec.ExecResult;

/**
 * 脚本跑完了但没成功：超时、非零退出、起不来。
 *
 * <p>与 {@link SandboxViolationException} 的分工：那个是"被拦下来，根本没跑"，
 * 这个是"跑了，但没跑出结果"。消息里带上退出码与 stderr 的原话，
 * 因为自纠错的前提是模型拿到具体的失败原因（GLOSSARY 的"自纠错"条目）——
 * 抛一个笼统的"执行失败"，它没有任何东西可改。
 */
public class ScriptExecutionException extends RuntimeException {

    public ScriptExecutionException(ExecResult result, String summary) {
        super(summary + "（退出码=" + result.exitCode() + "，stderr=" + oneLine(result.stderr()) + "）");
    }

    /** stderr 可能很长也可能没有；只取第一行非空内容，够模型判断就够了。 */
    private static String oneLine(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "空";
        }
        return stderr.lines().filter(line -> !line.isBlank()).findFirst().orElse("空");
    }
}
