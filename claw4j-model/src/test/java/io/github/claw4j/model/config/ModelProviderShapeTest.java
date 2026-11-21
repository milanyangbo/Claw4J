package io.github.claw4j.model.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * provider 的形状：id 为键的表、{@code api} 选适配器、{@code auth} 与多把钥匙。
 *
 * <p>守的是"加一家供应商只改配置"这句话。它最容易在两种地方失效：又长回"每家一个 Java bean"，
 * 或者配置写错时静默跳过那一家——前者表现为代码里多一处要改的地方，后者表现为
 * "我明明配了它，为什么没在链上"。
 */
class ModelProviderShapeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelConfiguration.class,
                    RoutingModelConfiguration.class));

    @Test
    @DisplayName("id 就是键：绑出来按 id 取得到，大小写归一")
    void providersBindById() {
        runner.withPropertyValues(
                        "claw4j.model.providers.DeepSeek.api=deepseek",
                        "claw4j.model.providers.DeepSeek.base-url=http://127.0.0.1:9",
                        "claw4j.model.providers.DeepSeek.api-keys[0]=secret:deepseek_api_key",
                        "claw4j.model.providers.DeepSeek.models[0].id=deepseek-chat",
                        "claw4j.model.providers.DeepSeek.models[0].context-window=65536",
                        "DEEPSEEK_API_KEY=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelProperties properties = context.getBean(ModelProperties.class);
                    assertThat(properties.names()).containsExactly("deepseek");
                    assertThat(properties.require("deepseek").getModels().getFirst().getId())
                            .isEqualTo("deepseek-chat");
                    assertThat(properties.require("deepseek").getApi())
                            .isEqualTo(ModelProperties.API_DEEPSEEK);
                });
    }

    @Test
    @DisplayName("api 不认识就在启动时拒掉，并列出可选值")
    void unknownApiFailsAtStartup() {
        runner.withPropertyValues(
                        "claw4j.model.providers.mine.api=bogus-protocol",
                        "claw4j.model.providers.mine.base-url=http://127.0.0.1:9",
                        "claw4j.model.providers.mine.api-keys[0]=secret:mine_api_key",
                        "claw4j.model.providers.mine.models[0].id=m",
                        "claw4j.model.providers.mine.models[0].context-window=1024",
                        "MINE_API_KEY=test-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("bogus-protocol")
                            .hasMessageContaining(ModelProperties.API_OPENAI_COMPLETIONS);
                });
    }

    @Test
    @DisplayName("auth: none：没有钥匙也能起，走 OpenAI 兼容端点")
    void authNoneSkipsCredential() {
        runner.withPropertyValues(
                        "claw4j.model.primary=local",
                        "claw4j.model.providers.local.api=openai-completions",
                        "claw4j.model.providers.local.auth=none",
                        "claw4j.model.providers.local.base-url=http://127.0.0.1:11434/v1",
                        "claw4j.model.providers.local.models[0].id=qwen3",
                        "claw4j.model.providers.local.models[0].context-window=8192")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModelConfiguration.ProviderModels.class)
                            .byRef()).containsKey(
                            new io.github.claw4j.model.catalog.ModelRef("local", "qwen3"));
                    assertThat(context.getBean(RoutingModelConfiguration.ROUTING_BEAN,
                            io.github.claw4j.model.routing.RoutingChatModel.class)
                            .routeOrder()).containsExactly("local/qwen3");
                });
    }

    @Test
    @DisplayName("没写 auth: none 又没给钥匙：启动失败并点名是哪一家")
    void missingKeyStillFailsWithoutAuthNone() {
        runner.withPropertyValues(
                        "claw4j.model.primary=nokey",
                        "claw4j.model.providers.nokey.api=openai-completions",
                        "claw4j.model.providers.nokey.base-url=http://127.0.0.1:9/v1",
                        "claw4j.model.providers.nokey.api-keys[0]=secret:nokey_api_key",
                        "claw4j.model.providers.nokey.models[0].id=m",
                        "claw4j.model.providers.nokey.models[0].context-window=1024")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("NOKEY_API_KEY");
                });
    }

    @Test
    @DisplayName("加一家只改配置：两家都进候选链，主在前")
    void newProviderNeedsNoJavaChange() {
        runner.withPropertyValues(
                        "claw4j.model.primary=second",
                        "claw4j.model.providers.first.api=openai-completions",
                        "claw4j.model.providers.first.base-url=http://127.0.0.1:9/v1",
                        "claw4j.model.providers.first.api-keys[0]=secret:first_api_key",
                        "claw4j.model.providers.first.models[0].id=a",
                        "claw4j.model.providers.first.models[0].context-window=1024",
                        "claw4j.model.providers.second.api=openai-completions",
                        "claw4j.model.providers.second.base-url=http://127.0.0.1:9/v1",
                        "claw4j.model.providers.second.api-keys[0]=secret:second_api_key",
                        "claw4j.model.providers.second.models[0].id=b",
                        "claw4j.model.providers.second.models[0].context-window=1024",
                        "FIRST_API_KEY=k1",
                        "SECOND_API_KEY=k2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RoutingModelConfiguration.ROUTING_BEAN,
                            io.github.claw4j.model.routing.RoutingChatModel.class)
                            .routeOrder())
                            .as("primary 排第一，其余按配置顺序")
                            .containsExactly("second/b", "first/a");
                });
    }

    @Test
    @DisplayName("旧的列表写法（providers[0]）启动即拒，并指出改成什么")
    void legacyListShapeIsRejected() {
        runner.withPropertyValues(
                        "claw4j.model.providers[0].api=deepseek",
                        "claw4j.model.providers[0].models[0].id=deepseek-chat",
                        "claw4j.model.providers[0].api-key=secret:deepseek_api_key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("旧的列表写法")
                            .hasMessageContaining("providers.deepseek");
                });
    }
}
