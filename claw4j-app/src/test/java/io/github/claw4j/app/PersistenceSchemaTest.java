package io.github.claw4j.app;

import io.github.claw4j.agent.approval.JdbcApprovalLedger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditProperties;
import io.github.claw4j.agent.audit.AuditLedger;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.JdbcSessionGoalStore;
import io.github.claw4j.agent.goal.SessionGoal;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.session.SessionKeys;

import java.time.Instant;
import io.github.claw4j.agent.session.ConversationPointerStore;
import io.github.claw4j.agent.session.JdbcConversationPointerStore;
import io.github.claw4j.agent.session.SessionResetProperties;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.port.ApprovalLedger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * run / step 那两条 SQL 真跑在 Postgres 上（A01）。
 *
 * <p><b>为什么单独一份</b>：主链路那份（{@code ChatPipelineTest}）用的是内存版存储，验的是
 * "什么时候开 run、什么时候收尾"。而这里要验的是<b>库真的会落成那样</b>——
 * 表在 {@code claw4j_run} 这个 schema 下（不是 public）、状态受 check 约束、
 * 同一 run 内 {@code seq} 唯一。这几条错了都不报错：写错 schema 是"插入失败被降级吞掉"，
 * 写错约束是"什么状态都存得进去"，而 run 表存在的理由恰恰是"能答出做到第几步"。
 *
 * <p>迁移也由这里跑：表是 {@code V6} 建的，用 {@link Flyway} 而不是手写 DDL。
 */
@Testcontainers
class PersistenceSchemaTest {

    /** 和语义缓存那份用同一个镜像：同一批迁移里的 {@code V4} 要 {@code create extension vector}。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    /**
     * 两个形状合法（64 位十六进制）但不同的指纹。
     *
     * <p>用真形状而不是 {@code "d1"}：V11 给这一列定了 {@code varchar(64)}，
     * 拿短串测的话，"列宽够不够放一个真指纹"这件事永远测不到。
     */
    private static final String DIGEST = "a".repeat(64);

    private static final String OTHER_DIGEST = "b".repeat(64);

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void freshTables() {
        jdbc.update("delete from claw4j_session.conversation_goal");
        jdbc.update("delete from claw4j_session.conversation_pointer");
        jdbc.update("delete from claw4j_run.approval_request");
        // 审计也要清：上限那条用例会插十万行，留着会让"30 天只留一条"的断言看到别人的行。
        jdbc.update("delete from claw4j_run.audit_event");
    }

    @Test
    @DisplayName("V24 之后 claw4j_run 里只剩三张表：run 与 step 都不在了")
    void migrationLeavesOnlyTheTablesThatSurvived() {
        // 审计视图长在 step 上，随 step 一起删（V24）；agent_session_budget 在 V22 已经删了。
        // 这条断言存在的理由是"兼容层"那个诱惑：留一张空表"以防有人要查历史"，
        // 下一个人就会往里加一列。历史在 audit_event 上，不在 step 上。
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'claw4j_run'"
                        + " order by table_name", String.class))
                .as("run 只是一轮的句柄（ADR-0027），不落表")
                .containsExactly("agent_schedule", "approval_request", "audit_event");
    }

    @Test
    @DisplayName("六态之外写不进去；空白目标、0 预算、同一会话第二行都被库拒绝")
    void goalsAreSessionStateWithAClosedVocabulary() {
        jdbc.update("insert into claw4j_session.conversation_goal"
                + " (session_key, status, objective) values ('k-a', 'active', '让 CI 变绿')");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_session.conversation_goal"
                + " (session_key, status, objective) values ('k-b', 'running', '错的态')"))
                .as("六态是闭合词表：写成 running 会被库拒绝，而不是存下一个没人认得的状态")
                .hasMessageContaining("conversation_goal_status_allowed");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_session.conversation_goal"
                + " (session_key, status, objective) values ('k-c', 'active', '   ')"))
                .as("空白目标不是空目标，是存了一个空格")
                .hasMessageContaining("conversation_goal_objective_present");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_session.conversation_goal"
                + " (session_key, status, objective, token_budget)"
                + " values ('k-d', 'active', 'x', 0)"))
                .as("0 预算的意思是「一点额度都不给」，不限额的正确写法是不写这一列")
                .hasMessageContaining("conversation_goal_budget_positive");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_session.conversation_goal"
                + " (session_key, status, objective) values ('k-a', 'active', '同一个键再来一行')"))
                .as("主键就是「一个会话一个目标」：不用标志位，因为标志位写得出一行都不是当前")
                .hasMessageContaining("conversation_goal_pkey");
    }

    @Test
    @DisplayName("换段就是换行：新段天然没有目标；edit 不动状态与用量；已完成是终态")
    void aFreshSegmentHasNoGoalAndEditKeepsStatusAndUsage() {
        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, SessionSchema.DEFAULT_SCHEMA, null);
        String first = SessionKeys.sessionIdOf("u-1@fp-1", "web", "c-1", "seg-1");
        String second = SessionKeys.sessionIdOf("u-1@fp-1", "web", "c-1", "seg-2");

        goals.start(first, "让 CI 变绿", 50_000L);
        goals.addTokens(first, 1200);
        goals.edit(first, "让 CI 变绿并更新文档");

        SessionGoal edited = goals.of(first).orElseThrow();
        assertThat(edited.objective()).isEqualTo("让 CI 变绿并更新文档");
        assertThat(edited.status()).as("改个措辞不该把状态也改了").isEqualTo(GoalStatus.ACTIVE);
        assertThat(edited.tokensUsed())
                .as("先 clear 再 start 的写法会让用量归零——改个措辞把额度还回去了")
                .isEqualTo(1200);
        assertThat(edited.tokenBudget()).isEqualTo(50_000L);
        assertThat(edited.revision()).isEqualTo(2);

        assertThat(goals.of(second))
                .as("/new 换段之后这一行读不到：不需要谁去删，新段的键根本不是这一个")
                .isEmpty();
        assertThat(goals.of(first)).as("旧段那一行还在（等会话 TTL 一起清）").isPresent();

        goals.setStatus(first, GoalStatus.COMPLETE, "推上去了");
        assertThat(goals.setStatus(first, GoalStatus.ACTIVE, null))
                .as("已完成是终态：恢复它等于把一件收尾的事重新打开，那不是恢复、是新建")
                .isEmpty();
        assertThat(goals.start(second, "另一件事", null))
                .as("新段是干净的，可以有自己的目标")
                .isPresent();
    }

    /**
     * V22：会话级额度表没了（ADR-0027 决定五）。
     *
     * <p>这条判据存在的理由是"兼容层"那个诱惑：留着这张表"以防有人要查历史用量"，
     * 下一个人就会往里加一列。历史用量在 {@code audit_event} 的
     * {@code agent.run.finished} 上（票 01 已经把 token 带上）。
     */
    @Test
    @DisplayName("额度不再按会话累计：V22 之后 agent_session_budget 这张表不存在")
    void theSessionBudgetTableIsGone() {
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'claw4j_run'"
                        + " and table_name = 'agent_session_budget'", Integer.class))
                .as("表还在就说明有人又把它建回来了，或者 V22 没跑")
                .isZero();
    }

    @Test
    @DisplayName("审批表只认三种状态；有结论必须有决定时间，还挂着的不能有")
    void approvalRequestPinsItsStatusAndDecision() {
        jdbc.update("insert into claw4j_run.approval_request"
                        + " (id, owner, tool_name, status, expires_at)"
                        + " values ('ap-pending', 'u-1', 'sendEmail', 'pending', now() + interval '10 minutes')");

        // decided_at 一并写上，是为了让这条只可能撞状态那一条约束：
        // 约束的检查顺序不由我们决定，不写它的话，'maybe' 会先撞"有结论必须有决定时间"，
        // 于是这条用例红在另一条约束上，看起来像"状态约束不存在"。
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, expires_at, decided_at)"
                + " values ('ap-maybe', 'u-1', 'sendEmail', 'maybe',"
                + " now() + interval '10 minutes', now())"))
                .as("未知状态必须被拒")
                .hasMessageContaining("ck_approval_request_status");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, expires_at)"
                + " values ('ap-granted-no-time', 'u-1', 'sendEmail', 'granted', now() + interval '10 minutes')"))
                .as("批了但没批：没有决定时间的 granted 追不回来")
                .hasMessageContaining("ck_approval_request_decided");
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, expires_at, decided_at)"
                + " values ('ap-pending-with-time', 'u-1', 'sendEmail', 'pending',"
                + " now() + interval '10 minutes', now())"))
                .as("还挂着的请求不该有决定时间")
                .hasMessageContaining("ck_approval_request_decided");
    }

    /**
     * 有效批准的口径：**这个人 + 这个工具 + 这一种参数 + 还没过期 + 是批不是拒 + 还没兑现**，
     * 六条同时成立。
     *
     * <p>每一条都单独立了一行，因为它们任意一条写错都只表现成"拦了本该放行的"或者
     * "放了本该拦的"，两种都不报错。过期那一条尤其容易写成"读出来再在 Java 里比"——
     * 那样判的是 JVM 的时钟，而 {@code expires_at} 是库的时间。
     *
     * <p><b>参数那一条是 P02 的核心</b>：只按工具名批的话，"批了发一封给张三的邮件"
     * 同时放行了"发给全公司"。指纹变了就是另一次，必须重新批。
     */
    @Test
    @DisplayName("审批按人 + 工具 + 参数命中：过期、别人的、被拒的、已兑现的都不算")
    void onlyAnUnexpiredUnconsumedGrantOfThisPersonAndArgsCounts() {
        ApprovalLedger ledger = new JdbcApprovalLedger(jdbc, Duration.ofMinutes(10).toMillis());
        granted("ap-ok", "u-1", "sendEmail", DIGEST, "10 minutes");
        granted("ap-expired", "u-1", "runScript", DIGEST, "-1 minute");
        granted("ap-other-person", "u-2", "sendEmail", DIGEST, "10 minutes");
        granted("ap-other-args", "u-1", "sendEmail", OTHER_DIGEST, "10 minutes");
        jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, args_digest, status, reason, expires_at, decided_at)"
                + " values ('ap-denied', 'u-1', 'draftScript', '" + DIGEST + "', 'denied',"
                + " '这批脚本不该进生产', now() + interval '10 minutes', now())");

        // 参数不同是另一次：那条只放行它自己那一种参数
        assertThat(ledger.redeem("u-1", "sendEmail", OTHER_DIGEST)).get()
                .extracting(ApprovalLedger.Grant::id).isEqualTo("ap-other-args");
        assertThat(ledger.redeem("u-1", "sendEmail", DIGEST)).get()
                .extracting(ApprovalLedger.Grant::id).isEqualTo("ap-ok");
        assertThat(ledger.redeem("u-1", "sendEmail", DIGEST))
                .as("一次用完即废：同一条批准不能放行第二次执行").isEmpty();
        assertThat(ledger.redeem("u-1", "runScript", DIGEST)).as("过期按没有批准处理").isEmpty();
        assertThat(ledger.redeem("u-3", "sendEmail", DIGEST)).as("别人的批准不算自己的").isEmpty();
        assertThat(ledger.redeem("u-1", "draftScript", DIGEST)).as("被拒的请求不是批准").isEmpty();
    }

    /**
     * 登记一条待批请求：过期点由库的时钟算，摘要原样存下来（审批人要能看见自己批的是什么）。
     *
     * <p>过期点这里断言"大致在十分钟之后"而不是精确值：它是 {@code now() + interval}，
     * 精确值只有库自己知道。判据取的是"它确实按配置算，而不是一个写死的默认值"——
     * 传 3 分钟进去，就该出来 3 分钟。
     */
    @Test
    @DisplayName("登记的待批请求带上参数摘要，过期点按配置算，不按表上的默认值")
    void anAskedApprovalCarriesArgsSummaryAndConfiguredExpiry() {
        ApprovalLedger ledger = new JdbcApprovalLedger(jdbc, Duration.ofMinutes(3).toMillis());

        String id = ledger.ask(new ApprovalLedger.Ask("run-x", "u-1", "sendEmail", DIGEST,
                "{\"to\":\"zhang@example.com\"}", null)).orElseThrow();

        Long minutes = jdbc.queryForObject("select round(extract(epoch from (expires_at - now())) / 60)"
                + " from claw4j_run.approval_request where id = ?", Long.class, id);
        assertThat(minutes).as("传 3 分钟就该是 3 分钟，不是表上那个写死的默认值").isEqualTo(3L);
        assertThat(jdbc.queryForObject("select args_summary from claw4j_run.approval_request"
                + " where id = ?", String.class, id)).contains("zhang@example.com");
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request"
                + " where id = ?", String.class, id)).isEqualTo("pending");
    }

    // ---------------------------------------------------------------- 审计（P07）

    @Test
    @DisplayName("审计按 30 天与 10 万行清：一个按时钟，一个按账本序号")
    void theAuditLedgerPrunesByAgeAndSize() {
        jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status, actor, run_id, at)"
                + " values (gen_random_uuid(), 'e-old', 'agent_run', 'agent.run.started',"
                + " 'started', 'u-1', 'run-1', now() - interval '31 days')");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status, actor, run_id)"
                + " values (gen_random_uuid(), 'e-fresh', 'agent_run', 'agent.run.started',"
                + " 'started', 'u-1', 'run-2')");

        AuditLedger.Report aged = new AuditLedger(jdbc, null).prune();

        assertThat(aged.expired()).as("31 天前那一条").isEqualTo(1);
        assertThat(jdbc.queryForList("select event_id from claw4j_run.audit_event", String.class))
                .as("30 天内不动").containsExactly("e-fresh");

        // ---- 10 万行上限。一条 SQL 插进去，比循环十万次快得多，也比 sleep 诚实。
        jdbc.update("delete from claw4j_run.audit_event");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status, run_id)"
                + " select gen_random_uuid(), 'bulk-' || g, 'agent_run', 'agent.run.started',"
                + " 'started', 'run-bulk' from generate_series(1, 100001) g");

        Long highest = jdbc.queryForObject("select max(seq) from claw4j_run.audit_event", Long.class);

        AuditLedger.Report capped = new AuditLedger(jdbc, null).prune();

        assertThat(capped.overLimit()).as("多出来的正好一条").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.audit_event", Integer.class))
                .as("留下 10 万条")
                .isEqualTo(100000);
        assertThat(jdbc.queryForObject("select min(seq) from claw4j_run.audit_event", Long.class))
                .as("留下的是 seq 最大的 10 万条：按 at 删的话同一秒里会随机丢，"
                        + "而账本上限本来该是一个确定的位置")
                .isEqualTo(highest - 99_999);
        assertThat(jdbc.queryForObject("select max(seq) from claw4j_run.audit_event", Long.class))
                .isEqualTo(highest);
    }

    @Test
    @DisplayName("错误码是 OpenClaw 的九项闭合词表：枚举里的值都写得进去，旧词写不进去")
    void theErrorCodeVocabularyIsClosed() {
        // 词表逐字照 OpenClaw 的 AuditEventErrorCodeSchema。这条断言的理由不是"多一条约束"，
        // 而是"按 error_code 过滤时词表外的值一个都命中不了，而它的缺席看起来像没发生过"。
        //
        // 两头都要验。只验"旧词写不进去"的话，把枚举值改成旧词，这条测试照样是绿的——
        // 它压根没读枚举；只验"枚举里的都写得进去"的话，约束被删了也照样是绿的。
        for (AuditErrorCode code : AuditErrorCode.values()) {
            boolean runLevel = code.column().startsWith("run_");
            jdbc.update("insert into claw4j_run.audit_event"
                            + " (id, event_id, kind, action, status, error_code, run_id)"
                            + " values (gen_random_uuid(), ?, ?, ?, 'blocked', ?, 'run-1')",
                    "e-" + code.column(),
                    runLevel ? "agent_run" : "tool_action",
                    runLevel ? "agent.run.finished" : "tool.action.finished",
                    code.column());
        }

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status, error_code, run_id)"
                + " values (gen_random_uuid(), 'e-old-word', 'tool_action', 'tool.action.finished',"
                + " 'blocked', 'policy_denied', 'run-1')"))
                .as("这是换词表之前的值，现在不在词表里")
                .hasMessageContaining("ck_audit_event_error_code");

        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_run.audit_event where error_code is not null",
                Integer.class))
                .as("枚举有几个值，就该有几行落下去")
                .isEqualTo(AuditErrorCode.values().length);
    }

    @Test
    @DisplayName("run_id 必填：没有轮次的事件落不下去，账本连 SQL 都不发")
    void anAuditEventWithoutARunIdDoesNotLand() {
        // OpenClaw 的 AuditEventSchema.runId 是 NonEmptyString。这一条两头都验：
        // 库拒绝空值；账本在没有编号时直接跳过（否则每一行都撞约束，write_errors 会一直涨，
        // 把"没有归属"错报成"库坏了"）。
        jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status, run_id)"
                + " values (gen_random_uuid(), 'e-with-run', 'agent_run', 'agent.run.started',"
                + " 'started', 'run-1')");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status)"
                + " values (gen_random_uuid(), 'e-no-run', 'agent_run', 'agent.run.started', 'started')"))
                .as("没有 run_id 的行会被库拒绝")
                .hasMessageContaining("run_id");

        // 断言计数而不是行数：行数在"跳过"和"撞约束失败"两种情况下都一样（都是 1），
        // 只有 skipped 与 write_errors 的分布能说明是哪一种。这条区分就是"没有归属"与"库坏了"的边界。
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AuditLedger(jdbc, registry).record(AuditEvent.runStarted(AuditEvent.Scope.empty()));

        assertThat(registry.find(AuditLedger.SKIPPED).counter())
                .as("计入了 skipped").isNotNull();
        assertThat(registry.find(AuditLedger.SKIPPED).counter().count()).isEqualTo(1.0);
        assertThat(registry.find(AuditLedger.WRITE_ERRORS).counter())
                .as("不是写失败——把它记成故障会让一次正常的子链调用看起来像库挂了")
                .isNull();
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.audit_event", Integer.class))
                .as("那一行没有落库").isEqualTo(1);
    }

    private void granted(String id, String owner, String tool, String digest, String offset) {
        jdbc.update("insert into claw4j_run.approval_request"
                        + " (id, owner, tool_name, args_digest, status, expires_at, decided_at, decided_by)"
                        + " values (?, ?, ?, ?, 'granted', now() + interval '" + offset + "', now(),"
                        + " 'approver-1')",
                id, owner, tool, digest);
    }
}
