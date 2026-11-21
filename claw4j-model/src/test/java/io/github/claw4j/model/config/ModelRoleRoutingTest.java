package io.github.claw4j.model.config;

import io.github.claw4j.model.catalog.ModelRole;
import io.github.claw4j.model.routing.ModelRoles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色：utility 的四态与 main 的默认注入。
 *
 * <p>最容易写错的是"配了空串"：它表示<b>关闭</b>，不是继承。静默继承会让"我明明关了"与
 * "它还在花钱"同时成立，而且没有任何日志。
 */
class ModelRoleRoutingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelConfiguration.class,
                    RoutingModelConfiguration.class))
            .withPropertyValues(
                    "DEEPSEEK_API_KEY=k1",
                    "QWEN_API_KEY=k2",
                    "claw4j.model.primary=deepseek/deepseek-chat",
                    "claw4j.model.providers.deepseek.api=deepseek",
                    "claw4j.model.providers.deepseek.base-url=http://127.0.0.1:9",
                    "claw4j.model.providers.deepseek.api-keys[0]=secret:deepseek_api_key",
                    "claw4j.model.providers.deepseek.models[0].id=deepseek-chat",
                    "claw4j.model.providers.deepseek.models[0].context-window=65536",
                    "claw4j.model.providers.qwen.api=openai-completions",
                    "claw4j.model.providers.qwen.base-url=http://127.0.0.1:9/v1",
                    "claw4j.model.providers.qwen.api-keys[0]=secret:qwen_api_key",
                    "claw4j.model.providers.qwen.models[0].id=qwen-plus",
                    "claw4j.model.providers.qwen.models[0].context-window=131072",
                    "claw4j.model.providers.qwen.models[0].cost.input=0.4",
                    "claw4j.model.providers.qwen.models[1].id=qwen-turbo",
                    "claw4j.model.providers.qwen.models[1].context-window=8192",
                    "claw4j.model.providers.qwen.models[1].cost.input=0.1");

    @Test
    @DisplayName("utility 配了就用自己的链")
    void utilityUsesItsOwnChain() {
        runner.withPropertyValues("claw4j.model.roles.utility.primary=qwen/qwen-plus")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatModel utility = context.getBean(ModelRoles.class).forRole(ModelRole.utility);
                    ChatModel main = context.getBean(ModelRoles.class).forRole(ModelRole.main);
                    assertThat(utility).isNotSameAs(main);
                });
    }

    @Test
    @DisplayName("utility 配空串视为关闭，不回落 main")
    void emptyUtilityDisablesRoute() {
        runner.withPropertyValues("claw4j.model.roles.utility.primary=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelRoles roles = context.getBean(ModelRoles.class);
                    assertThat(roles.enabled(ModelRole.utility)).isFalse();
                    assertThatThrownBy(() -> roles.forRole(ModelRole.utility)
                            .call(new org.springframework.ai.chat.prompt.Prompt("压缩这段")))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("关闭");
                });
    }

    @Test
    @DisplayName("utility 完全没配则继承 main")
    void unsetUtilityInheritsMain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ModelRoles roles = context.getBean(ModelRoles.class);
            assertThat(roles.forRole(ModelRole.utility))
                    .as("没配就继承：摘要用主模型在成本上可以接受")
                    .isSameAs(roles.forRole(ModelRole.main));
        });
    }

    @Test
    @DisplayName("默认注入的 ChatModel 仍是 main")
    void primaryBeanIsMainRole() {
        runner.run(context -> {
            ChatModel injected = context.getBean(ChatModel.class);
            assertThat(injected).isSameAs(context.getBean(ModelRoles.class).forRole(ModelRole.main));
        });
    }

    @Test
    @DisplayName("auto 档取主供应商里最便宜的一行")
    void autoPicksCheapestOfPrimaryProvider() {
        runner.withPropertyValues(
                        "claw4j.model.primary=qwen/qwen-plus",
                        "claw4j.model.roles.utility.auto=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatModel utility = context.getBean(ModelRoles.class).forRole(ModelRole.utility);
                    assertThat(utility)
                            .as("qwen-turbo 的 input 单价 0.1 比 qwen-plus 的 0.4 便宜")
                            .isNotSameAs(context.getBean(ModelRoles.class).forRole(ModelRole.main));
                });
    }

    @Test
    @DisplayName("指标带 role 标签：否则看不出这次是哪条链答的")
    void metricsCarryRoleTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatModel fake = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                return org.springframework.ai.chat.model.ChatResponse.builder()
                        .generations(java.util.List.of(new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("答"))))
                        .build();
            }

            @Override
            public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                return reactor.core.publisher.Flux.empty();
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
            }
        };

        new io.github.claw4j.model.usage.MeteredChatModel(fake, "qwen/qwen-plus", "utility", registry)
                .call(new org.springframework.ai.chat.prompt.Prompt("压缩这段"));

        assertThat(registry.get(io.github.claw4j.model.usage.MeteredChatModel.CALLS)
                .tag("role", "utility")
                .tag("outcome", "ok")
                .counter()
                .count())
                .isEqualTo(1.0);
    }
}
