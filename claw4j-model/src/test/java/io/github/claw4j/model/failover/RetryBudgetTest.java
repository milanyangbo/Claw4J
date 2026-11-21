package io.github.claw4j.model.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重试预算：两组数、一套退避、一个模板。
 *
 * <p>守的是"一次抖动到底打几次"能回答。以前三处各一套（SDK 短重试、通义关掉、路由再叠两次），
 * 叠起来就是乘法。
 */
class RetryBudgetTest {

    /** 测试用的小预算：真实退避 1 秒起，跑 16 次要等到分钟级。 */
    private static final RetryBudget FAST = new RetryBudget(15, 8, Duration.ofSeconds(90),
            Duration.ofMillis(1), Duration.ZERO, 1.0, Duration.ofSeconds(60), Duration.ofSeconds(30));

    @Test
    @DisplayName("限流最多 16 次总尝试（1 次 + 15 次重试）")
    void rateLimitAllowsSixteenAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        // 16 次是外层路由的预算（限流时换家），不是供应商内部的重试
        RetryTemplate template = new RetryTemplate(FAST.rateLimitPolicy());

        assertThatThrownBy(() -> template.invoke(() -> {
            attempts.incrementAndGet();
            throw new org.springframework.ai.retry.TransientAiException("429 too many requests");
        })).isInstanceOf(org.springframework.ai.retry.TransientAiException.class);

        assertThat(attempts).hasValue(16);
    }

    @Test
    @DisplayName("其它瞬时 8 次重试，且只花在 90 秒窗口内")
    void transientAllowsEightAttemptsInNinetySeconds() {
        AtomicInteger attempts = new AtomicInteger();
        RetryTemplate template = FAST.template();

        assertThatThrownBy(() -> template.invoke(() -> {
            attempts.incrementAndGet();
            // 受检异常要包一层：RetryTemplate#invoke 的 Supplier 不能抛 checked，
            // 而分类器沿 cause 链走，包一层不影响判定（route_unavailable → 瞬时槽）。
            throw new RuntimeException(new java.net.ConnectException("连接被拒"));
        })).isInstanceOf(RuntimeException.class);

        assertThat(attempts).hasValue(9);
        assertThat(RetryBudget.defaults().transientPolicy().getTimeout())
                .as("窗口是 90 秒：超过就别再等，交回路由推进")
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("退避从 1s 起、翻倍、有抖动、按档封顶")
    void backoffStartsAtOneSecondAndDoubles() {
        BackOffExecution execution = RetryBudget.defaults().transientPolicy().getBackOff().start();
        long first = execution.nextBackOff();
        long second = execution.nextBackOff();

        // 抖动是"在本次间隔上下 10% 内随机"，所以区间是 1000±100 与 2000±200
        assertThat(first).isBetween(900L, 1100L);
        assertThat(second).isBetween(1800L, 2200L);
    }

    @Test
    @DisplayName("成功一次之后下一次从头开始：预算不跨调用累计")
    void successResetsBudget() {
        AtomicInteger attempts = new AtomicInteger();
        RetryTemplate template = FAST.template();

        template.invoke(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException(new java.net.ConnectException("抖了一下"));
            }
            return "ok";
        });
        assertThat(attempts).hasValue(3);

        attempts.set(0);
        template.invoke(() -> {
            attempts.incrementAndGet();
            return "ok";
        });
        assertThat(attempts)
                .as("上一次用了两次重试，不该让这一次一开始就只剩零次")
                .hasValue(1);
    }

    @Test
    @DisplayName("Retry-After 超过单次封顶就不等了")
    void retryAfterBeyondCapSurfaces() {
        RetryBudget budget = RetryBudget.defaults();
        assertThat(budget.worthWaiting(Duration.ofSeconds(30))).isTrue();
        assertThat(budget.worthWaiting(Duration.ofSeconds(61)))
                .as("睡满供应商给的时长会让这一轮卡住；不如交回路由走冷却与推进")
                .isFalse();
        assertThat(budget.worthWaiting(null)).isTrue();
    }

    @Test
    @DisplayName("两家供应商共用同一份预算：限流归限流那一档，其它瞬时归另一档")
    void providersShareOneRetryPolicy() {
        RetryBudget budget = RetryBudget.defaults();
        var rateLimited = new org.springframework.ai.retry.TransientAiException("429 too many requests");
        var overloaded = com.openai.errors.UnexpectedStatusCodeException.builder()
                .statusCode(503)
                .headers(com.openai.core.http.Headers.builder().build())
                .cause(new IllegalStateException("503"))
                .build();

        assertThat(budget.rateLimitPolicy().shouldRetry(rateLimited)).isTrue();
        assertThat(budget.transientPolicy().shouldRetry(rateLimited))
                .as("限流不走「其它瞬时」那 8 次：两档各自计数才知道一次抖动打几次")
                .isFalse();
        assertThat(budget.transientPolicy().shouldRetry(overloaded)).isTrue();
    }
}
