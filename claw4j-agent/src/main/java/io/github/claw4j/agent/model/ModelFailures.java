package io.github.claw4j.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIRetryableException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * 一次模型调用失败之后，换另一家还有没有意义。
 *
 * <p>判断只此一处，路由只消费它：散在各家适配器里的话，加第三个供应商时会各写一套，
 * "哪类失败该降级"就变成要看三份代码才知道的答案。
 *
 * <p>判据是"换一家可能好"，不是"失败了就换"：
 * <ul>
 *   <li>可重试：限流（429）、服务端错误（5xx）、连接被拒、DNS 解析不了、读写超时。
 *       这些是对方或链路的问题，另一家大概率没事。
 *   <li>不可重试：鉴权失败（401）、无权限（403）、参数错误（400 / 422）、响应解析不了、
 *       本地构造错误（空消息、选项拼不出来）。换一家只会再失败一次，把一次确定的错误拖成
 *       N 次网络往返。
 * </ul>
 *
 * <p>怎么认：先看 Spring AI 自己的两类（{@link TransientAiException} /
 * {@link NonTransientAiException}），再看 OpenAI 兼容家族的状态码（DeepSeek 与通义都走这套 SDK，
 * 所以这里是唯一一处认识具体 SDK 的地方），最后看 JDK 的网络异常。异常会被一层层包起来，
 * 判断沿 {@code getCause()} 走完整条链。
 *
 * <p>认不出来的失败按不可重试处理：只有明确知道"换一家可能好"时才换。反过来会把每一次编程错误
 * 都变成两倍的调用。
 */
public final class ModelFailures {

    private ModelFailures() {
    }

    /**
     * 换另一家还有没有意义。
     *
     * @param failure 捕获到的异常，不能为 null
     * @return 可重试或网络类失败为 true，其余为 false
     */
    public static boolean worthTryingAnother(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        for (Throwable current = failure; current != null; current = next(current)) {
            if (current instanceof NonTransientAiException) {
                return false;
            }
            if (current instanceof TransientAiException) {
                return true;
            }
            if (current instanceof OpenAIServiceException serviceFailure) {
                return isRetryableStatus(serviceFailure.statusCode());
            }
            if (current instanceof OpenAIRetryableException) {
                return true;
            }
            if (current instanceof JsonProcessingException) {
                // 同一份解析不了的响应，换一家也解析不了——这是本地的问题，不是对方的问题。
                return false;
            }
            if (isNetworkFailure(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static boolean isNetworkFailure(Throwable failure) {
        return failure instanceof IOException
                || failure instanceof ResourceAccessException
                || failure instanceof HttpTimeoutException
                || failure instanceof TimeoutException;
    }

    private static Throwable next(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == failure ? null : cause;
    }
}
