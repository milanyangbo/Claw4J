package io.github.claw4j.model.failover;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 探测节流：冷却快到期时，每 30 秒最多放一个请求过去试。
 *
 * <p>三个数照 OpenClaw 的 `src/agents/model-fallback-cooldown.ts:9-11`：
 * {@code MIN_PROBE_INTERVAL_MS = 30000}、{@code PROBE_MARGIN_MS = 120000}、键上限 256。
 *
 * <p>为什么要有余量：冷却还剩十几分钟时放探测，等于每个请求都去撞一次已经知道不通的端点；
 * 只有快到点（2 分钟内）才值得试。没有候选链的单模型部署则不受这条限制——
 * 不让它探就没有任何人会再试。
 */
public final class ProbeThrottle {

    /** 同一个键两次探测之间至少隔这么久。 */
    static final Duration MIN_PROBE_INTERVAL = Duration.ofSeconds(30);

    /** 冷却还剩这么久以上时，有候选链就别急着探。 */
    static final Duration PROBE_MARGIN = Duration.ofMinutes(2);

    /** 记住多少个键的探测时刻；超过就丢最老的。 */
    static final int MAX_KEYS = 256;

    private final Map<String, Instant> lastProbe = new LinkedHashMap<>(16, 0.75f, true);

    private final Clock clock;

    public ProbeThrottle() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock 时钟（测试用固定时钟）
     */
    public ProbeThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * 现在能不能放一个探测过去。
     *
     * @param key            路由键
     * @param cooldownUntil  冷却到期时刻，没有冷却时传 null
     * @param hasFallbacks   有没有别的候选；没有时"提前探"是唯一出路
     * @return 可以探时为 true
     */
    public synchronized boolean mayProbe(String key, Instant cooldownUntil, boolean hasFallbacks) {
        Instant now = clock.instant();
        if (cooldownUntil == null) {
            return true;
        }
        if (hasFallbacks && cooldownUntil.isAfter(now.plus(PROBE_MARGIN))) {
            // 还早：有别的候选先用别的，别把冷却中的端点当探测对象。
            return false;
        }
        Instant previous = lastProbe.get(key);
        if (previous != null && previous.plus(MIN_PROBE_INTERVAL).isAfter(now)) {
            return false;
        }
        remember(key, now);
        return true;
    }

    /**
     * 记下一次探测（成功或失败都算一次，下一次要隔 30 秒）。
     *
     * @param key 路由键
     */
    public synchronized void markProbed(String key) {
        remember(key, clock.instant());
    }

    private void remember(String key, Instant now) {
        lastProbe.put(key, now);
        while (lastProbe.size() > MAX_KEYS) {
            String oldest = lastProbe.keySet().iterator().next();
            lastProbe.remove(oldest);
        }
    }
}
