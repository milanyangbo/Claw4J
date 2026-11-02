package io.github.claw4j.agent.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * 删掉过了保留期的旧事（P07）：{@code claw4j.audit.retention} 只是写在配置里的一个数，
 * 没有这个任务的话**那一天的行一条都不会消失**。
 *
 * <p>这是本项目第四次遇到"配了 TTL 不等于会清理"（会话、缓存是前两次）。所以这里与
 * {@code SemanticCacheCleanup} 同一个形状：一个 {@code @Scheduled}、一次删除、一条计数。
 *
 * <p><b>删的是 run，不是"审计行"</b>：审计行就是 step，step 由外键级联跟着 run 走。
 * 另写一条 {@code delete from agent_step where created_at < ...} 的话，
 * run 上那句"这件事做完了没有"还在、它的过程没了——两种清理口径迟早对不上。
 *
 * <p><b>审批也一起删</b>：审批行没有外键（V10 那条注释说过为什么不设），
 * 所以它不会跟着 run 走。不删的话，"谁批过"会无限期长下去，而它带着参数摘要——
 * 那正是这一票说好"只留 90 天"的东西。两笔用同一个截止点：它们是同一件事的两面。
 *
 * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：固定频率在上一次没跑完时会排队，
 * 库慢的时候积出一串同时执行的删除任务，互相锁在同一批行上。
 */
public class AuditRetention {

    public static final String PURGED = "claw4j.audit.purged";

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private static final String PURGE_RUNS =
            "delete from claw4j_run.agent_run"
                    + " where created_at < now() - (? * interval '1 millisecond')";

    /**
     * 审批按 {@code requested_at} 判，不按 {@code decided_at}：
     * 一条挂了三个月都没人点的请求，它的留存期从提出那一刻起算——
     * 按结论时间算的话，没人点的那条永远不会被删。
     */
    private static final String PURGE_APPROVALS =
            "delete from claw4j_run.approval_request"
                    + " where requested_at < now() - (? * interval '1 millisecond')";

    private final JdbcTemplate jdbc;

    private final AuditProperties properties;

    /** 为 null 表示没装 actuator：照常删，只是没有计数可报。 */
    private final MeterRegistry registry;

    public AuditRetention(JdbcTemplate jdbc, AuditProperties properties, MeterRegistry registry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = registry;
    }

    /**
     * 间隔走 {@code @Scheduled} 的占位符而不是从 {@link AuditProperties} 读：
     * 调度注解在容器启动时就要拿到值，那时属性绑定不一定已经完成。两处读的是同一个键，
     * 不会出现"配了 1 小时但实际还是 24 小时"。
     */
    @Scheduled(fixedDelayString = "${claw4j.audit.purge-interval:PT24H}")
    public void purgeExpired() {
        purge();
    }

    /**
     * 删一轮，返回删掉的总行数。
     *
     * <p>做成有返回值的公开方法是为了让用例能直接调它：{@code @Scheduled} 的那一层
     * 只负责"到点"，"删对了没有"是另一件事，不该靠等一天去验。
     */
    public int purge() {
        long window = properties.retentionMillis();
        int runs;
        int approvals;
        try {
            runs = jdbc.update(PURGE_RUNS, window);
            approvals = jdbc.update(PURGE_APPROVALS, window);
        }
        catch (RuntimeException failure) {
            // 清理失败只是表在长，不该让一个后台任务反复抛栈。库连不上时下一轮再说。
            log.debug("审计的保留期清理这一轮没跑成", failure);
            return 0;
        }
        if (runs + approvals > 0) {
            count("run", runs);
            count("approval", approvals);
            log.info("按保留期清掉 {} 件旧事与 {} 条审批记录", runs, approvals);
        }
        return runs + approvals;
    }

    private void count(String what, int deleted) {
        if (registry == null || deleted <= 0) {
            return;
        }
        Counter.builder(PURGED)
                .description("按保留期清掉的审计记录数")
                .tag("what", what)
                .register(registry)
                .increment(deleted);
    }
}
