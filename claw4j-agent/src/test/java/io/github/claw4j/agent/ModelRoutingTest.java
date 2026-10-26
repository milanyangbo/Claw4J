package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ModelRoutingException;
import io.github.claw4j.agent.model.RoutingChatModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型路由：主挂了下一次调用换谁，以及"换了之后还能不能干活"。
 *
 * <p>这里不验"模型会不会答"，只验路由边界：降级条件、流式窗口、失败时的出口，
 * 以及跨厂商换模型时工具与上下文有没有跟着走。
 */
class ModelRoutingTest {

    private static final ToolCallback TOOL = new RecordingTool("querySales", "128400");

    @Test
    @DisplayName("主模型正常时备模型一次都不碰")
    void primarySuccessDoesNotTouchBackup() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().model("deepseek-chat").build(),
                        prompt -> answer("主模型的回答"),
                        prompt -> Flux.just(answer("主模型的回答")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().model("qwen-plus").build(),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return answer("备模型的回答");
                        },
                        prompt -> Flux.just(answer("备模型的回答"))))));

        ChatResponse response = routing.call(prompt(DeepSeekChatOptions.builder().model("deepseek-chat").build()));

        assertThat(textOf(response)).isEqualTo("主模型的回答");
        assertThat(backupCalls).hasValue(0);
    }

    @Test
    @DisplayName("主模型抛错时备模型接住，工具与工具上下文一个都不丢")
    void fallbackKeepsToolsAndToolContext() {
        List<Prompt> backupPrompts = new CopyOnWriteArrayList<>();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().model("deepseek-chat").build(),
                        prompt -> {
                            throw new IllegalStateException("429 too many requests");
                        },
                        prompt -> Flux.error(new IllegalStateException("429 too many requests")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().model("qwen-plus").build(),
                        prompt -> {
                            backupPrompts.add(prompt);
                            return answer("备模型接住了");
                        },
                        prompt -> Flux.just(answer("备模型接住了"))))));

        ChatOptions incoming = DeepSeekChatOptions.builder()
                .model("deepseek-chat")
                .temperature(0.3)
                .toolCallbacks(List.of(TOOL))
                .toolContext(Map.of("claw4j.principal", "guest"))
                .build();

        ChatResponse response = routing.call(prompt(incoming));

        assertThat(textOf(response)).isEqualTo("备模型接住了");
        assertThat(backupPrompts).hasSize(1);

        ChatOptions sent = backupPrompts.getFirst().getOptions();
        assertThat(sent).isInstanceOf(OpenAiChatOptions.class);
        assertThat(sent.getModel())
                .as("把主模型的 model 名带给备模型，等于让别家跑一个它没有的模型")
                .isEqualTo("qwen-plus");
        assertThat(sent.getTemperature()).isEqualTo(0.3);
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) sent).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("querySales");
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) sent).getToolContext())
                .containsEntry("claw4j.principal", "guest");
    }

    @Test
    @DisplayName("两路都失败时抛 ModelRoutingException，不静默返回空回答")
    void allRoutesFailingThrowsInsteadOfReturningEmpty() {
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw new IllegalStateException("主挂了");
                        },
                        prompt -> Flux.error(new IllegalStateException("主挂了")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            throw new IllegalStateException("备也挂了");
                        },
                        prompt -> Flux.error(new IllegalStateException("备也挂了"))))));

        assertThatThrownBy(() -> routing.call(prompt(DeepSeekChatOptions.builder().build())))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("primary")
                .hasMessageContaining("backup")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("主挂了", "备也挂了"));
    }

    @Test
    @DisplayName("空路由在构造时就失败：没有候选的「降级」没有意义")
    void emptyRoutesAreRejected() {
        assertThatThrownBy(() -> new RoutingChatModel(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一个");
    }

    @Test
    @DisplayName("流式首块之前失败才降级：备模型的块原样交给客户端")
    void streamFallsBackBeforeFirstChunk() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> Flux.error(new IllegalStateException("连接被重置")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return Flux.just(answer("备"), answer("接住"));
                        }))));

        List<String> texts = routing.stream(prompt(DeepSeekChatOptions.builder().build()))
                .map(ModelRoutingTest::textOf)
                .collectList()
                .block();

        assertThat(texts).containsExactly("备", "接住");
        assertThat(backupCalls).hasValue(1);
    }

    @Test
    @DisplayName("已经吐过一块再失败就不换人：同一条回答发两遍比报错更难接受")
    void streamDoesNotRestartAfterFirstChunk() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> Flux.concat(
                                Flux.just(answer("前半截")),
                                Flux.error(new IllegalStateException("流中断了"))))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return Flux.just(answer("全文"));
                        }))));

        assertThatThrownBy(() -> routing.stream(prompt(DeepSeekChatOptions.builder().build()))
                .map(ModelRoutingTest::textOf)
                .collectList()
                .block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("流中断了");
        assertThat(backupCalls).hasValue(0);
    }

    @Test
    @DisplayName("路由顺序就是主在前：日志与降级都按这个顺序")
    void routeOrderIsPrimaryFirst() {
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("deepseek", model(
                        DeepSeekChatOptions.builder().build(), prompt -> answer(""), prompt -> Flux.empty())),
                new RoutingChatModel.Route("qwen", model(
                        OpenAiChatOptions.builder().build(), prompt -> answer(""), prompt -> Flux.empty()))));

        assertThat(routing.routeOrder()).containsExactly("deepseek", "qwen");
    }

    @Test
    @DisplayName("流式上「备模型接住」也带工具：换了一家不是退化成只能说话")
    void streamingFallbackAlsoCarriesTools() {
        List<Prompt> backupPrompts = new CopyOnWriteArrayList<>();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> Flux.error(new IllegalStateException("首块前挂了")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> answer(""),
                        prompt -> {
                            backupPrompts.add(prompt);
                            return Flux.just(answer("备答"));
                        }))));

        ChatOptions incoming = DeepSeekChatOptions.builder()
                .model("deepseek-chat")
                .toolCallbacks(List.of(TOOL))
                .toolContext(Map.of("claw4j.principal", "guest"))
                .build();
        routing.stream(prompt(incoming)).blockLast();

        assertThat(backupPrompts).hasSize(1);
        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) backupPrompts.getFirst().getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolContext()).containsEntry("claw4j.principal", "guest");
    }

    private static Prompt prompt(ChatOptions options) {
        return new Prompt(List.of(new UserMessage("上周华东成交额")), options);
    }

    private static String textOf(ChatResponse response) {
        return response.getResult() == null ? "" : response.getResult().getOutput().getText();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private static ChatModel model(ChatOptions options,
                                   Function<Prompt, ChatResponse> call,
                                   Function<Prompt, Flux<ChatResponse>> stream) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return call.apply(prompt);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return stream.apply(prompt);
            }

            @Override
            public ChatOptions getOptions() {
                return options;
            }
        };
    }
}
