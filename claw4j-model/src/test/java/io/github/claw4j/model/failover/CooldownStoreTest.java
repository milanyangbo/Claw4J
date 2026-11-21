package io.github.claw4j.model.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷却：四类时长、只延长不缩短、按作用域生效。
 *
 * <p>守的是"限流听供应商的、其它按次数爬、欠费十分钟起"。写死一个 60 秒的后果很具体：
 * 供应商已经准备好接了，我们还在绕开它。
 */
class CooldownStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);

    private final CooldownStore store = new CooldownStore(clock);

    @Test
    @DisplayName("普通失败 30s、1min、5min 封顶")
    void ladderGrowsAndCaps() {
        assertThat(stored("deepseek", FailoverReason.timeout).until()).isEqualTo(T0.plusSeconds(30));
        assertThat(stored("deepseek", FailoverReason.timeout).until()).isEqualTo(T0.plusSeconds(60));
        assertThat(stored("deepseek", FailoverReason.timeout).until()).isEqualTo(T0.plusSeconds(300));
        assertThat(stored("deepseek", FailoverReason.timeout).until())
                .as("第四次仍封顶 5 分钟，不无限涨")
                .isEqualTo(T0.plusSeconds(300));
    }

    @Test
    @DisplayName("限流带 Retry-After: 2 就只冷却 2 秒")
    void rateLimitUsesProviderRetryAfter() {
        CooldownStore.Cooldown cooldown = store
                .recordFailure("deepseek", FailoverReason.rate_limit, Duration.ofSeconds(2))
                .orElseThrow();

        assertThat(cooldown.until()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    @DisplayName("限流但供应商没给时长：不冷却，下一轮仍可用")
    void rateLimitWithoutTimingDoesNotCoolDown() {
        assertThat(store.recordFailure("deepseek", FailoverReason.rate_limit, null))
                .as("自己发明一个 60 秒只会在供应商已经准备好接的时候继续绕开它")
                .isEmpty();
        assertThat(store.cooldownOf("deepseek")).isEmpty();
    }

    @Test
    @DisplayName("更早的到期时间不能缩短已有冷却")
    void earlierDeadlineCannotShorten() {
        store.recordFailure("deepseek", FailoverReason.auth_permanent, null);
        CooldownStore.Cooldown after = store
                .recordFailure("deepseek", FailoverReason.rate_limit, Duration.ofSeconds(1))
                .orElseThrow();

        assertThat(after.until())
                .as("并发失败里更早的那个不该把 10 分钟改成 1 秒")
                .isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("billing 十分钟起，翻倍，封顶 24h")
    void billingUsesTenMinuteBase() {
        assertThat(stored("qwen", FailoverReason.billing).until()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(stored("qwen", FailoverReason.billing).until()).isEqualTo(T0.plus(Duration.ofMinutes(20)));

        CooldownStore.Cooldown capped = null;
        for (int i = 0; i < 10; i++) {
            capped = stored("qwen", FailoverReason.billing);
        }
        assertThat(capped.until()).isEqualTo(T0.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("模型级冷却不影响同家的兄弟模型")
    void modelScopedLeavesSiblingUsable() {
        store.recordFailure("qwen/qwen-plus", FailoverReason.timeout, null);

        assertThat(store.cooldownOf("qwen/qwen-plus")).isPresent();
        assertThat(store.cooldownOf("qwen/qwen-turbo"))
                .as("限流可以只锁到 model：兄弟模型没被限，不该跟着停")
                .isEmpty();
    }

    @Test
    @DisplayName("过了到期时间自己消失")
    void expiredCooldownDisappears() {
        store.recordFailure("deepseek", FailoverReason.timeout, null);
        clock.advance(Duration.ofSeconds(31));

        assertThat(store.cooldownOf("deepseek")).isEmpty();
    }

    private CooldownStore.Cooldown stored(String key, FailoverReason reason) {
        return store.recordFailure(key, reason, null).orElseThrow();
    }

    /** 可以拨的钟：冷却的断言全是"到哪个时刻"，等真实时间会让用例变成几秒钟。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
