package io.github.claw4j.model.routing;

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
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由语义：主先上、失败换谁、换了之后参数还认不认、流式在哪一块之后不再换人。
 *
 * <p>这一组跟着 {@link RoutingChatModel} 搬进模型层（ADR-0035）。留在 agent 的是端到端那一份
 * （"降级之后工具真的跑得起来"），这里只钉路由自己的判断——两者视角不同，不是复述。
 */
class ModelRouteTest {

    @Test
    @DisplayName("主模型正常时备模型一次都不碰；路由顺序就是主在前")
    void primarySuccessDoesNotTouchBackup() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer("主家答的"),
                        prompt -> Flux.just(answer("主家答的")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return answer("备家答的");
                        },
                        prompt -> Flux.just(answer("备家答的"))))));

        assertThat(textOf(routing.call(prompt(DeepSeekChatOptions.builder().build()))))
                .isEqualTo("主家答的");
        assertThat(backupCalls).hasValue(0);
        assertThat(routing.routeOrder()).containsExactly("primary", "backup");
    }

    @Test
    @DisplayName("主抛可重试错误：备接住，并且拿到的是它自己那一型 options（不是主的那一型）")
    void fallbackSwapsOptionsToTheRouteOwnType() {
        List<ChatOptions> backupOptions = new java.util.ArrayList<>();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw new TransientAiException("429");
                        },
                        prompt -> Flux.error(new TransientAiException("429")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            backupOptions.add(prompt.getOptions());
                            return answer("备家答的");
                        },
                        prompt -> Flux.just(answer("备家答的"))))));

        // 调用方给的是 DeepSeek 的 options；备家是 OpenAI 兼容那一型，直接传会 ClassCastException
        assertThat(textOf(routing.call(prompt(DeepSeekChatOptions.builder()
                .temperature(0.3).build()))))
                .isEqualTo("备家答的");
        assertThat(backupOptions).singleElement()
                .isInstanceOf(OpenAiChatOptions.class);
    }

    @Test
    @DisplayName("降级时工具回调与工具上下文跟着搬到备家：换一家不是退化成只能说话")
    void fallbackCarriesToolsAndContext() {
        ToolCallback tool = stubTool("querySales");
        List<ChatOptions> seen = new java.util.ArrayList<>();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw new TransientAiException("503");
                        },
                        prompt -> Flux.error(new TransientAiException("503")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            seen.add(prompt.getOptions());
                            return answer("备家答的");
                        },
                        prompt -> Flux.just(answer("备家答的"))))));

        routing.call(new Prompt(List.of(new UserMessage("上周成交额")),
                DeepSeekChatOptions.builder()
                        .toolCallbacks(List.of(tool))
                        .toolContext(Map.of("claw4j.principal", "guest"))
                        .build()));

        assertThat(seen).singleElement().satisfies(options -> {
            var toolOptions = (org.springframework.ai.model.tool.ToolCallingChatOptions) options;
            assertThat(toolOptions.getToolCallbacks())
                    .as("漏了它，备家会答「我无法访问数据库」")
                    .extracting(callback -> callback.getToolDefinition().name())
                    .containsExactly("querySales");
            assertThat(toolOptions.getToolContext()).containsEntry("claw4j.principal", "guest");
        });
    }

    @Test
    @DisplayName("两路都失败：抛 ModelRoutingException，每条失败挂在 suppressed 上")
    void allRoutesFailingThrowsInsteadOfReturningEmpty() {
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw new TransientAiException("503");
                        },
                        prompt -> Flux.empty())),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            throw new TransientAiException("503");
                        },
                        prompt -> Flux.empty()))));

        assertThatThrownBy(() -> routing.call(prompt(DeepSeekChatOptions.builder().build())))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("primary")
                .hasMessageContaining("backup")
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(2));
    }

    @Test
    @DisplayName("空路由在构造时就失败：没有候选的「降级」没有意义")
    void emptyRoutesAreRejected() {
        assertThatThrownBy(() -> new RoutingChatModel(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("不可重试的错误直接点名报出，备家一次都不碰")
    void unauthorizedDoesNotFallBack() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel routing = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            throw statusFailure(401, "invalid api key");
                        },
                        prompt -> Flux.empty())),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return answer("备家答的");
                        },
                        prompt -> Flux.empty()))));

        assertThatThrownBy(() -> routing.call(prompt(DeepSeekChatOptions.builder().build())))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("primary");
        assertThat(backupCalls)
                .as("key 失效时去撞第二家，只会把一次确定的错误拖成两次网络往返")
                .hasValue(0);
    }

    @Test
    @DisplayName("解析不出来：同一家不重试，只换家")
    void unparseableOutputDoesNotRetryTheSameModel() {
        AtomicInteger attempts = new AtomicInteger();
        RoutingChatModel single = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> {
                            attempts.incrementAndGet();
                            throw new IllegalStateException("Conversion from JSON to java.util.Map failed",
                                    new tools.jackson.core.exc.StreamReadException("Unexpected character ('专')"));
                        },
                        prompt -> Flux.empty()))));

        assertThatThrownBy(() -> single.call(prompt(DeepSeekChatOptions.builder().build())))
                .isInstanceOf(ModelRoutingException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("流式首块之前失败才换人；已经吐过一块之后不再换（会重复）")
    void streamSwitchesOnlyBeforeTheFirstChunk() {
        AtomicInteger backupCalls = new AtomicInteger();
        RoutingChatModel beforeFirstChunk = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer("不会走到"),
                        prompt -> Flux.error(new TransientAiException("503")))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> answer("不会走到"),
                        prompt -> Flux.just(answer("备家的块"))))));

        assertThat(beforeFirstChunk.stream(prompt(DeepSeekChatOptions.builder().build()))
                .map(ModelRouteTest::textOf)
                .collectList()
                .block())
                .containsExactly("备家的块");

        RoutingChatModel afterFirstChunk = new RoutingChatModel(List.of(
                new RoutingChatModel.Route("primary", model(
                        DeepSeekChatOptions.builder().build(),
                        prompt -> answer("不会走到"),
                        prompt -> Flux.concat(
                                Flux.just(answer("前半截")),
                                Flux.error(new TransientAiException("503"))))),
                new RoutingChatModel.Route("backup", model(
                        OpenAiChatOptions.builder().build(),
                        prompt -> {
                            backupCalls.incrementAndGet();
                            return answer("不会走到");
                        },
                        prompt -> Flux.just(answer("备家的块"))))));

        assertThatThrownBy(() -> afterFirstChunk.stream(prompt(DeepSeekChatOptions.builder().build()))
                .collectList()
                .block())
                .isInstanceOf(TransientAiException.class);
        assertThat(backupCalls).hasValue(0);
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

    private static com.openai.errors.OpenAIServiceException statusFailure(int statusCode, String message) {
        return com.openai.errors.UnexpectedStatusCodeException.builder()
                .statusCode(statusCode)
                .headers(com.openai.core.http.Headers.builder().build())
                .cause(new IllegalStateException(message))
                .build();
    }

    private static ToolCallback stubTool(String name) {
        return new ToolCallback() {

            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name)
                    .description("测试工具")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "128400";
            }
        };
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
