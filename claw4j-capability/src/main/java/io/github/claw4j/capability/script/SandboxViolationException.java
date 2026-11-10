package io.github.claw4j.capability.script;

/**
 * 一次执行请求越过了沙箱划好的边界。
 *
 * <p>单独一个类型而不是直接用 {@link IllegalStateException}，是为了让调用方把它和"脚本自己
 * 跑失败了"区分开：前者是被拦下来，后者是跑完了但没成功。模型看到这两种话时的下一步不一样：
 * 被拦下来就该换条路，跑失败了才值得改参数重试。
 */
public class SandboxViolationException extends RuntimeException {

    /**
     * 用一句说明建异常。
     *
     * @param message 越界的是哪一条约束、拿到的值是什么
     */
    public SandboxViolationException(String message) {
        super(message);
    }

    /**
     * 用一句说明与一个底层原因建异常。
     *
     * @param message 越界的是哪一条约束、拿到的值是什么
     * @param cause 触发这次判定的底层异常，例如路径解析失败
     */
    public SandboxViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
