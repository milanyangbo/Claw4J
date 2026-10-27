package io.github.claw4j.agent.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * 定期删掉过期的缓存行。
 *
 * <p><b>为什么必须有</b>：{@code claw4j.cache.time-to-live} 只是写在行上的一个时间戳。
 * 查询时它会让过期行不参与命中，但**那一天的行一条都不会消失**——表会一直长。
 * 这是本项目第三次遇到"配了 TTL 不等于会清理"（会话 TTL 是第二次，见
 * {@code docs/design/pitfalls.md} 第十条）。TTL 与清理任务必须成对出现。
 *
 * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：固定频率在上一次没跑完时会排队，
 * 库慢的时候积出一串同时执行的删除任务，互相锁在同一批行上。
 */
public class SemanticCacheCleanup {

    public static final String EXPIRED = "claw4j.cache.expired";

    private final SemanticCache cache;

    private final MeterRegistry registry;

    public SemanticCacheCleanup(SemanticCache cache, MeterRegistry registry) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.registry = registry == null ? Metrics.globalRegistry : registry;
    }

    /**
     * 间隔走 {@code @Scheduled} 的占位符而不是从 {@link CacheProperties} 读：调度注解在容器
     * 启动时就要拿到值，那时属性绑定不一定已经完成。占位符与 properties 读的是同一个键，
     * 不会出现"配了 2 小时但实际还是 1 小时"。
     *
     * <p>这个键**只保证"两边读到的原文一致"，不保证"原文是合法值"**——归一化那一步在
     * {@link CacheProperties} 的构造器里，它不经手这里。所以 0 与负数在那一步直接拒绝启动，
     * 而不是被悄悄改成默认值：真让 {@code PT0S} 走到这个注解上，就是 {@code fixedDelay=0}
     * 的热循环。
     */
    @Scheduled(fixedDelayString = "${claw4j.cache.cleanup-interval:PT1H}")
    public void deleteExpired() {
        int deleted;
        try {
            deleted = cache.deleteExpired();
        }
        catch (RuntimeException failure) {
            // 清理失败只是表在长，不该让一个后台任务反复抛栈。库连不上时下一轮再说。
            return;
        }
        if (deleted <= 0) {
            return;
        }
        Counter.builder(EXPIRED)
                .description("被清理掉的过期缓存行数")
                .register(registry)
                .increment(deleted);
    }
}
