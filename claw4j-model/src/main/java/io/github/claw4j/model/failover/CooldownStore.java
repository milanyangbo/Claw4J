package io.github.claw4j.model.failover;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这条路由下次什么时候能再用。
 *
 * <p>数字照 OpenClaw 的 `src/agents/auth-profiles/usage-failure-state.ts:21-45`：
 * <ul>
 *   <li>普通失败：第 1 次 30s、第 2 次 1min、第 3 次起 5min 封顶；
 *   <li>限流：<b>只认供应商给的时长</b>（{@code retry-after}）——没给就不冷却。自己发明一个 60 秒
 *       只会在供应商已经准备好接的时候继续绕开它；
 *   <li>{@code billing}：10 分钟起，每次翻倍，封顶 24h；
 *   <li>{@code auth_permanent}：10 分钟起，封顶 60min。
 * </ul>
 *
 * <p>计数窗口 24h：一天没再失败就从 1 数起。冷却<b>只延长不缩短</b>——更晚的到期时间可以覆盖，
 * 更早的不行，否则并发失败会把一个长冷却改短。
 *
 * <p>全在进程内、不落盘：重启后要重新撞一次才知道，这是认下来的代价（ADR-0035）。
 */
public final class CooldownStore {

    /** 普通失败的三档。 */
    static final Duration FIRST = Duration.ofSeconds(30);

    static final Duration SECOND = Duration.ofMinutes(1);

    static final Duration CAPPED = Duration.ofMinutes(5);

    /** billing / auth_permanent 的起步时长。 */
    static final Duration LONG_BASE = Duration.ofMinutes(10);

    static final Duration BILLING_CAP = Duration.ofHours(24);

    static final Duration AUTH_PERMANENT_CAP = Duration.ofMinutes(60);

    /** 失败计数的窗口：超过这个时长没再失败，就从 1 数起。 */
    static final Duration COUNT_WINDOW = Duration.ofHours(24);

    /**
     * 一条冷却。
     *
     * @param until    到这个时刻之前别用
     * @param reason   上一次失败的原因
     * @param failures 窗口内的失败次数
     */
    public record Cooldown(Instant until, FailoverReason reason, int failures) {
    }

    /** 计数的状态：第一次失败的时刻 + 次数。 */
    private record State(Instant firstFailureAt, int failures) {
    }

    private final Map<String, Cooldown> active = new ConcurrentHashMap<>();

    private final Map<String, State> counters = new ConcurrentHashMap<>();

    private final Clock clock;

    /** 用系统时钟。 */
    public CooldownStore() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock 时钟（测试用固定时钟）
     */
    public CooldownStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记一次失败。
     *
     * @param key               {@code provider} 或 {@code provider/model}，作用域见下面说明
     * @param reason            失败原因
     * @param providerRetryAfter 供应商给的时长，没有就是 null（只对限流有意义）
     * @return 这次之后的冷却；不冷却时为空
     */
    public Optional<Cooldown> recordFailure(String key, FailoverReason reason, Duration providerRetryAfter) {
        Instant now = clock.instant();
        int failures = bump(key, now);
        Duration duration = durationOf(reason, providerRetryAfter, failures);
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Optional.empty();
        }
        Cooldown candidate = new Cooldown(now.plus(duration), reason, failures);
        // 只延长不缩短：并发失败里更早的那个不能把长冷却改短。
        Cooldown effective = active.merge(key, candidate,
                (old, fresh) -> old.until().isAfter(fresh.until()) ? old : fresh);
        return Optional.of(effective);
    }

    /**
     * 现在冷却到什么时候。
     *
     * @param key 路由键
     * @return 还在冷却中时返回；已过期或从没冷却过时为空
     */
    public Optional<Cooldown> cooldownOf(String key) {
        Cooldown cooldown = active.get(key);
        if (cooldown == null) {
            return Optional.empty();
        }
        if (!cooldown.until().isAfter(clock.instant())) {
            active.remove(key, cooldown);
            return Optional.empty();
        }
        return Optional.of(cooldown);
    }

    /**
     * 清掉一条冷却（探测成功、或人工恢复时用）。
     *
     * @param key 路由键
     */
    public void clear(String key) {
        active.remove(key);
        counters.remove(key);
    }

    private int bump(String key, Instant now) {
        State previous = counters.get(key);
        if (previous == null || previous.firstFailureAt().plus(COUNT_WINDOW).isBefore(now)) {
            counters.put(key, new State(now, 1));
            return 1;
        }
        int failures = previous.failures() + 1;
        counters.put(key, new State(previous.firstFailureAt(), failures));
        return failures;
    }

    private static Duration durationOf(FailoverReason reason, Duration providerRetryAfter, int failures) {
        return switch (reason) {
            case rate_limit -> providerRetryAfter;          // 没给就不冷却
            case billing -> capped(LONG_BASE.multipliedBy(1L << Math.min(failures - 1, 10)), BILLING_CAP);
            case auth_permanent -> capped(LONG_BASE.multipliedBy(1L << Math.min(failures - 1, 10)),
                    AUTH_PERMANENT_CAP);
            default -> switch (failures) {
                case 1 -> FIRST;
                case 2 -> SECOND;
                default -> CAPPED;
            };
        };
    }

    private static Duration capped(Duration value, Duration cap) {
        return value.compareTo(cap) > 0 ? cap : value;
    }
}
