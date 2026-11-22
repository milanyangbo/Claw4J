package io.github.claw4j.model.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败分类：17 项词表，一条判据。
 *
 * <p>守的是"冷却、闸、指标拿到的原因是同一个"。三处各自判一遍的话，
 * "限流冷却 2 秒"与"指标记成限流"迟早对不上，而那种不一致在事故里最难查。
 */
class FailoverClassifierTest {

    @Test
    @DisplayName("429 与 5xx 分得开：限流、过载、服务端错误是三种不同的退避")
    void rateLimitAndServerErrorAreDistinct() {
        assertThat(classify(status(429, "rate limited"))).isEqualTo(FailoverReason.rate_limit);
        assertThat(classify(status(503, "service unavailable"))).isEqualTo(FailoverReason.overloaded);
        assertThat(classify(status(500, "internal error"))).isEqualTo(FailoverReason.server_error);
        assertThat(classify(status(502, "bad gateway"))).isEqualTo(FailoverReason.server_error);
    }

    @Test
    @DisplayName("401 是 auth，额度类 403 是 billing：一刀切会让欠费按鉴权处理")
    void authAndBillingAreDistinct() {
        assertThat(classify(status(401, "invalid api key"))).isEqualTo(FailoverReason.auth);
        assertThat(classify(status(403, "insufficient quota, please recharge")))
                .isEqualTo(FailoverReason.billing);
        assertThat(classify(status(403, "forbidden"))).isEqualTo(FailoverReason.auth);
        assertThat(classify(status(402, "balance not enough"))).isEqualTo(FailoverReason.billing);
    }

    @Test
    @DisplayName("Spring AI 的 NonTransientAiException 不都是 auth：4xx 按消息里的状态码与文案细分")
    void springAiNonTransientIsNotAlwaysAuth() {
        assertThat(classify(new NonTransientAiException(
                "400 - {\"error\":{\"message\":\"The supported API model names are deepseek-chat, "
                        + "but you passed no-such-model\"}}")))
                .as("模型名写错被当成 auth 的代价是换家也不换——一次配置笔误让整条候选链停摆")
                .isEqualTo(FailoverReason.model_not_found);
        assertThat(classify(new NonTransientAiException("401 - unauthorized")))
                .isEqualTo(FailoverReason.auth);
        assertThat(classify(new NonTransientAiException("400 - maximum context length is 65536 tokens")))
                .isEqualTo(FailoverReason.context_overflow);
        assertThat(classify(new TransientAiException("503 - service unavailable")))
                .isEqualTo(FailoverReason.overloaded);
    }

    @Test
    @DisplayName("工具参数坏 JSON 归 format（两个 Jackson 包名都认）")
    void unparseableArgumentsAreFormat() {
        assertThat(classify(new IllegalStateException("Conversion from JSON to java.util.Map failed",
                new tools.jackson.core.exc.StreamReadException("Unexpected character ('专')"))))
                .isEqualTo(FailoverReason.format);
        assertThat(classify(new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected end-of-input")))
                .as("OpenAI SDK 自己解析响应体失败时抛的是 Jackson 2 那一份")
                .isEqualTo(FailoverReason.format);
    }

    @Test
    @DisplayName("认不出来的一律 unclassified，不猜、不抛")
    void unknownFailureIsUnclassified() {
        assertThat(classify(new IllegalArgumentException("空消息"))).isEqualTo(FailoverReason.unclassified);
        assertThat(classify(new IllegalStateException("模型返回了不该有的东西")))
                .isEqualTo(FailoverReason.unclassified);
        assertThat(FailoverClassifier.classify(null))
                .as("没有异常也不能炸：调用方可能拿到 null")
                .isEqualTo(FailoverReason.unclassified);
    }

    @Test
    @DisplayName("网络、TLS、上下文溢出、模型不存在各有归宿")
    void networkAndOverflowReasons() {
        assertThat(classify(new HttpTimeoutException("读超时"))).isEqualTo(FailoverReason.timeout);
        assertThat(classify(new ConnectException("连接被拒"))).isEqualTo(FailoverReason.route_unavailable);
        assertThat(classify(new SSLHandshakeException("证书不对"))).isEqualTo(FailoverReason.tls_certificate);
        assertThat(classify(new TransientAiException("provider 说太忙"))).isEqualTo(FailoverReason.rate_limit);
        assertThat(classify(new NonTransientAiException("key 无效"))).isEqualTo(FailoverReason.auth);
        assertThat(classify(new IllegalArgumentException(
                "This model's maximum context length is 65536 tokens"))).isEqualTo(FailoverReason.context_overflow);
        assertThat(classify(status(404, "model not found"))).isEqualTo(FailoverReason.model_not_found);
    }

    @Test
    @DisplayName("17 项每一项都能被分类器产出，或被显式标注为无产生方")
    void everyReasonHasProducerOrGap() {
        Map<Throwable, FailoverReason> samples = new java.util.LinkedHashMap<>();
        samples.put(status(401, "invalid api key"), FailoverReason.auth);
        samples.put(status(429, "rate limited"), FailoverReason.rate_limit);
        samples.put(status(503, "overloaded"), FailoverReason.overloaded);
        samples.put(status(500, "boom"), FailoverReason.server_error);
        samples.put(status(404, "nope"), FailoverReason.model_not_found);
        samples.put(new tools.jackson.core.exc.StreamReadException("bad"), FailoverReason.format);
        samples.put(new HttpTimeoutException("slow"), FailoverReason.timeout);
        samples.put(new SSLHandshakeException("cert"), FailoverReason.tls_certificate);
        samples.put(new ConnectException("refused"), FailoverReason.route_unavailable);
        samples.put(new IllegalArgumentException("maximum context length is 8192"),
                FailoverReason.context_overflow);
        samples.put(new IllegalArgumentException("空消息"), FailoverReason.unclassified);
        samples.put(status(403, "insufficient quota"), FailoverReason.billing);

        Set<FailoverReason> produced = EnumSet.noneOf(FailoverReason.class);
        samples.forEach((failure, expected) -> {
            FailoverReason actual = classify(failure);
            assertThat(actual).as("%s 应归 %s", failure, expected).isEqualTo(expected);
            produced.add(actual);
        });

        Set<FailoverReason> uncovered = EnumSet.allOf(FailoverReason.class);
        uncovered.removeAll(produced);
        uncovered.removeAll(FailoverReason.PRODUCER_GAPS);

        assertThat(uncovered)
                .as("这些原因既产不出来、也没写进 PRODUCER_GAPS：词表里躺着没人用的项")
                .isEmpty();
    }

    @Test
    @DisplayName("两张动作表：鉴权与格式不换家，限流与溢出换")
    void actionSetsMatchOpenClaw() {
        assertThat(FailoverReason.auth.worthTryingAnother()).isFalse();
        assertThat(FailoverReason.format.worthTryingAnother()).isFalse();
        assertThat(FailoverReason.rate_limit.worthTryingAnother()).isTrue();
        assertThat(FailoverReason.context_overflow.worthTryingAnother()).isTrue();
        assertThat(FailoverReason.TRANSIENT_SLOT)
                .contains(FailoverReason.rate_limit, FailoverReason.timeout, FailoverReason.unclassified)
                .doesNotContain(FailoverReason.auth, FailoverReason.format);
        assertThat(FailoverReason.KEEPS_PROBE_BUDGET)
                .contains(FailoverReason.auth, FailoverReason.format, FailoverReason.model_not_found)
                .doesNotContain(FailoverReason.rate_limit);
    }

    private static FailoverReason classify(Throwable failure) {
        return FailoverClassifier.classify(failure);
    }

    private static com.openai.errors.OpenAIServiceException status(int statusCode, String message) {
        return com.openai.errors.UnexpectedStatusCodeException.builder()
                .statusCode(statusCode)
                .headers(com.openai.core.http.Headers.builder().build())
                .cause(new IllegalStateException(message))
                .build();
    }
}
