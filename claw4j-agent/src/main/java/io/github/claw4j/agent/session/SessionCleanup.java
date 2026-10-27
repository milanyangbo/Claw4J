package io.github.claw4j.agent.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.SessionService;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Objects;

/**
 * 定期把过期会话删掉。
 *
 * <p><b>为什么必须有这个类</b>：{@code spring.ai.session.time-to-live} 只是写在会话行上的一个
 * 时间戳，<b>没有任何东西会去读它并删除</b>。只配 TTL 不配清理，结果是一条记录都没删——
 * 而"过期"在配置里看起来已经成立了。这是本项目第二次遇到"配了不等于生效"
 * （第一次是压缩，见 {@code docs/design/pitfalls.md} 第十条）。
 *
 * <p>间隔用 {@code @Scheduled} 的属性占位符而不是从 properties 对象读：
 * 调度注解在容器启动时就要拿到值，那时 properties 绑定不一定已经完成；
 * 占位符走的是 Environment，两个来源同一个键，不会出现"配了 2 小时但实际还是 1 小时"。
 *
 * <p>删除数量进指标。清理任务最典型的失败姿势是<b>静默空转</b>：
 * 任务在跑、没报错、一条没删。有一个数就能分辨"没有过期的"和"根本没在执行"。
 */
public class SessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanup.class);

    public static final String EXPIRED = "claw4j.session.expired";

    private final SessionService sessions;
    private final MeterRegistry registry;

    public SessionCleanup(SessionService sessions, MeterRegistry registry) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 用 {@code fixedDelay} 而不是 {@code fixedRate}：固定频率在上一次还没跑完时会排队，
     * 库慢的时候能积出一串同时执行的清理任务，互相锁在同一个会话上。
     */
    @Scheduled(fixedDelayString = "${claw4j.session.cleanup-interval:PT1H}")
    public void deleteExpired() {
        int deleted = sessions.deleteExpiredSessions(Instant.now());
        // 存储不可用时 FaultTolerantSessionService 已经吞掉异常并返回 0，
        // 所以这里拿到的 0 既可能是"没有过期的"，也可能是"库连不上"——两者都不该吵。
        if (deleted <= 0) {
            return;
        }
        Counter.builder(EXPIRED)
                .description("被清理掉的过期会话数")
                .register(registry)
                .increment(deleted);
        log.info("清理了 {} 个过期会话", deleted);
    }
}
