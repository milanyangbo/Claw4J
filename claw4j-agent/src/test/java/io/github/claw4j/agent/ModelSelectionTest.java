package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RoutingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型选择的各种情形。用 ApplicationContextRunner 而不是 @SpringBootTest，
 * 因为它能在同一个 JVM 里反复换配置跑条件装配，又快又不联网
 * （这里装配的是真实模型对象，但一个请求都不发）。
 *
 * <p>装配结果只有一种形态：**真实模型 + 一个路由**。没有 mock 兜底了（ADR-0011）：
 * 一家 key 都没配时是**启动失败**，不再退到回声模型——那正是"本地跑着 mock
 * 却以为在跑真模型"的来源。所以这个测试类里有一半在钉"各种缺 key 的姿势都必须红"。
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
                    RoutingModelConfiguration.class));

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
     * 温度默认 0（ADR-0011）。
     *
     * <p>测试连了真模型之后，非零温度等于给每条断言都掺一份随机；
     * 而这是个查数、调工具的 Agent，同一个问题两次问出两个不同的数是故障，不是个性。
     * 这条断言钉的是"默认"——有人为了演示效果把它调高了，这里会红，提醒他测试也会跟着飘。
     */
    @Test
    void 温度默认为零_否则每条断言都掺一份随机() {
        contextRunner.withPropertyValues(BOTH_KEYS)
                .run(context -> {
                    assertThat(context.getBean(ModelConfiguration.DEEPSEEK_BEAN, ChatModel.class)
                            .getOptions().getTemperature()).isEqualTo(0.0);
                    assertThat(context.getBean(ModelConfiguration.QWEN_BEAN, ChatModel.class)
                            .getOptions().getTemperature()).isEqualTo(0.0);
                });
    }

    /**
     * 模型内部只留短重试。
     *
     * <p>默认的 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} 是 10 次、2 秒起、×5 递增、上限 180 秒；
     * 上层还有 {@code RoutingChatModel} 在"换一家"，两层相乘的结果实测是
     * 4 分 40 秒还没轮到备（主的 base-url 指到没人听的端口）。降级写出来等于没写，
     * 所以这里钉死：内部用 {@code SHORT_RETRY_TEMPLATE}（10 次 × 100ms，合计约 1 秒），
     * 超时预算花在换供应商上，而不是在同一家身上等。
     *
     * <p>断言用引用相等而不是"看它重试几次"，是因为这个坑的形状就是"不知不觉用了默认模板"。
     */
    @Test
    void 模型内部只留短重试_不叠默认的指数退避() {
        contextRunner.withPropertyValues(BOTH_KEYS)
                .run(context -> {
                    DeepSeekChatModel deepSeek = context.getBean(
                            ModelConfiguration.DEEPSEEK_BEAN, DeepSeekChatModel.class);
                    assertThat(deepSeek.retryTemplate).isSameAs(RetryUtils.SHORT_RETRY_TEMPLATE);
                    assertThat(deepSeek.retryTemplate).isNotSameAs(RetryUtils.DEFAULT_RETRY_TEMPLATE);
                });
    }

    /** 只给备用的 key 也算"有真实模型"：路由里就它一个，没有备可用。 */
    @Test
    void 只有千问有key时路由里只有千问() {
        contextRunner.withPropertyValues(
                        "claw4j.model.qwen.api-key=sk-qwen-unit-test",
                        "claw4j.model.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "claw4j.model.qwen.model=qwen-plus",
                        "claw4j.model.primary=qwen")
                .run(context -> assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                        .containsExactly("qwen"));
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

    /**
     * <b>没有 key 就是启动失败（ADR-0011）。</b>
     *
     * <p>改真模型之前这里断言的是"降级到 mock 且容器里只有一个 ChatModel"。
     * 那条现在反过来了：没有任何 key 时 {@code ModelConfiguration} 会 fail fast，
     * 而不是装一个回声模型让应用假装能跑。失败消息里必须点名去哪个环境变量配。
     */
    @Test
    void 没有key时启动失败并说清去哪配() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().getMessage())
                    .contains("DEEPSEEK_API_KEY", "QWEN_API_KEY");
        });
    }

    /**
     * 空字符串与空白都算"没给 key"——这个判断正是当初要自定义 Condition 的原因：
     * {@code @ConditionalOnProperty} 只判"属性等于某值"，空字符串也会算属性存在。
     * 现在这两种姿势的表现是启动失败，而不是装出一个带空 key 的模型。
     */
    @Test
    void 空字符串或空白的key等同于没给key() {
        contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("DEEPSEEK_API_KEY");
                });

        contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("DEEPSEEK_API_KEY");
                });
    }
}
