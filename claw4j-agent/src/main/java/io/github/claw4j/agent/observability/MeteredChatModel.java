package io.github.claw4j.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 给一条模型路由加计量：调了几次、花了多少 token、用了多久。
 *
 * <p><b>为什么在 {@code ChatModel} 这一层，而不是在 {@code AgentPort} 上</b>：
 * 一轮对话里工具循环每转一圈就调一次模型，一次提问可能是 3~6 次调用。
 * 只在最外层记一次，看到的是"一次提问"，而账单按"一次调用"结。成本恰恰在中间那几轮。
 *
 * <p><b>为什么包在每条 route 上，而不是包住整个 {@code RoutingChatModel}</b>：
 * {@code route} 这个标签要能回答"这次是主模型答的，还是降级之后答的"。
 * 包在外面只知道"这次请求成功了"；要分清就得在 {@code RoutingChatModel} 里再埋一个计数器，
 * 那是第二处状态，而这里一个标签就够了——降级次数 = 另一条 route 的调用数。
 *
 * <p><b>流式为什么只认最后一帧</b>：多数供应商只在末帧带 usage，前面的帧里是空。
 * 逐帧累加的结果是把总量记成 0（每帧都没有），或者重复累加同一个总数。
 * 这里只在 {@code doOnComplete} 时看一次最后一帧。
 *
 * <p><b>拿不到 usage 就不记 token</b>：假模型、部分兼容实现、某些流式响应都不给 usage。
 * 这时候记 0 与记一个估算值都是在说假话——前者让人以为不花钱，后者让成本口径不可信。
 * {@code calls} 与 {@code duration} 照记，它们是真实发生过的。
 */
public class MeteredChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(MeteredChatModel.class);

    /** 指标前缀。统一在这里，改名只改一处。 */
    public static final String TOKENS = "claw4j.model.tokens";
    public static final String CALLS = "claw4j.model.calls";
    public static final String DURATION = "claw4j.model.duration";

    private final ChatModel delegate;
    private final String route;
    private final MeterRegistry registry;

    public MeteredChatModel(ChatModel delegate, String route, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.route = Objects.requireNonNull(route, "route");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = delegate.call(prompt);
            calls("ok");
            recordTokens(response);
            return response;
        }
        catch (RuntimeException failure) {
            calls("error");
            throw failure;
        }
        finally {
            duration(false, System.nanoTime() - startedAt);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long startedAt = System.nanoTime();
        AtomicReference<ChatResponse> lastChunk = new AtomicReference<>();
        return delegate.stream(prompt)
                .doOnNext(lastChunk::set)
                .doOnComplete(() -> {
                    calls("ok");
                    recordTokens(lastChunk.get());
                })
                .doOnError(failure -> calls("error"))
                // doFinally 而不是 doOnComplete：客户端中断（飞书那边 cancel）也要记耗时，
                // 否则被掐断的那些请求在耗时分布里整条消失，看着像是没发生过。
                .doFinally(signal -> duration(true, System.nanoTime() - startedAt));
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    private void calls(String outcome) {
        Counter.builder(CALLS)
                .description("模型调用次数")
                .tag("route", route)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    private void duration(boolean stream, long nanos) {
        Timer.builder(DURATION)
                .description("模型调用耗时")
                .tag("route", route)
                .tag("stream", Boolean.toString(stream))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    private void recordTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            log.debug("模型 {} 这次响应没有 usage，token 不记", route);
            return;
        }
        tokens("prompt", usage.getPromptTokens());
        tokens("completion", usage.getCompletionTokens());
    }

    private void tokens(String kind, Integer amount) {
        // 0 也不记：0 会把"没拿到"和"真的没用 token"混成一个数。
        if (amount == null || amount <= 0) {
            return;
        }
        Counter.builder(TOKENS)
                .description("模型消耗的 token 数")
                .tag("route", route)
                .tag("kind", kind)
                .register(registry)
                .increment(amount);
    }
}
