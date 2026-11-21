package io.github.claw4j.model.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探测节流：30 秒一次、还早就不探、键有上限。
 *
 * <p>一个请求一次探测的后果是：冷却中的端点被持续撞，日志里全是同一个失败。
 */
class ProbeThrottleTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);

    private final ProbeThrottle throttle = new ProbeThrottle(clock);

    @Test
    @DisplayName("30 秒最多一次；距到期 2 分钟以上且有候选时不探")
    void probesThrottledAndMarginRespected() {
        assertThat(throttle.mayProbe("deepseek", null, true))
                .as("没有冷却时是正常调用，不算探测")
                .isTrue();

        Instant far = T0.plus(Duration.ofMinutes(10));
        assertThat(throttle.mayProbe("deepseek", far, true))
                .as("还有十分钟才到期，有候选就先别碰它")
                .isFalse();

        Instant soon = T0.plus(Duration.ofMinutes(1));
        assertThat(throttle.mayProbe("deepseek", soon, true)).isTrue();
        clock.advance(Duration.ofSeconds(10));
        assertThat(throttle.mayProbe("deepseek", soon, true))
                .as("同一键 30 秒内只放一个")
                .isFalse();
        clock.advance(Duration.ofSeconds(25));
        assertThat(throttle.mayProbe("deepseek", soon, true)).isTrue();

        assertThat(throttle.mayProbe("single", far, false))
                .as("没有候选链时不探就等于再也不试")
                .isTrue();
    }

    /** 可以拨的钟。 */
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
