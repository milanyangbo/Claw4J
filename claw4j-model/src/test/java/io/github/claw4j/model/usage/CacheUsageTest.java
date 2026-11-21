package io.github.claw4j.model.usage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存命中计量：两条件条路，取不到就不记。
 *
 * <p>为什么不自己写缓存层：供应商的上下文缓存是自动的（按前缀匹配、计费里体现），
 * 自己再写一层等于跟它打架。这里只让命中率可见。
 */
class CacheUsageTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("OpenAI 兼容侧从 getCacheReadInputTokens() 取到")
    void openAiCompatibleCacheReadRecorded() {
        // OpenAI 适配器把 promptTokensDetails.cachedTokens 填进了 cacheReadInputTokens
        Usage usage = new DefaultUsage(100, 20, 120, null, 80L, null);

        run(usage);

        assertThat(counter("cache-read")).isEqualTo(80);
        assertThat(counter("prompt")).isEqualTo(100);
    }

    @Test
    @DisplayName("DeepSeek 的命中数从 native usage 取到")
    void deepSeekCacheHitFromNativeUsage() {
        DeepSeekApi.Usage nativeUsage = new DeepSeekApi.Usage(100, 20, 120,
                new DeepSeekApi.Usage.PromptTokensDetails(64));
        // DeepSeek 适配器不填 cacheReadInputTokens，它只在 native usage 里
        Usage usage = new DefaultUsage(100, 20, 120, nativeUsage);

        run(usage);

        assertThat(counter("cache-read")).isEqualTo(64);
    }

    @Test
    @DisplayName("两边都没有就不记，不写 0")
    void missingCacheUsageRecordsNothing() {
        run(new DefaultUsage(100, 20, 120));

        assertThat(registry.find(MeteredChatModel.TOKENS).tag("kind", "cache-read").counter())
                .as("0 会把「没拿到」与「真的没命中」混成一个数")
                .isNull();
    }

    private void run(Usage usage) {
        new MeteredChatModel(model(usage), "deepseek/deepseek-chat", "main", registry)
                .call(new Prompt("上周华东成交额"));
    }

    private double counter(String kind) {
        return registry.get(MeteredChatModel.TOKENS)
                .tag("kind", kind)
                .counter()
                .count();
    }

    private static ChatModel model(Usage usage) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return ChatResponse.builder()
                        .generations(java.util.List.of(new Generation(new AssistantMessage("答"))))
                        .metadata(ChatResponseMetadata.builder().usage(usage).build())
                        .build();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().build();
            }
        };
    }
}
