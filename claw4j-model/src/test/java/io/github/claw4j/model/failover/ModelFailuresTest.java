package io.github.claw4j.model.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 哪些失败值得换一家：一条判据、一张表。
 *
 * <p>表放在模型层自己的测试里（类也在这里）。票 06 会把它换成 17 项词表与分类器，
 * 那时候这张表跟着换名字，而不是在 agent 那边留一份影子。
 */
class ModelFailuresTest {

    @Test
    @DisplayName("可重试与不可重试各一张表：这条判据只有一处，加供应商不用重新讨论")
    void retryableClassificationTable() {
        Map<Throwable, Boolean> table = new java.util.LinkedHashMap<>();
        // 换一家可能好
        table.put(statusFailure(429, "限流"), true);
        table.put(statusFailure(500, "服务端错误"), true);
        table.put(statusFailure(503, "服务不可用"), true);
        table.put(new TransientAiException("限流"), true);
        table.put(new java.net.ConnectException("连接被拒"), true);
        table.put(new java.net.http.HttpTimeoutException("读超时"), true);
        table.put(new RuntimeException("被框架包了一层", statusFailure(502, "网关错误")), true);
        // 拿到的字节解析不出来：换一家就是换一份新字节，值得试
        // （两个包名都要认：tools.jackson 是 Spring AI 解析工具参数用的，com.fasterxml.jackson 是
        //   openai-java-core 解析响应体用的，后者会被包进 OpenAIInvalidDataException 抛出来）
        table.put(new tools.jackson.core.exc.StreamReadException("Unexpected character ('专')"), true);
        table.put(new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected end-of-input"), true);
        // 换一家也一样
        table.put(statusFailure(401, "key 无效"), false);
        table.put(statusFailure(403, "无权限"), false);
        table.put(statusFailure(400, "参数错"), false);
        table.put(statusFailure(422, "参数不可处理"), false);
        table.put(new NonTransientAiException("key 无效"), false);
        table.put(new IllegalArgumentException("空消息"), false);
        table.put(new IllegalStateException("模型返回了不该有的东西"), false);
        table.put(new RuntimeException("被框架包了一层", statusFailure(401, "key 无效")), false);

        table.forEach((failure, retryable) -> assertThat(ModelFailures.worthTryingAnother(failure))
                .as("%s 应%s降级", failure, retryable ? "该" : "不该")
                .isEqualTo(retryable));
    }

    private static com.openai.errors.OpenAIServiceException statusFailure(int statusCode, String message) {
        return com.openai.errors.UnexpectedStatusCodeException.builder()
                .statusCode(statusCode)
                .headers(com.openai.core.http.Headers.builder().build())
                .cause(new IllegalStateException(message))
                .build();
    }
}
