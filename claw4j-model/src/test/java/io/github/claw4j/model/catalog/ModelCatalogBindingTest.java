package io.github.claw4j.model.catalog;

import io.github.claw4j.model.config.ModelConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录：模型行带着元数据，引用写 {@code provider/model}。
 *
 * <p>守的是"窗口跟着模型走"（ADR-0035）。它最典型的失效方式不是报错，是降级之后仍然按主模型的
 * 窗口去压缩——压缩得太晚，请求被供应商截断，而日志里一切正常。
 */
class ModelCatalogBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelConfiguration.class))
            .withPropertyValues(
                    "DEEPSEEK_API_KEY=k1",
                    "QWEN_API_KEY=k2",
                    "claw4j.model.providers.deepseek.api=deepseek",
                    "claw4j.model.providers.deepseek.base-url=http://127.0.0.1:9",
                    "claw4j.model.providers.deepseek.api-keys[0]=secret:deepseek_api_key",
                    "claw4j.model.providers.deepseek.models[0].id=deepseek-chat",
                    "claw4j.model.providers.deepseek.models[0].context-window=65536",
                    "claw4j.model.providers.deepseek.models[0].cost.input=0.27",
                    "claw4j.model.providers.deepseek.models[0].cost.output=1.10",
                    "claw4j.model.providers.deepseek.models[0].cost.cache-read=0.07",
                    "claw4j.model.providers.qwen.api=openai-completions",
                    "claw4j.model.providers.qwen.base-url=http://127.0.0.1:9/v1",
                    "claw4j.model.providers.qwen.api-keys[0]=secret:qwen_api_key",
                    "claw4j.model.providers.qwen.models[0].id=qwen-plus",
                    "claw4j.model.providers.qwen.models[0].context-window=131072",
                    "claw4j.model.providers.qwen.models[0].context-tokens=120000",
                    "claw4j.model.providers.qwen.models[1].id=qwen-turbo",
                    "claw4j.model.providers.qwen.models[1].context-window=8192");

    @Test
    @DisplayName("一家能读多张模型行：摊平成 provider/model → 元数据，顺序按配置")
    void modelsAreReadById() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelCatalog.class).refs())
                    .containsExactly(
                            new ModelRef("deepseek", "deepseek-chat"),
                            new ModelRef("qwen", "qwen-plus"),
                            new ModelRef("qwen", "qwen-turbo"));
        });
    }

    @Test
    @DisplayName("引用指向目录里没有的模型：启动失败并列出可选")
    void unknownModelRefFailsAtStartup() {
        runner.withPropertyValues("claw4j.model.primary=deepseek/deepseek-reasoner")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("deepseek/deepseek-reasoner")
                            .hasMessageContaining("deepseek/deepseek-chat");
                });
    }

    @Test
    @DisplayName("压缩阈值来自目录行：主模型那一行说多少就是多少")
    void contextWindowComesFromCatalogRow() {
        runner.run(context -> {
            ModelCatalog catalog = context.getBean(ModelCatalog.class);
            assertThat(catalog.primary()).isEqualTo(new ModelRef("deepseek", "deepseek-chat"));
            assertThat(catalog.primaryRow().getContextWindow()).isEqualTo(65536);
        });
    }

    @Test
    @DisplayName("没写 context-tokens 时等于 context-window；写了就用写的那个")
    void contextTokensDefaultsToWindow() {
        runner.run(context -> {
            ModelCatalog catalog = context.getBean(ModelCatalog.class);
            assertThat(catalog.require(new ModelRef("deepseek", "deepseek-chat")).effectiveContextTokens())
                    .as("没写就取总窗口")
                    .isEqualTo(65536);
            assertThat(catalog.require(new ModelRef("qwen", "qwen-plus")).effectiveContextTokens())
                    .as("写了有效容量就用它：总窗口里那段留给输出的不算")
                    .isEqualTo(120000);
        });
    }

    @Test
    @DisplayName("cost 三档解析得出来，但没有任何代码消费它")
    void costIsParsedButUnused() {
        runner.run(context -> {
            ModelRow row = context.getBean(ModelCatalog.class)
                    .require(new ModelRef("deepseek", "deepseek-chat"));
            assertThat(row.getCost().getInput()).isEqualTo(0.27);
            assertThat(row.getCost().getOutput()).isEqualTo(1.10);
            assertThat(row.getCost().getCacheRead()).isEqualTo(0.07);
            assertThat(row.getCost().getCacheWrite())
                    .as("没配的那一档就是 null，不拿 0 冒充")
                    .isNull();
        });
    }

    @Test
    @DisplayName("只写供应商时取该家第一行：常见部署少写一遍模型名")
    void providerOnlyPrimaryTakesFirstRow() {
        runner.withPropertyValues("claw4j.model.primary=qwen")
                .run(context -> assertThat(context.getBean(ModelCatalog.class).primary())
                        .isEqualTo(new ModelRef("qwen", "qwen-plus")));
    }
}
