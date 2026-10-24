package io.github.claw4j.capability.script;

/**
 * 一次执行请求越过了沙箱划好的边界。
 *
 * <p>单独一个类型而不是直接用 {@link IllegalStateException}，是为了让调用方能把它
 * 和"脚本自己跑失败了"区分开：前者是**被拦下来**，后者是**跑完了但没成功**。
 * 模型看到这两种话时的下一步不一样——被拦下来就该换条路，跑失败了才值得改参数重试。
 */
public class SandboxViolationException extends RuntimeException {

    public SandboxViolationException(String message) {
        super(message);
    }

    public SandboxViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
