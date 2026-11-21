package io.github.claw4j.model.failover;

import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import java.time.Duration;

/**
 * 一次模型调用的重试预算：一组数，两个策略。
 *
 * <p>以前三处各一套：DeepSeek 用 {@code RetryUtils.SHORT_RETRY_TEMPLATE}、通义 {@code maxRetries(0)}、
 * 路由层再叠"同一家最多两次"。没人说得清一次抖动会打几次。这里收成一份，数字照 OpenClaw 的
 * `failover-retry-controller.ts:34-41,74`：
 * <ul>
 *   <li>限流：最多 16 次总尝试（15 次重试），1s 起、×2、封顶 60s；
 *   <li>其它瞬时：8 次重试，且只在 90 秒窗口内；封顶 30s；
 *   <li>供应商给的 {@code Retry-After} 超过 {@code maxRetryDelay} 就不再等，交回路由走冷却与推进。
 * </ul>
 *
 * <p>用 Spring 自己的 {@link RetryPolicy} / {@link RetryTemplate}，不自己写退避：
 * 这几个旋钮（delay / multiplier / jitter / maxDelay / timeout / predicate）它都有。
 *
 * @param rateLimitMaxRetries     限流的重试次数
 * @param transientMaxRetries     其它瞬时的重试次数
 * @param transientWindow         其它瞬时的总时长窗口
 * @param delay                   首次重试前等多久
 * @param jitter                  抖动
 * @param multiplier              退避倍数
 * @param maxRateLimitDelay       限流那一路的单次封顶
 * @param maxTransientDelay       其它瞬时那一路的单次封顶
 */
public record RetryBudget(long rateLimitMaxRetries,
                          long transientMaxRetries,
                          Duration transientWindow,
                          Duration delay,
                          Duration jitter,
                          double multiplier,
                          Duration maxRateLimitDelay,
                          Duration maxTransientDelay) {

    /** OpenClaw 的那组数。 */
    public static RetryBudget defaults() {
        return new RetryBudget(15, 8, Duration.ofSeconds(90), Duration.ofSeconds(1),
                Duration.ofMillis(100), 2.0, Duration.ofSeconds(60), Duration.ofSeconds(30));
    }

    /**
     * 限流那一路：只重试限流。
     *
     * @return 策略
     */
    public RetryPolicy rateLimitPolicy() {
        return RetryPolicy.builder()
                .maxRetries(rateLimitMaxRetries)
                .delay(delay)
                .jitter(jitter)
                .multiplier(multiplier)
                .maxDelay(maxRateLimitDelay)
                .predicate(failure -> FailoverClassifier.classify(failure) == FailoverReason.rate_limit)
                .build();
    }

    /**
     * 其它瞬时那一路：窗口内最多 8 次，封顶 30s。
     *
     * @return 策略
     */
    public RetryPolicy transientPolicy() {
        return RetryPolicy.builder()
                .maxRetries(transientMaxRetries)
                .delay(delay)
                .jitter(jitter)
                .multiplier(multiplier)
                .maxDelay(maxTransientDelay)
                .timeout(transientWindow)
                .predicate(failure -> {
                    FailoverReason reason = FailoverClassifier.classify(failure);
                    return reason != FailoverReason.rate_limit && reason.retryable();
                })
                .build();
    }

    /**
     * 供应商 bean 用的那个模板：只装"其它瞬时"这一档（8 次重试、90 秒窗口）。
     *
     * <p>为什么不把限流那 16 次也塞进来：在同一个供应商内部重打 429 只会更糟——限流该做的是
     * 冷却 + 换家（票 07 / 10），不是原地等 16 次。{@link #rateLimitPolicy()} 留给外层路由。
     *
     * @return 模板
     */
    public RetryTemplate template() {
        return new RetryTemplate(transientPolicy());
    }

    /**
     * 供应商给的 {@code Retry-After} 还值不值得等。
     *
     * @param retryAfter 供应商给的时长，可能为 null
     * @return 超过单次封顶时返回 false：不再等，交回路由走冷却与推进
     */
    public boolean worthWaiting(Duration retryAfter) {
        return retryAfter == null || retryAfter.compareTo(maxRateLimitDelay) <= 0;
    }
}
