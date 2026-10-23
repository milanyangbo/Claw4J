package io.github.claw4j.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话 schema 是拼进 URL 的，拼错的表现是"表悄悄落到 public"——不报错，
 * 只是 ADR-0005 那条"业务与会话不同 schema"悄悄不成立。所以这里把边界钉住。
 */
class SessionSchemaTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/claw4j";

    @Test
    @DisplayName("URL 上没有参数时用 ? 起头")
    void 没有参数时() {
        assertThat(SessionSchema.urlWithSchema(URL, "claw4j_session"))
                .isEqualTo(URL + "?currentSchema=claw4j_session");
    }

    @Test
    @DisplayName("URL 上已有参数时用 & 拼接，不覆盖原有参数")
    void 已有参数时() {
        assertThat(SessionSchema.urlWithSchema(URL + "?ssl=false", "claw4j_session"))
                .isEqualTo(URL + "?ssl=false&currentSchema=claw4j_session");
    }

    @Test
    @DisplayName("schema 名带空白会被先 trim，不会拼出半个参数")
    void 带空白时先修剪() {
        assertThat(SessionSchema.urlWithSchema(URL, "  claw4j_session  "))
                .isEqualTo(URL + "?currentSchema=claw4j_session");
    }

    /**
     * 空 schema 是最该拦的一条：拼出来的 URL 是 {@code currentSchema=}，
     * 驱动不报错，会话表落到默认 schema，而"同库不同 schema"这件事没有任何人会发现。
     */
    @Test
    @DisplayName("空 schema 直接拒绝：它不报错，只会让会话表悄悄落到 public")
    void 空schema被拒() {
        assertThatThrownBy(() -> SessionSchema.urlWithSchema(URL, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public");
    }
}
