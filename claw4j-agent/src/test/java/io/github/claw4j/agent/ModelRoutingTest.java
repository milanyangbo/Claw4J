package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ModelConfiguration;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.model.ModelRoutingException;
import io.github.claw4j.agent.model.RoutingChatModel;
import io.github.claw4j.agent.model.RoutingModelConfiguration;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 一个完整功能：模型路由。主供应商挂了以后，这条链还能不能"答得出、还查得到数"。
 *
 * <p>最容易坏的不是"没降级"，是降级降成了半个：换了模型却把工具或工具上下文丢了，
 * 或者把主模型的选项类型递给备模型。三种表现都不是异常，是模型突然开始说
 * "我无法直接访问数据库"，而构建全绿。
 */
class ModelRoutingTest {

    private static final Principal PRINCIPAL = Principal.readOnly("u-1");

    @Test
    @DisplayName("主模型失败后退到备模型：答案来自备，工具与工具上下文都跟着过去")
    void fallsBackAndKeepsToolsAndContext() {
        StubModel primary = new StubModel(OpenAiChatOptions.builder().model("qwen-plus").build())
                .onCall(prompt -> {
                    throw new IllegalStateException("主供应商限流");
                });
        StubModel backup = new StubModel(DeepSeekChatOptions.builder().model("deepseek-chat").build())
                .onCall(prompt -> text("备模型的答案"));
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("qwen", primary),
                new RoutingChatModel.Route("deepseek", backup)));

        Prompt prompt = new Prompt("本月成交额",
                OpenAiChatOptions.builder()
                        .model("qwen-plus")
                        .toolCallbacks(new NamedTool("querySales"))
                        .toolContext(Map.of("claw4j.principal", PRINCIPAL))
                        .build());

        String answer = textOf(routing.call(prompt));

        assertThat(answer).isEqualTo("备模型的答案");
        assertThat(backup.prompts()).hasSize(1);
        ChatOptions delivered = backup.prompts().get(0).getOptions();
        assertThat(delivered)
                .as("主模型的选项类型递给备模型时，真厂商会当场 ClassCastException")
                .isInstanceOf(DeepSeekChatOptions.class);
        DeepSeekChatOptions options = (DeepSeekChatOptions) delivered;
        assertThat(options.getModel())
                .as("把主模型的 model 名带给备模型，等于让备去跑别人的模型")
                .isEqualTo("deepseek-chat");
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("querySales");
        assertThat(options.getToolContext()).containsEntry("claw4j.principal", PRINCIPAL);
    }

    @Test
    @DisplayName("流式在首块之前失败才降级：备模型接住，客户端只收到一份内容")
    void fallsBackOnStreamBeforeFirstChunk() {
        StubModel primary = new StubModel(ChatOptions.builder().build())
                .onStream(prompt -> Flux.error(new IllegalStateException("首包没拿到")));
        StubModel backup = new StubModel(ChatOptions.builder().build())
                .onStream(prompt -> Flux.just(text("备"), text("答")));
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("deepseek", primary),
                new RoutingChatModel.Route("qwen", backup)));

        List<String> chunks = routing.stream(new Prompt("本月成交额"))
                .map(ModelRoutingTest::textOf)
                .collectList()
                .block();

        assertThat(chunks).containsExactly("备", "答");
        assertThat(backup.prompts()).hasSize(1);
    }

    @Test
    @DisplayName("流式已经吐过块后不再降级：宁可报错，也不让客户端收到两遍回答")
    void doesNotFallBackAfterStreamingHasStarted() {
        StubModel primary = new StubModel(ChatOptions.builder().build())
                .onStream(prompt -> Flux.concat(
                        Flux.just(text("前半")),
                        Flux.error(new IllegalStateException("流中途断了"))));
        StubModel backup = new StubModel(ChatOptions.builder().build())
                .onStream(prompt -> Flux.just(text("备模型全文")));
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("deepseek", primary),
                new RoutingChatModel.Route("qwen", backup)));
        List<String> received = new ArrayList<>();

        assertThatThrownBy(() -> routing.stream(new Prompt("本月成交额"))
                .doOnNext(chunk -> received.add(textOf(chunk)))
                .blockLast())
                .hasMessageContaining("流中途断了");

        assertThat(received).containsExactly("前半");
        assertThat(backup.prompts()).as("已经吐过块，备份再上就是重复内容").isEmpty();
    }

    @Test
    @DisplayName("所有模型都失败时抛 ModelRoutingException，并把每次失败挂在 suppressed 上")
    void throwsRoutingExceptionInsteadOfReturningEmpty() {
        StubModel primary = new StubModel(ChatOptions.builder().build())
                .onCall(prompt -> {
                    throw new IllegalStateException("主挂了");
                });
        StubModel backup = new StubModel(ChatOptions.builder().build())
                .onCall(prompt -> {
                    throw new IllegalArgumentException("备也挂了");
                });
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("deepseek", primary),
                new RoutingChatModel.Route("qwen", backup)));

        Throwable failure = catchThrowable(() -> routing.call(new Prompt("本月成交额")));

        assertThat(failure).isInstanceOf(ModelRoutingException.class);
        assertThat(failure.getSuppressed()).hasSize(2);
        assertThat(failure).hasMessageContaining("deepseek").hasMessageContaining("qwen");
    }

    @Test
    @DisplayName("空路由直接拒绝：没有候选的“路由”只会静默返回空回答")
    void rejectsEmptyRouteList() {
        assertThatThrownBy(() -> new RoutingChatModel(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一个可用模型");
    }

    @Test
    @DisplayName("路由顺序由 primary 决定：主在前，其余在后")
    void ordersRoutesWithPrimaryFirst() {
        StubModel deepseek = new StubModel(ChatOptions.builder().build());
        StubModel qwen = new StubModel(ChatOptions.builder().build());
        ModelProperties properties = new ModelProperties();
        properties.setPrimary(ModelProperties.QWEN);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ModelConfiguration.DEEPSEEK_BEAN, ChatModel.class, () -> deepseek);
            context.registerBean(ModelConfiguration.QWEN_BEAN, ChatModel.class, () -> qwen);
            context.refresh();

            RoutingChatModel routing = new RoutingModelConfiguration().routingChatModel(properties, context);

            assertThat(routing.routeOrder()).containsExactly("qwen", "deepseek");
        }
    }

    @Test
    @DisplayName("primary 写了不存在的供应商时启动失败并列出可选值，不静默换成默认那家")
    void rejectsUnknownPrimary() {
        StubModel deepseek = new StubModel(ChatOptions.builder().build());
        ModelProperties properties = new ModelProperties();
        properties.setPrimary("openai");

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ModelConfiguration.DEEPSEEK_BEAN, ChatModel.class, () -> deepseek);
            context.refresh();

            assertThatThrownBy(() -> new RoutingModelConfiguration().routingChatModel(properties, context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("openai")
                    .hasMessageContaining("deepseek")
                    .hasMessageContaining("qwen");
        }
    }

    @Test
    @DisplayName("primary 那家没配 key 时启动失败，不悄悄拿备用的顶上")
    void rejectsPrimaryWithoutKey() {
        StubModel qwen = new StubModel(ChatOptions.builder().build());
        ModelProperties properties = new ModelProperties();
        properties.setPrimary(ModelProperties.DEEPSEEK);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(ModelConfiguration.QWEN_BEAN, ChatModel.class, () -> qwen);
            context.refresh();

            assertThatThrownBy(() -> new RoutingModelConfiguration().routingChatModel(properties, context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deepseek")
                    .hasMessageContaining("没有配置 key");
        }
    }

    @Test
    @DisplayName("一个 key 都没有时装配阶段就失败，并说清去配哪个环境变量")
    void failsFastWhenNoProviderIsConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(ModelConfiguration.class)
                .withPropertyValues("claw4j.model.mock=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().getRootCause()
                            .hasMessageContaining("没有配置任何 API key")
                            .hasMessageContaining("DEEPSEEK_API_KEY");
                });
    }

    @Test
    @DisplayName("只有备的那家配了 key 时路由只装那一家，主那家不会跳出来报“我没 key”")
    void installsOnlyTheProviderThatHasKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(ModelConfiguration.class, RoutingModelConfiguration.class)
                .withPropertyValues(
                        "claw4j.model.primary=qwen",
                        "claw4j.model.qwen.api-key=test-key",
                        "claw4j.model.qwen.base-url=https://qwen.invalid/compatible-mode/v1",
                        "claw4j.model.qwen.model=qwen-plus")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ModelConfiguration.DEEPSEEK_BEAN);
                    assertThat(context).hasSingleBean(RoutingChatModel.class);
                    assertThat(context.getBean(RoutingChatModel.class).routeOrder()).containsExactly("qwen");
                });
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(value))))
                .build();
    }

    private static String textOf(ChatResponse response) {
        return response.getResult().getOutput().getText();
    }

    private static final class NamedTool implements ToolCallback {

        private final String name;

        private NamedTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("测试工具 " + name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }
    }

    /** 纯替身：验的是“换一家”这件事，不是模型怎么回答。 */
    private static final class StubModel implements ChatModel {

        private final ChatOptions options;
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        private Function<Prompt, ChatResponse> call = prompt -> text("ok");
        private Function<Prompt, Flux<ChatResponse>> stream = prompt -> Flux.just(text("ok"));

        private StubModel(ChatOptions options) {
            this.options = options;
        }

        StubModel onCall(Function<Prompt, ChatResponse> responder) {
            this.call = responder;
            return this;
        }

        StubModel onStream(Function<Prompt, Flux<ChatResponse>> responder) {
            this.stream = responder;
            return this;
        }

        List<Prompt> prompts() {
            return prompts;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return call.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return stream.apply(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }
    }
}
