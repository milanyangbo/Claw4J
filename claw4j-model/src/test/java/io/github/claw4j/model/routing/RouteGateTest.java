package io.github.claw4j.model.routing;

import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.failover.FailoverReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 准入闸：饱和的端点不排队，绕开；全饱和时选最早恢复的那个。
 *
 * <p>它不是限流器。"每秒最多几个请求"是边界层的事，这里只回答"这个组合什么时候能再用"。
 */
class RouteGateTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

    private static final RouteGate.Key PRIMARY =
            new RouteGate.Key("https://api.deepseek.com", "deepseek", "deepseek/deepseek-chat");

    private static final RouteGate.Key BACKUP =
            new RouteGate.Key("https://dashscope.aliyuncs.com", "qwen", "qwen/qwen-plus");

    private final MutableClock clock = new MutableClock(T0);

    private final RouteGate gate = new RouteGate(clock);

    @Test
    @DisplayName("某端点限流后，下一次请求先发给别的候选")
    void saturatedRouteIsSkipped() {
        gate.openOnFailure(PRIMARY, FailoverReason.rate_limit, T0.plusSeconds(20));

        assertThat(gate.orderByAvailability(List.of("primary", "backup"), name ->
                name.equals("primary") ? PRIMARY : BACKUP))
                .as("可用优先：饱和的排到最后")
                .containsExactly("backup", "primary");
    }

    @Test
    @DisplayName("闸在最后一次底线之后 1 分钟过期")
    void gateExpiresAfterOneMinute() {
        gate.openOnFailure(PRIMARY, FailoverReason.rate_limit, T0.plusSeconds(10));
        clock.advance(Duration.ofSeconds(30));
        assertThat(gate.retryAt(PRIMARY)).isPresent();

        clock.advance(Duration.ofSeconds(45));
        assertThat(gate.retryAt(PRIMARY))
                .as("底线 10s + 1 分钟过期，不能永远挡着")
                .isEmpty();
    }

    @Test
    @DisplayName("全部饱和时按恢复时间升序，不是配置顺序")
    void allSaturatedPicksEarliestRetry() {
        gate.openOnFailure(PRIMARY, FailoverReason.rate_limit, T0.plusSeconds(50));
        gate.openOnFailure(BACKUP, FailoverReason.rate_limit, T0.plusSeconds(10));

        assertThat(gate.orderByAvailability(List.of("primary", "backup"), name ->
                name.equals("primary") ? PRIMARY : BACKUP))
                .containsExactly("backup", "primary");
        assertThat(gate.soonestRecovery(List.of(PRIMARY, BACKUP))).contains(T0.plusSeconds(10));
    }

    @Test
    @DisplayName("普通 5xx 不开闸：那是冷却的事")
    void serverErrorDoesNotOpenGate() {
        assertThat(gate.openOnFailure(PRIMARY, FailoverReason.server_error, T0.plusSeconds(30)))
                .isFalse();
        assertThat(gate.retryAt(PRIMARY)).isEmpty();
    }

    @Test
    @DisplayName("成功流量不延长闸")
    void successDoesNotExtendGate() {
        gate.openOnFailure(PRIMARY, FailoverReason.rate_limit, T0.plusSeconds(10));
        Instant before = gate.retryAt(PRIMARY).orElseThrow();

        gate.recordSuccess(PRIMARY);

        assertThat(gate.retryAt(PRIMARY)).contains(before);
    }

    @Test
    @DisplayName("闸自己不重试、不发请求：它只回答时刻与顺序")
    void gateNeverRetriesByItself() {
        gate.openOnFailure(PRIMARY, FailoverReason.rate_limit, T0.plusSeconds(10));

        // 只有两个出口：问时刻、要顺序。没有"执行一次调用"的方法，也就没有闸内重试这条路。
        assertThat(gate.getClass().getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("retry", "execute", "call", "send");
    }

    @Test
    @DisplayName("欠费也开闸：额度类失败同样意味着「现在别打」")
    void billingAlsoOpens() {
        assertThat(gate.openOnFailure(PRIMARY, FailoverReason.billing, T0.plusSeconds(600))).isTrue();
        assertThat(gate.retryAt(PRIMARY)).contains(T0.plusSeconds(600));
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
