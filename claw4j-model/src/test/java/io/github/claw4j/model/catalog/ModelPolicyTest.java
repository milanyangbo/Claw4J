package io.github.claw4j.model.catalog;

import io.github.claw4j.model.config.ModelConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 白名单：三种写法、只认尾部 *、显式选择被排除就启动失败。
 *
 * <p>最典型的失效方式不是报错，是"配了却全放行"——写错的模式被静默忽略，而读配置的人以为它生效了。
 */
class ModelPolicyTest {

    private static final ModelRef DEEPSEEK_CHAT = new ModelRef("deepseek", "deepseek-chat");
    private static final ModelRef QWEN_PLUS = new ModelRef("qwen", "qwen-plus");
    private static final ModelRef QWEN_LLAMA = new ModelRef("qwen", "meta-llama/Llama-3");
    private static final ModelRef QWEN_TURBO = new ModelRef("qwen", "qwen-turbo");

    @Test
    @DisplayName("不配或空列表时全部放行")
    void emptyAllowListAllowsEverything() {
        assertThat(ModelPolicy.of(null).allows(DEEPSEEK_CHAT)).isTrue();
        assertThat(ModelPolicy.of(List.of()).allows(QWEN_PLUS)).isTrue();
        assertThat(ModelPolicy.of(List.of()).patterns()).isEmpty();
    }

    @Test
    @DisplayName("精确 ref 只放行那一个")
    void exactRefAllowsOnlyThatModel() {
        ModelPolicy policy = ModelPolicy.of(List.of("deepseek/deepseek-chat"));

        assertThat(policy.allows(DEEPSEEK_CHAT)).isTrue();
        assertThat(policy.allows(QWEN_PLUS)).isFalse();
        assertThat(policy.allows(new ModelRef("deepseek", "deepseek-reasoner"))).isFalse();
    }

    @Test
    @DisplayName("provider/* 放行该家全部模型")
    void providerWildcardAllowsFamily() {
        ModelPolicy policy = ModelPolicy.of(List.of("qwen/*"));

        assertThat(policy.allows(QWEN_PLUS)).isTrue();
        assertThat(policy.allows(QWEN_LLAMA)).as("模型名里带斜杠也算这一家").isTrue();
        assertThat(policy.allows(DEEPSEEK_CHAT)).isFalse();
    }

    @Test
    @DisplayName("provider/family-* 按模型名前缀匹配")
    void modelPrefixWildcardMatchesByName() {
        ModelPolicy policy = ModelPolicy.of(List.of("qwen/qwen-*"));

        assertThat(policy.allows(QWEN_PLUS)).isTrue();
        assertThat(policy.allows(QWEN_TURBO)).isTrue();
        assertThat(policy.allows(QWEN_LLAMA)).isFalse();
    }

    @Test
    @DisplayName("中间通配启动即抛：它要么是正则、要么要靠猜")
    void midWildcardIsRejected() {
        assertThatThrownBy(() -> ModelPolicy.of(List.of("prov*/x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("*");
        assertThatThrownBy(() -> ModelPolicy.of(List.of("qwen/qw*n-plus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("末尾");
        assertThatThrownBy(() -> ModelPolicy.of(List.of("qwen")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("斜杠");
    }

    @Test
    @DisplayName("显式 primary 被排除：启动失败并列出允许集")
    void excludedPrimaryFailsAtStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModelConfiguration.class))
                .withPropertyValues(
                        "DEEPSEEK_API_KEY=k1",
                        "claw4j.model.primary=deepseek/deepseek-chat",
                        "claw4j.model.policy.allow[0]=qwen/*",
                        "claw4j.model.providers.deepseek.api=deepseek",
                        "claw4j.model.providers.deepseek.base-url=http://127.0.0.1:9",
                        "claw4j.model.providers.deepseek.api-keys[0]=secret:deepseek_api_key",
                        "claw4j.model.providers.deepseek.models[0].id=deepseek-chat",
                        "claw4j.model.providers.deepseek.models[0].context-window=65536")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("deepseek/deepseek-chat")
                            .hasMessageContaining("qwen/*");
                });
    }

    @Test
    @DisplayName("fallbacks 里被排除的模型仍可被调用：显式意图不受白名单收窄")
    void fallbacksIgnorePolicy() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModelConfiguration.class))
                .withPropertyValues(
                        "DEEPSEEK_API_KEY=k1",
                        "QWEN_API_KEY=k2",
                        "claw4j.model.primary=deepseek/deepseek-chat",
                        "claw4j.model.policy.allow[0]=deepseek/*",
                        "claw4j.model.providers.deepseek.api=deepseek",
                        "claw4j.model.providers.deepseek.base-url=http://127.0.0.1:9",
                        "claw4j.model.providers.deepseek.api-keys[0]=secret:deepseek_api_key",
                        "claw4j.model.providers.deepseek.models[0].id=deepseek-chat",
                        "claw4j.model.providers.deepseek.models[0].context-window=65536",
                        "claw4j.model.providers.qwen.api=openai-completions",
                        "claw4j.model.providers.qwen.base-url=http://127.0.0.1:9/v1",
                        "claw4j.model.providers.qwen.api-keys[0]=secret:qwen_api_key",
                        "claw4j.model.providers.qwen.models[0].id=qwen-plus",
                        "claw4j.model.providers.qwen.models[0].context-window=131072")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModelCatalog.class).refs())
                            .as("目录不按白名单裁剪：备选是显式意图，白名单管的是「能被选中」")
                            .contains(QWEN_PLUS);
                });
    }
}
