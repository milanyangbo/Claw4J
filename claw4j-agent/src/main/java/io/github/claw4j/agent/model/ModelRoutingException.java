package io.github.claw4j.agent.model;

/**
 * 模型调用失败。两种情形共用这一个类型，区别在 {@code getCause()}：
 *
 * <ul>
 *   <li><b>所有候选都试过了</b>：每一次失败的原始异常都挂在 {@code getSuppressed()} 上，
 *       顺序与路由顺序一致 —— 只看最外层异常容易以为主模型的问题就是全部原因，
 *       其实两家可能死在完全不同的地方。</li>
 *   <li><b>第一个候选就失败，且不值得再试</b>：原始异常是 {@code getCause()}，
 *       消息里点名是哪一家、什么错。这类错误（key 失效、参数写错）换一家也一样，
 *       重试只是把一次确定的错误拖成 N 次网络往返。</li>
 * </ul>
 */
public class ModelRoutingException extends RuntimeException {

    public ModelRoutingException(String message) {
        super(message);
    }

    public ModelRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
