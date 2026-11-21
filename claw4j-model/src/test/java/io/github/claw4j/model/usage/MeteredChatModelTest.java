package io.github.claw4j.model.usage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型调用的账：次数、耗时、token，以及"拿不到就不记"。
 *
 * <p>这些断言跟着 {@link MeteredChatModel} 搬进模型层（ADR-0035）——它包在每条 route 上，
 * 是模型层的用量出口，不是 agent 的观测插件。
 */
class MeteredChatModelTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("同步调用把 prompt 与 completion 分开记：成本要能拆开看")
    void countsTokensSeparately() {
        MeteredChatModel model = new MeteredChatModel(
                model(prompt -> withUsage(12, 34)), "deepseek", registry);

        model.call(new Prompt("上周华东成交额"));

        assertThat(counter(MeteredChatModel.TOKENS, "kind", "prompt")).isEqualTo(12);
        assertThat(counter(MeteredChatModel.TOKENS, "kind", "completion")).isEqualTo(34);
        assertThat(counter(MeteredChatModel.CALLS, "outcome", "ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("流式只认最后一帧：每帧都带累计 usage 时，逐帧累加会翻三倍")
    void streamCountsUsageOnceFromTheLastChunk() {
        MeteredChatModel model = new MeteredChatModel(
                model(prompt -> withUsage(12, 34),
                        prompt -> Flux.just(withUsage(12, 34), withUsage(12, 34), withUsage(12, 34))),
                "deepseek", registry);

        model.stream(new Prompt("上周华东成交额")).blockLast();

        assertThat(counter(MeteredChatModel.TOKENS, "kind", "prompt")).isEqualTo(12);
        assertThat(counter(MeteredChatModel.CALLS, "outcome", "ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("拿不到 usage 就不记 token：不拿估算值冒充真实值")
    void missingUsageDoesNotFakeATokenCount() {
        MeteredChatModel model = new MeteredChatModel(
                model(prompt -> withoutUsage("答")), "deepseek", registry);

        model.call(new Prompt("上周华东成交额"));

        assertThat(registry.find(MeteredChatModel.TOKENS).counter()).isNull();
        assertThat(counter(MeteredChatModel.CALLS, "outcome", "ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("失败记在 outcome=error 上，并带上 reason：冷却与闸要按原因决定何时再用")
    void failureCarriesFailoverReason() {
        MeteredChatModel model = new MeteredChatModel(
                model(prompt -> {
                    throw new org.springframework.ai.retry.TransientAiException("429 too many requests");
                }), "deepseek", registry);

        assertThatThrownBy(() -> model.call(new Prompt("上周华东成交额")))
                .isInstanceOf(org.springframework.ai.retry.TransientAiException.class);
        assertThat(counter(MeteredChatModel.CALLS, "outcome", "error")).isEqualTo(1);
        assertThat(registry.get(MeteredChatModel.CALLS)
                .tag("route", "deepseek")
                .tag("outcome", "error")
                .tag("reason", "rate_limit")
                .counter()
                .count())
                .as("只有 route/outcome 的话，冷却没有输入")
                .isEqualTo(1.0);
        assertThat(registry.find(MeteredChatModel.TOKENS).counter()).isNull();
    }

    private double counter(String name, String tagKey, String tagValue) {
        return registry.get(name)
                .tag("route", "deepseek")
                .tag(tagKey, tagValue)
                .counter()
                .count();
    }

    private static ChatResponse withUsage(long promptTokens, long completionTokens) {
        return ChatResponse.builder()
                .generations(java.util.List.of(new Generation(new AssistantMessage("答"))))
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage((int) promptTokens, (int) completionTokens))
                        .build())
                .build();
    }

    private static ChatResponse withoutUsage(String text) {
        return ChatResponse.builder()
                .generations(java.util.List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().build())
                .build();
    }

    private static ChatModel model(Function<Prompt, ChatResponse> call) {
        return model(call, prompt -> Flux.just(call.apply(prompt)));
    }

    private static ChatModel model(Function<Prompt, ChatResponse> call,
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
                return ChatOptions.builder().build();
            }
        };
    }
}
