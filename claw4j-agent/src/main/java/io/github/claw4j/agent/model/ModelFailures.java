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
 * 一次模型调用失败，<b>换另一家还有没有意义</b>。
 *
 * <p>这条判断只此一处，路由只消费它。理由不是洁癖：判断散在各家适配器里，
 * 加第三个供应商时就会各写一套，而"哪类失败该降级"会变成一个要看三份代码才知道的答案。
 *
 * <p>判据是"换一家可能好"，不是"失败了就换"：
 * <ul>
 *   <li><b>可重试</b>：限流（429）、服务端错误（5xx）、连接被拒、DNS 解析不了、读写超时。
 *       这些是对方或链路的问题，另一家大概率没事。</li>
 *   <li><b>不可重试</b>：鉴权失败（401）、无权限（403）、参数错误（400 / 422）、
 *       响应解析不了、以及本地构造错误（空消息、选项拼不出来）。
 *       这些换一家只会再失败一次，把一次确定的错误拖成 N 次网络往返，
 *       而用户最后拿到的是一堆捆在一起的异常，认不出是哪一家、什么错。</li>
 * </ul>
 *
 * <p><b>怎么认出来</b>：先看 Spring AI 自己的两类（{@link TransientAiException} /
 * {@link NonTransientAiException}，与供应商无关），再看 OpenAI 兼容家族的状态码
 * （DeepSeek 与通义都走这套 SDK，所以这里是唯一一处认识具体 SDK 的地方），
 * 最后看 JDK 的网络异常。异常会被一层层包起来（{@code RetryException}、各家的包装异常），
 * 所以判断沿 {@code getCause()} 走完整条链，而不只看最外层。
 *
 * <p><b>认不出来的失败按不可重试处理</b>。这不是保守，是让"降级"保持一个能被讲清的含义：
 * 只有明确知道"换一家可能好"时才换。反过来（默认重试）会把每一次编程错误都变成两倍的调用。
 */
public final class ModelFailures {

    private ModelFailures() {
    }

    /** 换另一家还有没有意义。 */
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
