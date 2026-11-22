package io.github.claw4j.agent.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 审批交接的持久实现（ADR-0037 票 05）：一张表、三条语句。
 *
 * <p>写失败不抛：审批已经在 {@code approval_request} 上落成终态，这里只是「这次执行是哪次批准的」
 * 那条线索。线索丢了，审计那一行少一个编号；把异常抛出去，整个工具调用会挂——代价不对等。
 * 失败计 {@code claw4j.audit.link_errors}，不为 0 就说明有一批执行对不上审批。
 */
public class JdbcDecisionFacts implements DecisionFacts {

    /** 交接写失败的次数。与 {@code write_errors} 分开：这条链丢的是关联，不是审计行。 */
    public static final String LINK_ERRORS = "claw4j.audit.link_errors";

    private static final Logger log = LoggerFactory.getLogger(JdbcDecisionFacts.class);

    private static final String GRANT = """
            insert into claw4j_run.execution_decision_fact
                (run_id, tool_name, args_digest, approval_id)
            values (?, ?, ?, ?)
            on conflict (run_id, tool_name, args_digest)
            do update set approval_id = excluded.approval_id, created_at = now()
            """;

    private static final String TAKE = """
            delete from claw4j_run.execution_decision_fact
             where run_id = ? and tool_name = ? and args_digest = ?
            returning approval_id
            """;

    private static final String PRUNE =
            "delete from claw4j_run.execution_decision_fact"
                    + " where created_at < now() - (? * interval '1 millisecond')";

    private final JdbcTemplate jdbc;

    /** 写失败只打一次日志：一次库故障不该变成一整页刷屏。 */
    private volatile boolean warned;

    /**
     * @param jdbc 主连接池，必填
     */
    public JdbcDecisionFacts(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void grant(String runId, String toolName, String argsDigest, String approvalId) {
        if (blank(runId) || blank(toolName) || blank(argsDigest) || blank(approvalId)) {
            return;
        }
        try {
            jdbc.update(GRANT, runId, toolName, argsDigest, approvalId);
        }
        catch (RuntimeException failure) {
            warn("记审批交接", failure);
        }
    }

    @Override
    public String take(String runId, String toolName, String argsDigest) {
        if (blank(runId) || blank(toolName) || blank(argsDigest)) {
            return null;
        }
        try {
            List<String> taken = jdbc.queryForList(TAKE, String.class, runId, toolName, argsDigest);
            return taken.isEmpty() ? null : taken.getFirst();
        }
        catch (RuntimeException failure) {
            warn("取审批交接", failure);
            return null;
        }
    }

    @Override
    public int prune(Duration age) {
        try {
            return jdbc.update(PRUNE, age.toMillis());
        }
        catch (RuntimeException failure) {
            warn("清过期的审批交接", failure);
            return 0;
        }
    }

    /**
     * 每小时顺手清一次过期交接（与审计账本同一个调度间隔）。
     *
     * <p>一小时足够宽：交接从写入到被取走只在一次工具调用之内，留下的是"批了但没跑"的那些——
     * 它们不会再有下文，留着只会让下一次排查多出一行没有执行的批准。
     */
    @Scheduled(fixedDelayString = "${claw4j.audit.prune-interval:PT1H}")
    public void pruneStale() {
        int removed = prune(Duration.ofHours(1));
        if (removed > 0) {
            log.info("清掉 {} 条过期的审批交接", removed);
        }
    }

    private void warn(String what, RuntimeException failure) {
        if (!warned) {
            warned = true;
            log.warn("{}失败，之后的同类失败只计数不再打日志：{}", what, failure.toString());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
