package io.github.claw4j.agent.session;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Objects;

/**
 * 每小时跑一次旧会话清理。
 *
 * <p><b>为什么必须有这个类</b>：{@code spring.ai.session.time-to-live} 只是写在会话行上的
 * 一个时间戳，<b>没有任何东西会去读它并删除</b>。只配 TTL 不配清理，结果是一条记录都没删——
 * 而"过期"在配置里看起来已经成立了。这是本项目第二次遇到"配了不等于生效"
 * （第一次是压缩，见 {@code docs/design/pitfalls.md} 第十条）。
 *
 * <p><b>它只负责"什么时候跑"，不负责"删什么"</b>：判据在 {@link SessionMaintenance}。
 * 分开的理由是两件事的读者不同——间隔是调度问题（{@code @Scheduled} 在启动时就要拿到值），
 * 判据是数据问题（要能单独验、单独改口径）。
 *
 * <p>间隔用 {@code @Scheduled} 的属性占位符而不是从 properties 对象读：
 * 调度注解在容器启动时就要拿到值，那时 properties 绑定不一定已经完成；
 * 占位符走的是 Environment，两个来源同一个键，不会出现"配了 2 小时但实际还是 1 小时"。
 */
public class SessionCleanup {

    private final SessionMaintenance maintenance;

    public SessionCleanup(SessionMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    /**
     * 用 {@code fixedDelay} 而不是 {@code fixedRate}：固定频率在上一次还没跑完时会排队，
     * 库慢的时候能积出一串同时执行的清理任务，互相锁在同一个会话上。
     *
     * <p><b>{@code mode: warn} 下这一趟一行都不删</b>，它只算，并把"本来会删多少"写进日志与指标。
     * 这是默认档位（与 OpenClaw 一致），不是故障——要确认它到底删没删，看
     * {@code claw4j.session.maintenance.pruned}；要确认它有没有在跑，看
     * {@code …prunable} 与 {@code …failures}。
     */
    @Scheduled(fixedDelayString = "${claw4j.session.cleanup-interval:PT1H}")
    public void sweep() {
        maintenance.sweep(Instant.now());
    }
}
