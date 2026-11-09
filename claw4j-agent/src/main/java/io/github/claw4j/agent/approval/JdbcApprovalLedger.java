package io.github.claw4j.agent.approval;

import io.github.claw4j.agent.approval.PrincipalSnapshot;
import io.github.claw4j.domain.port.ApprovalLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 审批账本的 JDBC 落点，表在 {@code claw4j_run.approval_request}（V6）。
 *
 * <p><b>读失败按"没有批准"处理</b>，与账本那一层"写不进去就降级"的方向相反。
 * 那边落不进去只少一行审计（对话照常），这边读不出来却要决定"放不放一个还没人批过的动作"——
 * 同一个"降级"在两条路上指向两个方向，所以这里的 catch 里返回的是 {@link Optional#empty()}，
 * 不是"算了放过去"。审批这条链上，出错时收紧才是安全的那一侧。
 *
 * <p>库没迁（迁移默认关着）时表不存在，于是每个标了 {@code REQUIRED} 的动作都会被拦——
 * 这是这个决定顺带得到的第二个好处：没有审批设施的环境里，没有人能悄悄绕过审批。
 *
 * <p>只记一次 warn：同一次启动之后每次工具调用都会走到这里，逐条打会把日志淹掉。
 */
public class JdbcApprovalLedger implements ApprovalLedger {

    private static final Logger log = LoggerFactory.getLogger(JdbcApprovalLedger.class);

    /**
     * 兑现一条批准：找到就**当场**标成用掉，再把那一条交回来。
     *
     * <p><b>一条语句而不是"先查后改"</b>：一次点头只放行一次执行。分成两步时，
     * 两个并发的同参数调用会同时查到同一条批准，于是同一次点头放行了两次副作用。
     * {@code for update skip locked} 让第二个请求直接看不到这条（而不是排队等）、
     * 于是它走"没有批准"那条路，重新发起一次审批——那个结果是对的。
     *
     * <p>有效期在 SQL 里比，不在 Java 里比：{@code now()} 用的是库的时钟，
     * 与 {@code expires_at} 同一个来源；拿 JVM 时钟去比库里的时间戳，会在两者有偏差的部署里
     * 让一批批准提前或延后作废，而那种事故没有日志可查。
     *
     * <p>同一个人同一工具同一参数有多条有效批准时取最晚到期的那个：这不改变"有效"这个判定，
     * 只是让先兑现哪一条是确定的。
     *
     * <p><b>状态是 {@code allowed} 不是 {@code granted}</b>（2026-11-09 对齐 openclaw 的五态）：
     * granted 是我们自己起的名字，表上只有 allowed。{@code consumed_by} 与 {@code consumed_at}
     * 一起写：表上的约束要求这两列同进同退，而"谁用掉的"是事后追责的入口。
     */
    private static final String REDEEM_SQL =
            "update claw4j_run.approval_request set consumed_at = now(), consumed_by = ? where id = ("
                    + " select id from claw4j_run.approval_request"
                    + " where owner = ? and tool_name = ? and args_digest = ? and status = 'allowed'"
                    + " and consumed_at is null and expires_at > now()"
                    + " order by expires_at desc limit 1 for update skip locked)"
                    + " returning id, expires_at";

    /**
     * 登记一条待批请求。
     *
     * <p>过期点用 {@code now() + 毫秒} 算，不传一个 JVM 算好的绝对时刻：
     * 那样"十分钟"就变成了"应用时钟的十分钟"，而判定那个方向用的是库的时钟。
     */
    private static final String ASK_SQL =
            "insert into claw4j_run.approval_request"
                    + " (id, run_id, owner, tool_name, args_digest, args_summary, status,"
                    + " session_key, channel, chat_ref, principal_snapshot,"
                    + " requested_at, expires_at)"
                    + " values (?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, now(),"
                    + " now() + (? * interval '1 millisecond'))";

    private final JdbcTemplate jdbc;

    /** 一条请求等多长。默认十分钟，写在配置里（{@code claw4j.approval.ttl}）。 */
    private final long ttlMillis;

    private volatile boolean degraded;

    public JdbcApprovalLedger(JdbcTemplate jdbc, long ttlMillis) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Optional<Grant> redeem(String owner, String toolName, String argsDigest) {
        if (isBlank(owner) || isBlank(toolName) || isBlank(argsDigest)) {
            // 问不出答案的那种输入：按"没有批准"走，而不是拼一个 "%" 去撞运气。
            return Optional.empty();
        }
        try {
            Optional<Grant> granted = jdbc.query(REDEEM_SQL, this::grantOf, owner, owner, toolName, argsDigest)
                    .stream().findFirst();
            if (degraded) {
                degraded = false;
                log.info("审批账本已恢复可读");
            }
            return granted;
        }
        catch (RuntimeException failure) {
            warnOnce("审批账本读不出来，标了要审批的动作这一轮一律按“没有批准”处理", failure);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> ask(Ask ask) {
        if (ask == null || isBlank(ask.owner()) || isBlank(ask.toolName()) || isBlank(ask.argsDigest())) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        // 来处与请求一起落（ADR-0027）：批准之后要回到那段对话开新一轮，
        // 而"哪段对话、发起人是谁"是这一条请求自己的属性，不该寄存在别处。
        Origin origin = ask.origin();
        try {
            jdbc.update(ASK_SQL, id, ask.runId(), ask.owner(), ask.toolName(), ask.argsDigest(),
                    ask.argsSummary(),
                    origin == null ? null : origin.sessionKey(),
                    origin == null ? null : origin.channel(),
                    origin == null ? null : origin.chatRef(),
                    origin == null || origin.principal() == null
                            ? null : PrincipalSnapshot.encode(origin.principal()),
                    ttlMillis);
            return Optional.of(id);
        }
        catch (RuntimeException failure) {
            // 记不下来时仍然要拦下这次调用（没批过就是没批过），只是这一轮没有编号回给用户。
            warnOnce("登记审批请求失败：这一轮仍然按“没有批准”拦下，但用户拿不到审批编号", failure);
            return Optional.empty();
        }
    }

    private Grant grantOf(ResultSet row, int index) throws SQLException {
        OffsetDateTime expiresAt = row.getObject("expires_at", OffsetDateTime.class);
        return new Grant(row.getString("id"),
                expiresAt == null ? Instant.EPOCH : expiresAt.toInstant());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void warnOnce(String message, RuntimeException failure) {
        if (!degraded) {
            degraded = true;
            log.warn(message + "（之后同类失败不再逐条打日志）", failure);
        }
    }
}
