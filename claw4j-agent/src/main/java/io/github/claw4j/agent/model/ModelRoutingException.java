package io.github.claw4j.agent.model;

/**
 * 所有候选模型都调用失败了。
 *
 * <p>每一次失败的原始异常都挂在 {@code getSuppressed()} 上，顺序与路由顺序一致 ——
 * 只看最外层异常容易以为主模型的问题就是全部原因，其实两家可能死在完全不同的地方。
 */
public class ModelRoutingException extends RuntimeException {

    public ModelRoutingException(String message) {
        super(message);
    }
}
