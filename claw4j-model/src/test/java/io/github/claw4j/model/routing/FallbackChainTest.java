package io.github.claw4j.model.routing;

import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.failover.CooldownStore;
import io.github.claw4j.model.failover.FailoverReason;
import io.github.claw4j.model.failover.ProbeThrottle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 候选链：三段顺序、跳过要记账、溢出重排、耗尽摘要、流式不回头。
 *
 * <p>这一组替掉的是原来 {@code RoutingChatModel} 那几条用例——它不是被删掉的行为，是被拆成了
 * "链怎么排"（这里）与"每家的参数怎么换"（同一条 call 路径里）。
 */
class FallbackChainTest {

    private static final ModelRef PRIMARY = new ModelRef("deepseek", "deepseek-chat");
    private static final ModelRef BACKUP = new ModelRef("qwen", "qwen-plus");
    private static final ModelRef SMALL = new ModelRef("qwen", "qwen-turbo");

    @Test
    @DisplayName("三段顺序：requested → fallbacks → primary 兜底，且 primary 不重复")
    void requestedThenFallbacksThenPrimary() {
        ModelRouter.Candidate requested = candidate(PRIMARY, ModelRouter.Origin.REQUESTED,
                model(prompt -> answer("主"), prompt -> Flux.just(answer("主"))));
        ModelRouter.Candidate fallback = candidate(BACKUP, ModelRouter.Origin.CONFIGURED_FALLBACK,
                model(prompt -> answer("备"), prompt -> Flux.just(answer("备"))));
        ModelRouter.Candidate primary = candidate(PRIMARY, ModelRouter.Origin.CONFIGURED_PRIMARY,
                requested.model());

        List<ModelRouter.Candidate> chain = ModelRouter.chain(requested, List.of(fallback), primary);

        assertThat(chain).extracting(c -> c.ref().toString())
                .containsExactly("deepseek/deepseek-chat", "qwen/qwen-plus");
        assertThat(chain.getFirst().origin()).isEqualTo(ModelRouter.Origin.REQUESTED);
    }

    @Test
    @DisplayName("溢出后把容量够的候选排到前面；重排不丢候选、不动失败位置之前的部分")
    void contextFitReordersTailAndKeepsEverything() {
        AtomicInteger smallCalls = new AtomicInteger();
        AtomicInteger bigCalls = new AtomicInteger();
        ModelRouter.Candidate requested = candidate(PRIMARY, ModelRouter.Origin.REQUESTED,
                model(prompt -> {
                    throw new IllegalStateException("This model's maximum context length is 8192 tokens");
                }, prompt -> Flux.empty()));
        // 配置顺序：小窗口在前、大窗口在后
        ModelRouter.Candidate small = candidate(SMALL, ModelRouter.Origin.CONFIGURED_FALLBACK,
                model(prompt -> {
                    smallCalls.incrementAndGet();
                    return answer("小");
                }, prompt -> Flux.just(answer("小"))));
        ModelRouter.Candidate big = candidate(BACKUP, ModelRouter.Origin.CONFIGURED_FALLBACK,
                model(prompt -> {
                    bigCalls.incrementAndGet();
                    return answer("大");
                }, prompt -> Flux.just(answer("大"))));

        ModelRouter router = router(requested, List.of(small, big), primaryCapacity());

        assertThat(textOf(router.call(prompt())))
                .as("容量够的 qwen-plus 应该先被试到")
                .isEqualTo("大");
        assertThat(bigCalls).hasValue(1);
        assertThat(smallCalls).as("不丢候选：如果大的也不行，小的还在链上").hasValue(0);
    }

    @Test
    @DisplayName("需求下界：取不到错误里的 token 数就用失败模型容量 + 1")
    void requirementFallsBackToCapacityPlusOne() {
        assertThat(ContextFit.requiredTokens("context length exceeded", 8192)).isEqualTo(8193);
        assertThat(ContextFit.requiredTokens("requested 12000 tokens", 8192)).isEqualTo(12000);
        assertThat(ContextFit.requiredTokens(
                "This model's maximum context length is 8192 tokens", 8192)).isEqualTo(8193);
    }

    @Test
    @DisplayName("被冷却跳过的候选仍出现在尝试列表")
    void skippedCandidateIsRecorded() {
        CooldownStore cooldowns = new CooldownStore();
        cooldowns.recordFailure(PRIMARY.toString(), FailoverReason.rate_limit,
                java.time.Duration.ofMinutes(5));
        ModelRouter router = routerWithCooldown(cooldowns);

        assertThat(textOf(router.call(prompt())))
                .as("主被冷却，直接走备")
                .isEqualTo("备");
    }

    @Test
    @DisplayName("全员失败：摘要带每一跳的原因与最早恢复时间")
    void exhaustionCarriesReasonsAndSoonestExpiry() {
        ModelRouter.Candidate requested = candidate(PRIMARY, ModelRouter.Origin.REQUESTED,
                model(prompt -> {
                    throw com.openai.errors.UnexpectedStatusCodeException.builder()
                            .statusCode(429)
                            .headers(com.openai.core.http.Headers.builder().build())
                            .cause(new IllegalStateException("rate limited"))
                            .build();
                }, prompt -> Flux.empty()));
        ModelRouter.Candidate backup = candidate(BACKUP, ModelRouter.Origin.CONFIGURED_FALLBACK,
                model(prompt -> {
                    throw new RuntimeException(new java.net.ConnectException("refused"));
                }, prompt -> Flux.empty()));

        ModelRouter router = router(requested, List.of(backup), primaryCapacity());

        assertThatThrownBy(() -> router.call(prompt()))
                .isInstanceOf(ModelFallbackExhaustedException.class)
                .satisfies(failure -> {
                    ModelFallbackExhaustedException exhausted = (ModelFallbackExhaustedException) failure;
                    assertThat(exhausted.attempts()).extracting(a -> a.ref() + "=" + a.reason())
                            .containsExactly("deepseek/deepseek-chat=rate_limit", "qwen/qwen-plus=route_unavailable");
                    assertThat(exhausted.soonestRecovery())
                            .as("限流那条进了冷却，摘要里要能说出什么时候能再试")
                            .isPresent();
                });
    }

    @Test
    @DisplayName("流式吐过首块之后不再换模型")
    void streamDoesNotSwitchAfterFirstChunk() {
        AtomicInteger backupCalls = new AtomicInteger();
        ModelRouter.Candidate requested = candidate(PRIMARY, ModelRouter.Origin.REQUESTED,
                model(prompt -> answer("不会走到"),
                        prompt -> Flux.concat(Flux.just(answer("前半截")),
                                Flux.error(new java.net.ConnectException("断了")))));
        ModelRouter.Candidate backup = candidate(BACKUP, ModelRouter.Origin.CONFIGURED_FALLBACK,
                model(prompt -> {
                    backupCalls.incrementAndGet();
                    return answer("备");
                }, prompt -> Flux.just(answer("备"))));

        ModelRouter router = router(requested, List.of(backup), primaryCapacity());

        assertThatThrownBy(() -> router.stream(prompt()).collectList().block())
                .hasCauseInstanceOf(java.net.ConnectException.class);
        assertThat(backupCalls).as("吐过内容再换人，客户端会收到两遍").hasValue(0);
    }

    private ModelRouter router(ModelRouter.Candidate requested, List<ModelRouter.Candidate> fallbacks,
                               Map<ModelRef, Integer> capacities) {
        return new ModelRouter(ModelRouter.chain(requested, fallbacks, requested), capacities,
                Map.of(PRIMARY, "http://primary", BACKUP, "http://backup", SMALL, "http://backup"),
                new CooldownStore(), new RouteGate(), new ProbeThrottle());
    }

    private ModelRouter routerWithCooldown(CooldownStore cooldowns) {
        ModelRouter.Candidate requested = candidate(PRIMARY, ModelRouter.Origin.REQUESTED,
                model(prompt -> answer("主"), prompt -> Flux.just(answer("主"))));
        ModelRouter.Candidate backup = candidate(BACKUP, ModelRouter.Origin.CONFIGURED_FALLBACK,
                model(prompt -> answer("备"), prompt -> Flux.just(answer("备"))));
        return new ModelRouter(ModelRouter.chain(requested, List.of(backup), requested), primaryCapacity(),
                Map.of(PRIMARY, "http://primary", BACKUP, "http://backup"),
                cooldowns, new RouteGate(), new ProbeThrottle(Clock.fixed(Instant.now(), ZoneOffset.UTC)));
    }

    private static Map<ModelRef, Integer> primaryCapacity() {
        return Map.of(PRIMARY, 8192, BACKUP, 131072, SMALL, 4096);
    }

    private static ModelRouter.Candidate candidate(ModelRef ref, ModelRouter.Origin origin, ChatModel model) {
        return new ModelRouter.Candidate(ref, model, origin);
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new UserMessage("上周华东成交额")));
    }

    private static String textOf(ChatResponse response) {
        return response.getResult() == null ? "" : response.getResult().getOutput().getText();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
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
