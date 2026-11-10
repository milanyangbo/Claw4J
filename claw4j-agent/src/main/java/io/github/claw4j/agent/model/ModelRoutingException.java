package io.github.claw4j.agent.model;

/**
 * 模型调用失败。两种情形共用这一个类型，区别在 {@code getCause()}：
 *
 * <ul>
 *   <li>所有候选都试过了：每一次失败的原始异常都挂在 {@code getSuppressed()} 上，顺序与路由顺序
 *       一致。只看最外层异常容易以为主模型的问题就是全部原因，其实两家可能死在完全不同的地方。
 *   <li>第一个候选就失败，且不值得再试：原始异常是 {@code getCause()}，消息里点名是哪一家、
 *       什么错。这类错误（key 失效、参数写错）换一家也一样。
 * </ul>
 */
public class ModelRoutingException extends RuntimeException {

    /**
     * @param message 说明哪一家、什么错
     */
    public ModelRoutingException(String message) {
        super(message);
    }

    /**
     * @param message 说明哪一家、什么错
     * @param cause   第一家失败的原始异常
     */
    public ModelRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
