package io.github.claw4j.model.routing;

import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.failover.FailoverReason;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 准入闸：这个端点 + 账号 + 模型什么时候能再用。
 *
 * <p>照 OpenClaw（`docs/concepts/model-failover.md:31-45`、`src/agents/model-fallback-rate-limit.ts:40-63`）。
 * 它不是限流器——"每秒最多几个请求"是边界层的事；它只回答"这个组合刚被限过，什么时候再试"，
 * 让新请求绕开已经饱和的端点，而不是排队去打。
 *
 * <p>三条规矩：
 * <ul>
 *   <li>只有配额 / 限流类失败才开闸；5xx 归冷却，不在闸里（一次 5xx 排除这个端点整一分钟太狠）；
 *   <li>状态是"最后一次重试底线 + 1 分钟"过期。这个 1 分钟只有文档依据，代码里没有常量可抄
 *       （ADR-0035 记了这一条）；
 *   <li>排序语义：<b>可用优先，饱和的排最后并按恢复时间升序</b>，不是全局按时间排。
 * </ul>
 */
public final class RouteGate {

    /** 闸的过期时长：最后一次重试底线之后这么久。 */
    static final Duration TTL = Duration.ofMinutes(1);

    /**
     * 闸的键：端点 + 账号 + 模型。
     *
     * @param endpoint 端点（{@code base-url}）
     * @param account  账号，先取供应商 id（多钥匙细分留给后续票）
     * @param model    模型引用
     */
    public record Key(String endpoint, String account, String model) {
    }

    /** 一条饱和记录：最早什么时候能再用。 */
    private record Saturation(Instant retryAt, FailoverReason reason) {
    }

    private final Map<Key, Saturation> saturated = new ConcurrentHashMap<>();

    private final Clock clock;

    public RouteGate() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock 时钟（测试用固定时钟）
     */
    public RouteGate(Clock clock) {
        this.clock = clock;
    }

    /**
     * 按失败原因决定要不要开闸。只有配额 / 限流会开。
     *
     * @param key      闸的键
     * @param reason   失败原因
     * @param retryAt  最早什么时候能再用（供应商给的，或冷却到期时刻）
     * @return 真的开了闸时为 true
     */
    public boolean openOnFailure(Key key, FailoverReason reason, Instant retryAt) {
        if (reason != FailoverReason.rate_limit && reason != FailoverReason.billing) {
            return false;
        }
        if (retryAt == null) {
            return false;
        }
        saturated.merge(key, new Saturation(retryAt, reason), (old, fresh) ->
                old.retryAt().isAfter(fresh.retryAt()) ? old : fresh);
        return true;
    }

    /**
     * 这个键现在饱和到什么时候。过期的记录当场清掉。
     *
     * @param key 闸的键
     * @return 还在饱和中时返回最早可用时刻
     */
    public Optional<Instant> retryAt(Key key) {
        Saturation entry = saturated.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.retryAt().plus(TTL).isAfter(clock.instant())) {
            saturated.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.retryAt());
    }

    /**
     * 按"可用优先、饱和按恢复时间排最后"重排候选。
     *
     * <p>稳定排序：同组内保持原顺序，所以配置顺序仍然是可用组里的偏好顺序。
     *
     * @param candidates 候选
     * @param keyOf      每个候选对应的闸键
     * @param <T>        候选类型
     * @return 重排后的新列表，长度与入参相同
     */
    public <T> List<T> orderByAvailability(List<T> candidates, Function<T, Key> keyOf) {
        List<T> available = new ArrayList<>();
        List<T> waiting = new ArrayList<>();
        for (T candidate : candidates) {
            (retryAt(keyOf.apply(candidate)).isPresent() ? waiting : available).add(candidate);
        }
        waiting.sort(Comparator.comparing(candidate -> retryAt(keyOf.apply(candidate)).orElse(Instant.MAX)));
        available.addAll(waiting);
        return available;
    }

    /**
     * 成功一次不延长闸：只有新的失败底线才更新到期时间。
     *
     * @param key 闸的键
     */
    public void recordSuccess(Key key) {
        // 刻意什么都不做。写在这里是为了让"成功不续期"成为一条读得到的规则，
        // 而不是"忘了写"——每成功一次续期会让饱和端点永远出不来。
    }

    /**
     * 全部饱和时，最早恢复的是哪个时刻；没有任何记录时为空。
     *
     * @param keys 闸的键
     * @return 最早恢复时刻
     */
    public Optional<Instant> soonestRecovery(List<Key> keys) {
        return keys.stream()
                .map(this::retryAt)
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder());
    }

    /**
     * 给一个模型引用造闸键。
     *
     * @param endpoint 端点
     * @param account  账号（供应商 id）
     * @param ref      模型引用
     * @return 闸的键
     */
    public static Key keyOf(String endpoint, String account, ModelRef ref) {
        return new Key(endpoint, account, ref.toString());
    }
}
