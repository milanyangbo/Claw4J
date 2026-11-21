package io.github.claw4j.channel.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批回调的请求体只靠注解绑定：迁到 Jackson 3 之后它还得照旧。
 *
 * <p>这条守的不是"JSON 能解析"，而是两件容易在换库时静默失效的事：
 * <ul>
 *   <li>{@code @JsonCreator} / {@code @JsonProperty} 在 Jackson 3 下仍然生效——注解包仍是
 *       {@code com.fasterxml.jackson.annotation}（databind 3.x 依赖它那一份），
 *       所以这里一行都没改，也正因为没改才需要证明它还认；
 *   <li>只传 {@code {"approved": true}} 时，缺的 {@code always} 与 {@code reason} 落到默认值上，
 *       而不是 400。少了这条，调用方传一份完全合法的简化报文会被判成参数错。
 * </ul>
 */
class ApprovalRequestBodyTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("只写 approved：另外两个字段落到默认值，不是参数错")
    void minimalBodyBindsToDefaults() {
        ApprovalController.DecisionRequest request =
                JSON.readValue("{\"approved\": true}", ApprovalController.DecisionRequest.class);

        assertThat(request.approved()).isTrue();
        assertThat(request.always()).isFalse();
        assertThat(request.reason()).isNull();
    }

    @Test
    @DisplayName("三个字段齐全：snake 无关的字段名逐个对上")
    void fullBodyKeepsEveryField() {
        ApprovalController.DecisionRequest request = JSON.readValue(
                "{\"approved\": false, \"always\": true, \"reason\": \"这个客户不能发\"}",
                ApprovalController.DecisionRequest.class);

        assertThat(request.approved()).isFalse();
        assertThat(request.always()).isTrue();
        assertThat(request.reason()).isEqualTo("这个客户不能发");
    }
}
