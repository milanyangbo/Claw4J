package io.github.claw4j.model.routing;

import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.failover.CooldownStore;
import io.github.claw4j.model.failover.FailoverClassifier;
import io.github.claw4j.model.failover.FailoverReason;
import io.github.claw4j.model.failover.ProbeThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 候选链 + 逐候选尝试：替掉原来的 {@code RoutingChatModel}。
 *
 * <p>候选链三段照 OpenClaw（`model-fallback-candidates.ts:295-409`）：
 * {@code requested → configured-fallback（按序、去重）→ configured-primary（末尾兜底）}。
 * 第三段看着多余（requested 通常就是 primary），但它解决一个真问题：所有 fallback 都写得跟主家
 * 同源时，至少还留一次回主家的机会。
 *
 * <p>每个候选执行前查两样：冷却（票 07）与准入闸（票 09）。被跳过也进尝试列表——"没试"与
 * "试了失败"在排查时是两件事。
 *
 * <p>上下文溢出时只重排失败位置之后的候选（{@link ContextFit}）：够得着的在前，不丢候选。
 */
public final class ModelRouter implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** 候选的来源。探测只探 requested；失败摘要按它归类。 */
    public enum Origin {

        /** 这次点名的那个（本仓目前是主模型）。 */
        REQUESTED,

        /** 配置里的备选。 */
        CONFIGURED_FALLBACK,

        /** 配置里的主模型，末尾再兜一次（去重后最多出现一次）。 */
        CONFIGURED_PRIMARY
    }

    /**
     * 一个候选。
     *
     * @param ref    引用
     * @param model  真正干活的模型
     * @param origin 来源
     */
    public record Candidate(ModelRef ref, ChatModel model, Origin origin) {

        public Candidate {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /** 供应商给的 {@code Retry-After}：秒数或 "in 6s" 两种写法。 */
    private static final Pattern RETRY_AFTER = Pattern.compile(
            "(?:retry[- ]after[:= ]*|try again in )\\s*(\\d{1,4})\\s*s?", Pattern.CASE_INSENSITIVE);

    private final List<Candidate> chain;

    private final Map<ModelRef, Integer> capacities;

    private final Map<ModelRef, String> endpoints;

    private final CooldownStore cooldowns;

    private final RouteGate gate;

    private final ProbeThrottle probes;

    /**
     * @param chain      候选链，第一个是 requested
     * @param capacities 每个候选的有效上下文容量
     * @param endpoints  每个候选的端点（闸的键要用）
     * @param cooldowns  冷却表
     * @param gate       准入闸
     * @param probes     探测节流
     */
    public ModelRouter(List<Candidate> chain, Map<ModelRef, Integer> capacities,
                       Map<ModelRef, String> endpoints, CooldownStore cooldowns,
                       RouteGate gate, ProbeThrottle probes) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("至少要有一个候选，空链没有意义");
        }
        this.chain = List.copyOf(chain);
        this.capacities = Map.copyOf(capacities);
        this.endpoints = Map.copyOf(endpoints);
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.probes = Objects.requireNonNull(probes, "probes");
    }

    /**
     * 拼三段候选链：requested → fallbacks（去重）→ primary（末尾兜底，已在链上就不加）。
     *
     * @param requested 点名的候选
     * @param fallbacks 配置里的备选
     * @param primary   配置里的主模型候选
     * @return 链
     */
    public static List<Candidate> chain(Candidate requested, List<Candidate> fallbacks, Candidate primary) {
        Map<ModelRef, Candidate> ordered = new LinkedHashMap<>();
        ordered.put(requested.ref(), requested);
        for (Candidate fallback : fallbacks) {
            ordered.putIfAbsent(fallback.ref(),
                    new Candidate(fallback.ref(), fallback.model(), Origin.CONFIGURED_FALLBACK));
        }
        ordered.putIfAbsent(primary.ref(), new Candidate(primary.ref(), primary.model(), Origin.CONFIGURED_PRIMARY));
        return List.copyOf(ordered.values());
    }

    /** 路由顺序，用于启动日志与断言。 */
    public List<String> routeOrder() {
        return chain.stream().map(candidate -> candidate.ref().toString()).toList();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Candidate> order = new ArrayList<>(chain);
        List<ModelFallbackExhaustedException.Attempt> attempts = new ArrayList<>();
        RuntimeException lastFailure = null;
        int index = 0;
        while (index < order.size()) {
            Candidate candidate = order.get(index);
            String key = candidate.ref().toString();
            Optional<CooldownStore.Cooldown> cooldown = cooldowns.cooldownOf(key);
            if (cooldown.isPresent()
                    && !probes.mayProbe(key, cooldown.get().until(), order.size() > 1)) {
                attempts.add(new ModelFallbackExhaustedException.Attempt(key, cooldown.get().reason(),
                        "skipped"));
                index++;
                continue;
            }
            try {
                ChatResponse response = candidate.model().call(optionsFor(prompt, candidate));
                cooldowns.clear(key);
                return response;
            }
            catch (RuntimeException failure) {
                lastFailure = failure;
                FailoverReason reason = FailoverClassifier.classify(failure);
                attempts.add(new ModelFallbackExhaustedException.Attempt(key, reason, "failed"));
                recordFailure(candidate, reason, failure);
                log.warn("模型 {} 调用失败（{}），换下一个（若有）", key, reason, failure);
                if (!reason.worthTryingAnother()) {
                    throw exhausted(attempts, key, failure);
                }
                if (reason == FailoverReason.context_overflow) {
                    order = reorderTail(order, index, failure);
                }
                index++;
            }
        }
        throw exhausted(attempts, chain.getFirst().ref().toString(), lastFailure);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return streamFrom(prompt, 0);
    }

    private Flux<ChatResponse> streamFrom(Prompt prompt, int index) {
        Candidate candidate = chain.get(index);
        // "已经吐出过东西"是流式的降级开关：吐过就不再换人，否则客户端会收到重复内容。
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);
        return candidate.model().stream(optionsFor(prompt, candidate))
                .doOnNext(chunk -> started.set(true))
                .onErrorResume(error -> {
                    FailoverReason reason = FailoverClassifier.classify(error);
                    recordFailure(candidate, reason, error);
                    int next = index + 1;
                    if (started.get() || !reason.worthTryingAnother() || next >= chain.size()) {
                        return Flux.error(error);
                    }
                    log.warn("模型 {} 流式失败（{}，未吐内容），换下一个", candidate.ref(), reason, error);
                    return streamFrom(prompt, next);
                });
    }

    private void recordFailure(Candidate candidate, FailoverReason reason, Throwable failure) {
        String key = candidate.ref().toString();
        cooldowns.recordFailure(key, reason, retryAfterOf(failure));
        cooldowns.cooldownOf(key).ifPresent(cooldown ->
                gate.openOnFailure(gateKey(candidate), reason, cooldown.until()));
    }

    private List<Candidate> reorderTail(List<Candidate> order, int failedIndex, Throwable failure) {
        int failedCapacity = capacities.getOrDefault(order.get(failedIndex).ref(), 0);
        int required = ContextFit.requiredTokens(failure.getMessage(), failedCapacity);
        List<Candidate> tail = order.subList(failedIndex + 1, order.size());
        if (tail.isEmpty()) {
            return order;
        }
        List<Candidate> reordered = ContextFit.preferFitting(tail, required,
                candidate -> capacities.getOrDefault(candidate.ref(), 0));
        List<Candidate> merged = new ArrayList<>(order.subList(0, failedIndex + 1));
        merged.addAll(reordered);
        return merged;
    }

    private ModelFallbackExhaustedException exhausted(
            List<ModelFallbackExhaustedException.Attempt> attempts, String last, RuntimeException cause) {
        Instant soonest = attempts.stream()
                .map(attempt -> cooldowns.cooldownOf(attempt.ref()))
                .flatMap(Optional::stream)
                .map(CooldownStore.Cooldown::until)
                .min(Instant::compareTo)
                .orElse(null);
        String summary = attempts.stream()
                .map(attempt -> attempt.ref() + "=" + attempt.reason() + "(" + attempt.status() + ")")
                .toList()
                .toString();
        return new ModelFallbackExhaustedException(
                "所有模型都调用失败，尝试：" + summary
                        + (soonest == null ? "" : "；最早 " + soonest + " 可再试"), attempts, soonest, cause);
    }

    private RouteGate.Key gateKey(Candidate candidate) {
        return RouteGate.keyOf(endpoints.getOrDefault(candidate.ref(), ""),
                candidate.ref().provider(), candidate.ref());
    }

    private static Duration retryAfterOf(Throwable failure) {
        for (Throwable current = failure; current != null; current = next(current)) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            Matcher matcher = RETRY_AFTER.matcher(message);
            if (matcher.find()) {
                return Duration.ofSeconds(Long.parseLong(matcher.group(1)));
            }
        }
        return null;
    }

    private static Throwable next(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == failure ? null : cause;
    }

    @Override
    public ChatOptions getOptions() {
        return chain.getFirst().model().getOptions();
    }

    /**
     * 把 Prompt 上的参数换成"这一家认识的类型"。
     *
     * <p>必须换：{@code ChatOptions} 的每家实现互不兼容，厂商的 ChatModel 会直接强转成自己那一型。
     * 主是通义、备是 DeepSeek 时，降级会把一种 options 递给另一种模型，当场 {@code ClassCastException}。
     *
     * @param prompt    调用方给的 Prompt
     * @param candidate 这一次要用的候选
     * @return 选项类型与该候选匹配的 Prompt
     */
    private static Prompt optionsFor(Prompt prompt, Candidate candidate) {
        ChatOptions own = candidate.model().getOptions();
        ChatOptions incoming = prompt.getOptions();
        if (incoming == null) {
            return new Prompt(prompt.getInstructions(), own);
        }
        if (own.getClass().isInstance(incoming)) {
            return prompt;
        }
        ChatOptions.Builder<?> builder = own.mutate()
                .temperature(incoming.getTemperature())
                .topP(incoming.getTopP())
                .topK(incoming.getTopK())
                .maxTokens(incoming.getMaxTokens())
                .frequencyPenalty(incoming.getFrequencyPenalty())
                .presencePenalty(incoming.getPresencePenalty())
                .stopSequences(incoming.getStopSequences())
                .model(own.getModel());
        copyTools(builder, incoming);
        return new Prompt(prompt.getInstructions(), builder.build());
    }

    /**
     * 工具回调与工具上下文必须跟着一起搬：漏掉它的表现不是报错，是降级后模型说"我无法访问数据库"。
     *
     * @param builder  这一家的选项 Builder
     * @param incoming 调用方给的选项
     */
    private static void copyTools(ChatOptions.Builder<?> builder, ChatOptions incoming) {
        if (!(builder instanceof ToolCallingChatOptions.Builder<?> target)
                || !(incoming instanceof ToolCallingChatOptions source)) {
            return;
        }
        List<ToolCallback> callbacks = source.getToolCallbacks();
        if (callbacks != null && !callbacks.isEmpty()) {
            target.toolCallbacks(callbacks);
        }
        Map<String, Object> context = source.getToolContext();
        if (context != null && !context.isEmpty()) {
            target.toolContext(context);
        }
    }

    /** 供配置层使用的端点映射构造器：端点缺失时用空串（闸只做键的一部分）。 */
    public static Function<ModelRef, String> endpointsOf(Map<ModelRef, String> endpoints) {
        return ref -> endpoints.getOrDefault(ref, "");
    }
}
