package io.github.claw4j.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词项规则的配置绑定。
 *
 * <p>单测 {@code KeywordDocumentRetrieverTest} 验的是"给了这组规则会怎么切"，
 * 这里验的是"配置里的那几行能不能真的走到规则里去"。两件事分开测，是因为它们错起来不一样：
 * 切错了会让检索变差，绑定错了会让配置悄无声息地不生效——后者在结果上表现为"改了配置没反应"，
 * 而没有人会怀疑是绑定。
 *
 * <p>八项挂在 {@code keyword.substring} 下面，与 {@code engine} 分开：切到 bm25 之后那些规则
 * 一个字都不生效，平铺会让人以为还能调。
 */
class RagPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindOnly.class);

    @EnableConfigurationProperties(RagProperties.class)
    static class BindOnly {
    }

    @Test
    @DisplayName("八项都能绑上，没配的项回落到默认值")
    void bindsConfiguredKeysAndFallsBack() {
        runner.withPropertyValues(
                        "claw4j.rag.keyword.substring"
                                + ".short-model-pattern=(?<![A-Za-z0-9])[A-Za-z]{2}-\\d{2}(?![A-Za-z0-9])",
                        "claw4j.rag.keyword.substring.max-anchors=6",
                        "claw4j.rag.keyword.substring.whole-weight=5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RagProperties.Keyword.Substring substring =
                            context.getBean(RagProperties.class).keyword().substring();

                    assertThat(substring.shortModelPattern()).contains("{2}");
                    assertThat(substring.maxAnchors()).isEqualTo(6);
                    assertThat(substring.wholeWeight()).isEqualTo(5);
                    assertThat(substring.bigramWeight())
                            .as("没配的项要用默认值，不能被绑成 0")
                            .isEqualTo(RagProperties.Keyword.Substring.DEFAULT_BIGRAM_WEIGHT);
                    assertThat(substring.anchorPattern())
                            .isEqualTo(RagProperties.Keyword.Substring.DEFAULT_ANCHOR_PATTERN);
                });
    }

    @Test
    @DisplayName("引擎能绑上：配 bm25 就走 BM25，不配就是 substring")
    void bindsEngine() {
        runner.withPropertyValues("claw4j.rag.keyword.engine=bm25")
                .run(context -> {
                    RagProperties.Keyword keyword = context.getBean(RagProperties.class).keyword();

                    assertThat(keyword.engine()).isEqualTo(RagProperties.Keyword.ENGINE_BM25);
                    assertThat(keyword.isBm25()).isTrue();
                });

        runner.run(context -> assertThat(context.getBean(RagProperties.class).keyword().isBm25())
                .as("一行不配时还是 substring：升级后行为不变")
                .isFalse());
    }

    @Test
    @DisplayName("引擎拼错：启动失败，不是静默回落")
    void rejectsUnknownEngineAtStartup() {
        runner.withPropertyValues("claw4j.rag.keyword.engine=bm2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("claw4j.rag.keyword.engine 只能是 substring 或 bm25，收到：bm2");
                });
    }

    @Test
    @DisplayName("一行都不配：八项全是默认值，与配置化之前写死在代码里的那套一致")
    void emptyConfigKeepsTheOldBehaviour() {
        runner.run(context -> {
            RagProperties.Keyword.Substring substring =
                    context.getBean(RagProperties.class).keyword().substring();

            assertThat(substring.anchorPattern())
                    .isEqualTo(RagProperties.Keyword.Substring.DEFAULT_ANCHOR_PATTERN);
            assertThat(substring.shortModelPattern())
                    .isEqualTo(RagProperties.Keyword.Substring.DEFAULT_SHORT_MODEL_PATTERN);
            assertThat(substring.maxAnchors()).isEqualTo(4);
            assertThat(substring.maxTerms()).isEqualTo(12);
            assertThat(substring.wholeMaxChars()).isEqualTo(12);
            assertThat(substring.wholeWeight()).isEqualTo(3);
            assertThat(substring.bigramWeight()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("旧的平铺键不再生效，而且是静默的：这条钉的是已知风险，不是想要的行为")
    void legacyFlatKeysAreIgnored() {
        runner.withPropertyValues("claw4j.rag.keyword.max-anchors=6")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RagProperties.class).keyword().substring().maxAnchors())
                            .as("写到旧位置的值被忽略，回落到 4——升级时这份配置会静默失效。"
                                    + "要改成启动失败，需要给 RagProperties 加 "
                                    + "ignoreUnknownFields=false，那会影响所有 claw4j.rag 键，得单独决定")
                            .isEqualTo(4);
                });
    }
}
