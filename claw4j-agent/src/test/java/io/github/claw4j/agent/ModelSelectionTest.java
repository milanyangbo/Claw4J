package io.github.claw4j.agent;

import io.github.claw4j.agent.model.MockChatModel;
import io.github.claw4j.agent.model.RoutingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型选择的各种情形。用 ApplicationContextRunner 而不是 @SpringBootTest，
 * 因为它能在同一个 JVM 里反复换配置跑条件装配，又快又不联网。
 *
 * <p>装配结果现在有两种形态：装了真实模型时是"各家模型 + 一个路由"，
 * 一家都没有时是"一个 mock"。路由才是业务侧看到的那一个（@Primary）。
 */
class ModelSelectionTest {

    /** 两家都配齐的配置，省得每个用例里重复一堆属性。 */
    private static final String[] BOTH_KEYS = {
            "claw4j.model.deepseek.api-key=sk-ds-unit-test",
            "claw4j.model.deepseek.base-url=https://api.deepseek.com",
            "claw4j.model.deepseek.model=deepseek-chat",
            "claw4j.model.qwen.api-key=sk-qwen-unit-test",
            "claw4j.model.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
            "claw4j.model.qwen.model=qwen-plus"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ModelConfiguration.class,
                    RoutingModelConfiguration.class,
                    MockModelConfiguration.class));

    @Test
    void 有key时装配真实模型并包进路由() {
        contextRunner.withPropertyValues(
                        "claw4j.model.deepseek.api-key=sk-unit-test",
                        "claw4j.model.deepseek.base-url=https://api.deepseek.com",
                        "claw4j.model.deepseek.model=deepseek-chat")
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingChatModel.class);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(RoutingChatModel.class);
                    assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                            .containsExactly("deepseek");
                    assertThat(context.getBean(ModelConfiguration.DEEPSEEK_BEAN))
                            .isInstanceOf(DeepSeekChatModel.class);
                    assertThat(context).doesNotHaveBean(MockChatModel.class);
                });
    }

    @Test
    void 两家都有key时按primary决定主备顺序() {
        contextRunner.withPropertyValues(BOTH_KEYS)
                .run(context -> assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                        .containsExactly("deepseek", "qwen"));

        contextRunner.withPropertyValues(BOTH_KEYS)
                .withPropertyValues("claw4j.model.primary=qwen")
                .run(context -> assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                        .containsExactly("qwen", "deepseek"));
    }

    /**
     * 模型名必须真的落在装出来的模型上（ADR-0006 里那个坑：默认 options 的 model 是 null，
     * 请求不带 model 时厂商返回空内容且不报错）。这里把断言钉在 bean 上而不是配置上 ——
     * 配了不等于生效：第一次用假端点跑真实链路时，DeepSeek 请求里的 model 就是 null。
     */
    @Test
    void 装配出来的模型带着配置的model名() {
        contextRunner.withPropertyValues(BOTH_KEYS)
                .run(context -> {
                    assertThat(context.getBean(ModelConfiguration.DEEPSEEK_BEAN, ChatModel.class)
                            .getOptions().getModel()).isEqualTo("deepseek-chat");
                    assertThat(context.getBean(ModelConfiguration.QWEN_BEAN, ChatModel.class)
                            .getOptions().getModel()).isEqualTo("qwen-plus");
                });
    }

    /**
     * 只给备用的 key 也算"有真实模型"：路由里就它一个，没有备可用，但总好过退回 mock。
     */
    @Test
    void 只有千问有key时路由里只有千问() {
        contextRunner.withPropertyValues(
                        "claw4j.model.qwen.api-key=sk-qwen-unit-test",
                        "claw4j.model.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "claw4j.model.qwen.model=qwen-plus",
                        "claw4j.model.primary=qwen")
                .run(context -> {
                    assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                            .containsExactly("qwen");
                    assertThat(context).doesNotHaveBean(MockChatModel.class);
                });
    }

    /**
     * 主模型那家没配 key 就直接启动失败，而不是悄悄拿备用的顶上。
     * 悄悄顶上的话，"我们主模型其实一直没配好"这件事永远不会暴露。
     */
    @Test
    void primary指向的那家没key时启动失败并说清去哪配() {
        contextRunner.withPropertyValues(
                        "claw4j.model.qwen.api-key=sk-qwen-unit-test",
                        "claw4j.model.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "claw4j.model.qwen.model=qwen-plus")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("deepseek", "DEEPSEEK_API_KEY");
                });
    }

    @Test
    void primary配了不存在的供应商时启动失败并列出可选值() {
        contextRunner.withPropertyValues(BOTH_KEYS)
                .withPropertyValues("claw4j.model.primary=gpt")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("gpt", "deepseek", "qwen");
                });
    }

    /**
     * 端点与模型名的默认值只活在 application.yml，Java 侧没有兜底（ADR-0006）。
     * 缺失时启动失败并说清去哪配，好过拿着空端点发出第一个请求才炸。
     */
    @Test
    void 有key但缺端点或模型名时启动失败并说清原因() {
        contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=sk-unit-test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("base-url", "DEEPSEEK_BASE_URL");
                });
    }

    @Test
    void 没有key时降级到mock() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(MockChatModel.class);
            assertThat(context).doesNotHaveBean(RoutingChatModel.class);
        });
    }

    @Test
    void 显式要求mock时即使有key也走mock() {
        contextRunner
                .withPropertyValues(BOTH_KEYS)
                .withPropertyValues("claw4j.model.mock=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(MockChatModel.class);
                    assertThat(context).doesNotHaveBean(RoutingChatModel.class);
                });
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
