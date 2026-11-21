package io.github.claw4j.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引擎开关的单测：只测"配了什么就是什么"，不连库。
 *
 * <p>这个开关的两种取值对应两套部署前提（换不换镜像），所以拼错绝不能回落成默认值：
 * 静默回落的表现是"改了配置没反应"，而人读到的却是"BM25 没效果"。
 */
class KeywordEngineTest {

    @Test
    @DisplayName("不配就是 substring：现有部署升级后行为不变")
    void defaultsToSubstring() {
        RagProperties.Keyword defaults = RagProperties.Keyword.defaults();

        assertThat(defaults.engine()).isEqualTo(RagProperties.Keyword.ENGINE_SUBSTRING);
        assertThat(defaults.isBm25()).isFalse();
        assertThat(defaults.substring())
                .as("substring 那一节不配也要有默认值：不能是 null，否则切词项时 NPE")
                .isNotNull();
        assertThat(defaults.substring().maxAnchors()).isEqualTo(4);
    }

    @Test
    @DisplayName("配 bm25 就认，isBm25 跟着走")
    void acceptsBm25() {
        RagProperties.Keyword keyword = withEngine("bm25");

        assertThat(keyword.engine()).isEqualTo(RagProperties.Keyword.ENGINE_BM25);
        assertThat(keyword.isBm25()).isTrue();
    }

    @Test
    @DisplayName("大小写与两侧空白不计：配置里写成 BM25 不该变成启动失败")
    void trimsAndLowercases() {
        assertThat(withEngine("  BM25  ").engine()).isEqualTo(RagProperties.Keyword.ENGINE_BM25);
        assertThat(withEngine("SubString").engine()).isEqualTo(RagProperties.Keyword.ENGINE_SUBSTRING);
    }

    @Test
    @DisplayName("拼错启动即抛，消息里列出允许值：不许静默回落")
    void rejectsUnknownEngine() {
        assertThatThrownBy(() -> withEngine("bm2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bm2")
                .hasMessageContaining("substring")
                .hasMessageContaining("bm25");
    }

    @Test
    @DisplayName("空白当作没配：空串不该被当成一个非法引擎")
    void blankMeansDefault() {
        assertThat(withEngine("   ").engine()).isEqualTo(RagProperties.Keyword.ENGINE_SUBSTRING);
        assertThat(withEngine(null).engine()).isEqualTo(RagProperties.Keyword.ENGINE_SUBSTRING);
    }

    /** 只改引擎，substring 那一节传 null（走默认）——这样断言失败时不会混进"正则被我改了"。 */
    private static RagProperties.Keyword withEngine(String engine) {
        return new RagProperties.Keyword(engine, null);
    }
}
