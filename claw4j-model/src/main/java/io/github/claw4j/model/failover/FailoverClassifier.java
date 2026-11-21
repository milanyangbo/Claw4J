package io.github.claw4j.model.failover;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIRetryableException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 把一次失败翻成 {@link FailoverReason}。
 *
 * <p>四层判据，顺序照 OpenClaw 的优先级（`src/agents/failover/classify-core.ts:127-196`）：
 * <ol>
 *   <li>Spring AI 自己的两类（{@link TransientAiException} / {@link NonTransientAiException}）；
 *   <li>上下文溢出与额度类文案（先看文本：402 与 403 都能是额度问题）；
 *   <li>OpenAI 家族的状态码与错误码；
 *   <li>JSON 解析失败与 JDK 网络异常。
 * </ol>
 *
 * <p><b>不搬它那套正则库</b>（`message-patterns` / `provider-patterns` / `context-overflow-tables`）：
 * 那是为几十家 provider 的错误文案准备的，我们只有 DeepSeek 与 OpenAI 兼容两个 SDK，
 * 只需要认它们真会给的那几条。
 *
 * <p>沿 {@code getCause()} 走完整条链：Spring AI 与厂商 SDK 都会把底层异常包一层。
 * 认不出来的一律 {@link FailoverReason#unclassified}——不猜。
 */
public final class FailoverClassifier {

    /** 上下文溢出的文案。DeepSeek 与 OpenAI 兼容端点的说法都在这里。 */
    private static final String[] CONTEXT_OVERFLOW_HINTS = {
            "maximum context length", "context length", "context_length_exceeded",
            "reduce the length", "too many tokens", "prompt is too long"};

    /** 额度类文案：403 带这些词时是欠费，不是没权限。 */
    private static final String[] BILLING_HINTS = {
            "insufficient", "quota", "balance", "billing", "arrears", "余额", "欠费", "额度"};

    private FailoverClassifier() {
    }

    /**
     * 分类。不抛异常、不返回 null。
     *
     * @param failure 捕获到的异常，不能为 null
     * @return 原因；认不出来是 {@link FailoverReason#unclassified}
     */
    public static FailoverReason classify(Throwable failure) {
        if (failure == null) {
            return FailoverReason.unclassified;
        }
        FailoverReason best = FailoverReason.unclassified;
        // 文案要沿着整条链看：状态码在外层，真正的原因说明常常在内层（"insufficient quota" 在 cause 里）。
        String text = textOf(failure);
        for (Throwable current = failure; current != null; current = next(current)) {
            FailoverReason reason = classifySingle(current, text);
            if (reason != FailoverReason.unclassified) {
                // 外层先命中就用外层：Spring AI 的包装比 SDK 的底层更了解语义。
                return reason;
            }
            if (best == FailoverReason.unclassified) {
                best = reason;
            }
        }
        return best;
    }

    private static FailoverReason classifySingle(Throwable failure, String text) {
        // 一、Spring AI 自己的两类
        if (failure instanceof NonTransientAiException) {
            return FailoverReason.auth;
        }
        if (failure instanceof TransientAiException) {
            return FailoverReason.rate_limit;
        }
        // 二、文本：上下文溢出与额度
        if (containsAny(text, CONTEXT_OVERFLOW_HINTS)) {
            return FailoverReason.context_overflow;
        }
        // 三、状态码与错误码
        if (failure instanceof OpenAIServiceException service) {
            return byStatus(service.statusCode(), text);
        }
        if (failure instanceof OpenAIRetryableException) {
            return FailoverReason.rate_limit;
        }
        // 四、解析失败与网络
        if (failure instanceof JacksonException || failure instanceof JsonProcessingException) {
            return FailoverReason.format;
        }
        if (containsAny(text, BILLING_HINTS)) {
            return FailoverReason.billing;
        }
        return byNetwork(failure);
    }

    private static FailoverReason byStatus(int status, String message) {
        return switch (status) {
            case 401 -> FailoverReason.auth;
            case 402 -> FailoverReason.billing;
            case 403 -> containsAny(message, BILLING_HINTS) ? FailoverReason.billing : FailoverReason.auth;
            case 404 -> FailoverReason.model_not_found;
            case 408 -> FailoverReason.timeout;
            case 429 -> FailoverReason.rate_limit;
            case 503 -> FailoverReason.overloaded;
            default -> status >= 500 ? FailoverReason.server_error : FailoverReason.unclassified;
        };
    }

    private static FailoverReason byNetwork(Throwable failure) {
        if (failure instanceof SSLException) {
            return FailoverReason.tls_certificate;
        }
        if (failure instanceof HttpTimeoutException || failure instanceof TimeoutException) {
            return FailoverReason.timeout;
        }
        if (failure instanceof ConnectException || failure instanceof UnknownHostException) {
            return FailoverReason.route_unavailable;
        }
        if (failure instanceof ResourceAccessException) {
            return FailoverReason.route_unavailable;
        }
        if (failure instanceof IOException) {
            return FailoverReason.route_unavailable;
        }
        return FailoverReason.unclassified;
    }

    /** 整条链的文案拼在一起，统一小写：状态码在外层、原因说明在内层是常态。 */
    private static String textOf(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = next(current)) {
            if (current.getMessage() != null) {
                text.append(' ').append(current.getMessage());
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String message, String[] hints) {
        for (String hint : hints) {
            if (message.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable next(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == failure ? null : cause;
    }
}
