package io.github.claw4j.model.failover;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIRetryableException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;

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
 *   <li>可重试：<b>拿到的字节解析不出来</b>（工具参数不是合法 JSON、响应体截断）。
 *       换一家就是换一份新字节，值得试；但对<b>同一家</b>不做整轮重生成——坏参数的正确归处是
 *       工具层：框架把它转成一段工具结果回灌给模型，模型在同一轮自己重发调用（票 02）。
 *   <li>不可重试：鉴权失败（401）、无权限（403）、参数错误（400 / 422）、
 *       本地构造错误（空消息、选项拼不出来）。换一家只会再失败一次，
 *       把一次确定的错误拖成 N 次网络往返。
 * </ul>
 *
 * <p>怎么认：先看 Spring AI 自己的两类（{@link TransientAiException} /
 * {@link NonTransientAiException}），再看 OpenAI 兼容家族的状态码（DeepSeek 与通义都走这套 SDK，
 * 所以这里是唯一一处认识具体 SDK 的地方），再看 JSON 解析失败，最后看 JDK 的网络异常。
 * 异常会被一层层包起来，判断沿 {@code getCause()} 走完整条链。
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
            if (isUnparseableContent(current)) {
                return true;
            }
            if (isNetworkFailure(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是不是"拿到的字节解析不出来"。
     *
     * <p>两个包名都要认，来源不同：
     * <ul>
     *   <li>{@link JacksonException}（{@code tools.jackson}）：本仓自己的代码与 Spring AI 都用这一份，
     *       Spring AI 解析工具参数时抛的就是它。
     *   <li>{@link JsonProcessingException}（{@code com.fasterxml.jackson}）：Jackson 2 仍随
     *       {@code openai-java-core} 在 classpath 上——那是 SDK 自己解析 HTTP 响应体用的，解析失败时
     *       它把异常包进 {@code OpenAIInvalidDataException} 抛出来。只认前者的话，这条会走到末尾
     *       "认不出来 → 不重试"。
     * </ul>
     *
     * <p>认的是异常类型，不是"我们依赖 Jackson 2"：本仓没有任何一行代码 import 它的 databind。
     */
    private static boolean isUnparseableContent(Throwable failure) {
        return failure instanceof JacksonException
                || failure instanceof JsonProcessingException;
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
