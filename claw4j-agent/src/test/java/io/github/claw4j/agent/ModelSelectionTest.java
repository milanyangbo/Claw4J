package io.github.claw4j.agent;

import io.github.claw4j.agent.model.MockChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型选择的四种情形。用 ApplicationContextRunner 而不是 @SpringBootTest，
 * 因为它能在同一个 JVM 里反复换配置跑条件装配，又快又不联网。
 */
class ModelSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ModelConfiguration.class,
                    MockModelConfiguration.class));

    @Test
    void 有key时装配真实模型() {
        contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=sk-unit-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(DeepSeekChatModel.class);
                    assertThat(context).doesNotHaveBean(MockChatModel.class);
                });
    }

    @Test
    void 没有key时降级到mock() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(MockChatModel.class);
        });
    }

    @Test
    void 显式要求mock时即使有key也走mock() {
        contextRunner
                .withPropertyValues(
                        "claw4j.model.deepseek.api-key=sk-unit-test",
                        "claw4j.model.mock=true")
                .run(context -> assertThat(context.getBean(ChatModel.class)).isInstanceOf(MockChatModel.class));
    }

    @Test
    void 显式要求真实模型却没给key时启动失败并说清原因() {
        contextRunner.withPropertyValues("claw4j.model.mock=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("DEEPSEEK_API_KEY");
                });
    }

    @Test
    void 空字符串的key等同于没给key() {
        contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=")
                .run(context -> assertThat(context.getBean(ChatModel.class)).isInstanceOf(MockChatModel.class));
    }

    @Test
    void 只有空白字符的key不算提供了key() {
        contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=   ")
                .run(context -> assertThat(context.getBean(ChatModel.class)).isInstanceOf(MockChatModel.class));
    }
}
