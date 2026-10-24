package io.github.claw4j.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型的装配与主备降级：配错了要红，主挂了要换一家，换的时候参数要跟着过去。
 *
 * <p>两半合成一份，因为它们是同一件事的两面：装配决定"路由里有谁"，
 * 路由决定"主出事时怎么办"。任何一半失效，现场都是"降级写出来等于没写"。
 *
 * <p>用假模型测降级：降级是"平时看不见、出事才生效"的逻辑，靠真实调用怎么也测不全
 * ——你没法让 DeepSeek 在测试里恰好超时。假模型能把每一种失败时序摆出来。
 */
class ModelRoutingTest {

    private static final String[] BOTH_KEYS = {
            "claw4j.model.deepseek.api-key=unit-test-ds",
            "claw4j.model.deepseek.base-url=https://api.deepseek.com",
            "claw4j.model.deepseek.model=deepseek-chat",
            "claw4j.model.qwen.api-key=unit-test-qwen",
            "claw4j.model.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
            "claw4j.model.qwen.model=qwen-plus"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelConfiguration.class, RoutingModelConfiguration.class));

    // ------------------------------------------------------------------ 装配

    @Test
    @DisplayName("有 key 就装配真实模型并包进路由；两家都有时按 primary 决定顺序；只配备的只有它")
    void 装配与顺序() {
        contextRunner.withPropertyValues(
                        "claw4j.model.deepseek.api-key=unit-test-ds",
                        "claw4j.model.deepseek.base-url=https://api.deepseek.com",
                        "claw4j.model.deepseek.model=deepseek-chat")
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingChatModel.class);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(RoutingChatModel.class);
                    assertThat(context.getBean(RoutingChatModel.class).routeOrder()).containsExactly("deepseek");
                    assertThat(context.getBean(ModelConfiguration.DEEPSEEK_BEAN)).isInstanceOf(DeepSeekChatModel.class);
                });

        contextRunner.withPropertyValues(BOTH_KEYS)
                .run(context -> assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                        .containsExactly("deepseek", "qwen"));

        contextRunner.withPropertyValues(BOTH_KEYS)
                .withPropertyValues("claw4j.model.primary=qwen")
                .run(context -> assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                        .containsExactly("qwen", "deepseek"));

        // 只给备用的 key 也算"有真实模型"：路由里就它一个，没有备可用
        contextRunner.withPropertyValues(
                        "claw4j.model.qwen.api-key=unit-test-qwen",
                        "claw4j.model.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "claw4j.model.qwen.model=qwen-plus",
                        "claw4j.model.primary=qwen")
                .run(context -> assertThat(context.getBean(RoutingChatModel.class).routeOrder())
                        .containsExactly("qwen"));
    }

    /**
     * 配置"读进来了"不等于"生效了"：第一次用假端点跑真实链路时，
     * DeepSeek 请求里的 model 是 null——厂商返回空内容且不报错。
     *
     * <p>温度默认零也是同一条理由的延伸：这是个查数、调工具的 Agent，
     * 同一个问题两次问出两个不同的数是故障，不是个性。
     */
    @Test
    @DisplayName("模型名与零温度真的落在装出来的 bean 上；内部只留短重试，不叠默认的指数退避")
    void 配置真的生效() {
        contextRunner.withPropertyValues(BOTH_KEYS).run(context -> {
            assertThat(context.getBean(ModelConfiguration.DEEPSEEK_BEAN, ChatModel.class)
                    .getOptions().getModel()).isEqualTo("deepseek-chat");
            assertThat(context.getBean(ModelConfiguration.QWEN_BEAN, ChatModel.class)
                    .getOptions().getModel()).isEqualTo("qwen-plus");
            assertThat(context.getBean(ModelConfiguration.DEEPSEEK_BEAN, ChatModel.class)
                    .getOptions().getTemperature()).isEqualTo(0.0);
            assertThat(context.getBean(ModelConfiguration.QWEN_BEAN, ChatModel.class)
                    .getOptions().getTemperature()).isEqualTo(0.0);

            // 默认模板是 10 次、2 秒起、×5 递增；上层还有路由在"换一家"，
            // 两层相乘实测 4 分 40 秒还没轮到备。超时预算该花在换供应商上
            DeepSeekChatModel deepSeek = context.getBean(ModelConfiguration.DEEPSEEK_BEAN, DeepSeekChatModel.class);
            assertThat(deepSeek.retryTemplate).isSameAs(RetryUtils.SHORT_RETRY_TEMPLATE);
            assertThat(deepSeek.retryTemplate).isNotSameAs(RetryUtils.DEFAULT_RETRY_TEMPLATE);
        });
    }

    /** 没有 key 就是启动失败，不退到假模型——那是"本地跑着 mock 却以为在跑真模型"的来源。 */
    @Test
    @DisplayName("没有任何 key、或只有空串/空白的 key，都启动失败并点名去哪配")
    void 没key就启动失败() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().getMessage()).contains("DEEPSEEK_API_KEY", "QWEN_API_KEY");
        });

        for (String blank : new String[]{"", "   "}) {
            contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=" + blank).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure().getMessage()).contains("DEEPSEEK_API_KEY");
            });
        }
    }

    /**
     * 主模型那家没配 key 就直接失败，而不是悄悄拿备用的顶上——
     * 悄悄顶上的话，"我们主模型其实一直没配好"这件事永远不会暴露。
     */
    @Test
    @DisplayName("primary 指向没 key 的家、指向不存在的名字、或缺端点，都启动失败并说清原因")
    void primary配错就启动失败() {
        contextRunner.withPropertyValues(
                        "claw4j.model.qwen.api-key=unit-test-qwen",
                        "claw4j.model.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "claw4j.model.qwen.model=qwen-plus")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("deepseek", "DEEPSEEK_API_KEY");
                });

        contextRunner.withPropertyValues(BOTH_KEYS).withPropertyValues("claw4j.model.primary=gpt")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("gpt", "deepseek", "qwen");
                });

        contextRunner.withPropertyValues("claw4j.model.deepseek.api-key=unit-test-ds")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("base-url", "DEEPSEEK_BASE_URL");
                });
    }

    // ------------------------------------------------------------------ 降级

    @Test
    @DisplayName("主正常时不惊动备；主失败时退到备")
    void 主备切换() {
        FakeChatModel primary = FakeChatModel.ok("deepseek", "来自主模型");
        FakeChatModel secondary = FakeChatModel.ok("qwen", "来自备模型");

        assertThat(text(routing(primary, secondary).call(prompt("你好")))).isEqualTo("来自主模型");
        assertThat(primary.callCount()).isEqualTo(1);
        assertThat(secondary.callCount()).isZero();

        assertThat(text(routing(FakeChatModel.broken("deepseek", new IllegalStateException("连接超时")),
                FakeChatModel.ok("qwen", "来自备模型")).call(prompt("你好")))).isEqualTo("来自备模型");
    }

    @Test
    @DisplayName("全部失败时抛出路由异常，且每一次失败都带在 suppressed 里")
    void 全部失败带全部原因() {
        IllegalStateException first = new IllegalStateException("主模型 429");
        IllegalStateException second = new IllegalStateException("备模型 500");
        RoutingChatModel routing = routing(FakeChatModel.broken("deepseek", first),
                FakeChatModel.broken("qwen", second));

        assertThatThrownBy(() -> routing.call(prompt("你好")))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("deepseek")
                .hasMessageContaining("qwen")
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(first, second));
    }

    /**
     * 已经吐出去的内容不能重来：此时再转投备模型，客户端会收到"半句主 + 全句备"。
     * 所以流式的降级窗口只到首块为止。
     */
    @Test
    @DisplayName("流式：首块之前失败会降级；已经吐出内容后失败不再降级")
    void 流式降级窗口() {
        List<String> chunks = routing(FakeChatModel.broken("deepseek", new IllegalStateException("握手失败")),
                FakeChatModel.ok("qwen", "来自备模型"))
                .stream(prompt("你好"))
                .map(ModelRoutingTest::text)
                .collectList()
                .block();
        assertThat(chunks).containsExactly("来自备模型");

        FakeChatModel secondary = FakeChatModel.ok("qwen", "来自备模型");
        RoutingChatModel routing = routing(
                FakeChatModel.breaksAfterFirstChunk("deepseek", "前半句", new IllegalStateException("读到一半断了")),
                secondary);

        assertThatThrownBy(() -> routing.stream(prompt("你好")).collectList().block())
                .hasMessageContaining("读到一半断了");
        assertThat(secondary.streamCount()).isZero();
    }

    @Test
    @DisplayName("默认参数取主模型的")
    void 默认参数取主模型() {
        RoutingChatModel routing = routing(FakeChatModel.ok("deepseek", "主"), FakeChatModel.ok("qwen", "备"));

        assertThat(routing.getOptions().getModel()).isEqualTo("deepseek");
    }

    /**
     * 各家 ChatOptions 互不兼容，厂商的 ChatModel 直接把 {@code prompt.getOptions()} 强转成自己那一型。
     * 降级时原样把主模型的参数递给备模型，当场 ClassCastException——救命的那条路径反而必崩。
     *
     * <p>模型名绝不能带过去（那等于让 DeepSeek 去跑 qwen-plus），
     * 但厂商无关的参数要跟着过去，否则调用方设的温度在降级后悄悄失效。
     */
    @Test
    @DisplayName("降级时把参数换成备那家的类型：模型名换掉，厂商无关参数跟过去")
    void 降级时转换参数类型() {
        FakeChatModel primary = FakeChatModel
                .broken("qwen", new IllegalStateException("429 限流"))
                .withOptions(OpenAiChatOptions.builder().model("qwen-plus").temperature(0.2).build());
        FakeChatModel secondary = FakeChatModel
                .ok("deepseek", "来自备模型")
                .withOptions(DeepSeekChatOptions.builder().model("deepseek-chat").build());

        assertThat(text(routing(primary, secondary).call(new Prompt(new UserMessage("你好"), primary.getOptions()))))
                .isEqualTo("来自备模型");

        ChatOptions used = secondary.lastPrompt().getOptions();
        assertThat(used).isInstanceOf(DeepSeekChatOptions.class);
        assertThat(used.getModel()).isEqualTo("deepseek-chat");
        assertThat(used.getTemperature()).isEqualTo(0.2);
    }

    /**
     * 这条比类型转换那个更阴：那一个降级时直接崩，这一个降级后<b>照常返回，只是再也没有工具</b>。
     * 真实链路上的表现是"我无法直接访问数据库"——不报错、不超时，只是查不到数了。
     */
    @Test
    @DisplayName("降级时工具回调与工具上下文跟着过去")
    void 降级时带上工具() {
        ToolCallback tool = new FakeToolCallback("querySales");
        FakeChatModel primary = FakeChatModel
                .broken("deepseek", new IllegalStateException("连接被拒"))
                .withOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-chat")
                        .toolCallbacks(List.of(tool))
                        .toolContext(Map.of("tenant", "acme"))
                        .build());
        FakeChatModel secondary = FakeChatModel
                .ok("qwen", "来自备模型")
                .withOptions(OpenAiChatOptions.builder().model("qwen-plus").build());

        routing(primary, secondary).call(new Prompt(new UserMessage("上周华东区成交额多少"), primary.getOptions()));

        ChatOptions used = secondary.lastPrompt().getOptions();
        assertThat(used).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(((ToolCallingChatOptions) used).getToolCallbacks()).containsExactly(tool);
        assertThat(((ToolCallingChatOptions) used).getToolContext()).containsEntry("tenant", "acme");
    }

    @Test
    @DisplayName("调用方给的参数不是这一家的类型时也要转换；空路由直接拒绝")
    void 参数转换与空路由() {
        FakeChatModel primary = FakeChatModel
                .ok("deepseek", "主模型回答")
                .withOptions(DeepSeekChatOptions.builder().model("deepseek-chat").build());

        routing(primary, FakeChatModel.ok("qwen", "备")).call(new Prompt(new UserMessage("你好"),
                ChatOptions.builder().model("随便写的").temperature(0.9).build()));

        ChatOptions used = primary.lastPrompt().getOptions();
        assertThat(used).isInstanceOf(DeepSeekChatOptions.class);
        assertThat(used.getModel()).isEqualTo("deepseek-chat");
        assertThat(used.getTemperature()).isEqualTo(0.9);

        assertThatThrownBy(() -> new RoutingChatModel(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 辅助

    private static Prompt prompt(String text) {
        return new Prompt(new UserMessage(text));
    }

    private static RoutingChatModel routing(FakeChatModel primary, FakeChatModel secondary) {
        return new RoutingChatModel(List.of(
                new RoutingChatModel.Route(primary.name(), primary),
                new RoutingChatModel.Route(secondary.name(), secondary)));
    }

    private static String text(ChatResponse response) {
        return response.getResult().getOutput().getText();
    }

    /**
     * 假模型：能正常回答、能一调用就炸、也能"吐出一块再炸"。
     * 第三种是流式降级规则的边界，没有它这条规则测不出来。
     */
    private static final class FakeChatModel implements ChatModel {

        private final String name;
        private final RuntimeException failure;
        private final String chunkBeforeFailure;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger streams = new AtomicInteger();
        private ChatOptions options;
        private Prompt lastPrompt;

        private FakeChatModel(String name, RuntimeException failure, String chunkBeforeFailure) {
            this.name = name;
            this.failure = failure;
            this.chunkBeforeFailure = chunkBeforeFailure;
        }

        static FakeChatModel ok(String name, String answer) {
            return new FakeChatModel(name, null, answer);
        }

        /** 同步与流式都在吐出任何内容之前失败。 */
        static FakeChatModel broken(String name, RuntimeException failure) {
            return new FakeChatModel(name, failure, null);
        }

        /** 流式先吐一块再失败；同步调用仍然成功。 */
        static FakeChatModel breaksAfterFirstChunk(String name, String chunk, RuntimeException failure) {
            return new FakeChatModel(name, failure, chunk);
        }

        String name() {
            return name;
        }

        int callCount() {
            return calls.get();
        }

        int streamCount() {
            return streams.get();
        }

        /** 这一家的参数类型。跨厂商降级时能不能转对，全靠它演得像。 */
        FakeChatModel withOptions(ChatOptions options) {
            this.options = options;
            return this;
        }

        Prompt lastPrompt() {
            return lastPrompt;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            if (failure != null && chunkBeforeFailure == null) {
                throw failure;
            }
            return response(failure == null ? chunkBeforeFailure : name);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streams.incrementAndGet();
            lastPrompt = prompt;
            if (failure == null) {
                return Flux.just(response(chunkBeforeFailure));
            }
            return chunkBeforeFailure == null
                    ? Flux.error(failure)
                    : Flux.just(response(chunkBeforeFailure)).concatWith(Flux.error(failure));
        }

        @Override
        public ChatOptions getOptions() {
            return options != null ? options : ChatOptions.builder().model(name).build();
        }

        private static ChatResponse response(String text) {
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(text))))
                    .build();
        }
    }

    /** 最小可用的工具回调：只要能报出自己的名字就够了——这里验的是"它有没有被搬过去"。 */
    private record FakeToolCallback(String name) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("假工具，只为验参数搬运")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String input) {
            return "{}";
        }
    }
}
