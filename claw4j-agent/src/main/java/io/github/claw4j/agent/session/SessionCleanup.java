package io.github.claw4j.agent.session;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Objects;

/**
 * 按固定间隔跑一次旧会话清理。
 *
 * <p>{@code spring.ai.session.time-to-live} 只是写在会话行上的时间戳，没有任何东西会读它并
 * 删除。只配 TTL 不配清理，一条记录都不会少，而"过期"在配置里看起来已经成立
 * （同类问题见 {@code docs/design/pitfalls.md} 第十条）。
 *
 * <p>本类只管"什么时候跑"，判据在 {@link SessionMaintenance}：间隔是调度问题
 * （{@code @Scheduled} 启动时就要拿到值），判据是数据问题（要能单独验、单独改口径）。
 *
 * <p>间隔用 {@code @Scheduled} 的属性占位符而不是从 properties 对象读：调度注解在容器启动时
 * 就要拿到值，那时绑定不一定完成；占位符走 Environment，两个来源同一个键，不会出现
 * "配了 2 小时但实际还是 1 小时"。
 */
public class SessionCleanup {

    private final SessionMaintenance maintenance;

    public SessionCleanup(SessionMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    /**
     * 跑一次清理。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：固定频率在上一次还没跑完时会排队，
     * 库慢的时候能积出一串同时执行的清理任务，互相锁在同一个会话上。
     *
     * <p>{@code mode: warn} 下这一趟一行都不删，只算并记日志与指标。这是本仓库的默认档位，
     * 比 OpenClaw 的 {@code enforce} 保守，原因见 {@link SessionMaintenanceProperties}。
     * 要确认删没删看 {@code claw4j.session.maintenance.pruned}，
     * 要确认跑没跑看 {@code …prunable} 与 {@code …failures}。
     */
    @Scheduled(fixedDelayString = "${claw4j.session.cleanup-interval:PT1H}")
    public void sweep() {
        maintenance.sweep(Instant.now());
    }
}
