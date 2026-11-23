package io.github.claw4j.app;

import io.github.claw4j.agent.approval.JdbcApprovals;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditProperties;
import io.github.claw4j.agent.audit.AuditLedger;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.JdbcSessionGoalStore;
import io.github.claw4j.agent.goal.SessionGoal;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.intent.JdbcStandingIntents;
import io.github.claw4j.agent.intent.StandingIntent;
import io.github.claw4j.agent.intent.StandingIntents;
import io.github.claw4j.agent.progress.JdbcProgressCards;
import io.github.claw4j.agent.progress.ProgressCards;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionScopeProperties;

import java.time.Instant;
import java.util.UUID;
import io.github.claw4j.agent.session.SessionEntryStore;
import io.github.claw4j.agent.session.JdbcSessionEntryStore;
import io.github.claw4j.agent.session.SessionResetProperties;
import io.github.claw4j.agent.session.SessionSchema;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.port.Approvals;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话目标、审批、审计那几条 SQL 真跑在 Postgres 上（A01）。
 *
 * <p>主链路那份（{@code ChatPipelineTest}）用内存版存储，验的是什么时候开 run、什么时候收尾。
 * 这里验的是库真的会落成那样：表在 {@code claw4j_run}、{@code claw4j_session} 下而不是 public，
 * 状态受 check 约束，同一 run 内 {@code seq} 唯一。这几条错了都不报错，写错 schema 是插入失败
 * 被降级吞掉，写错约束是什么状态都存得进去，而审计存在的理由恰恰是能答出做到第几步。
 *
 * <p>迁移也由这里跑：表是 {@code V6} 建的，用 {@link Flyway} 而不是手写 DDL。
 */
@Testcontainers
class PersistenceSchemaTest {

    /** 用 pgvector 镜像：同一批迁移里的 {@code V5__rag.sql} 要 {@code create extension vector}。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    /**
     * 两个形状合法（64 位十六进制）但不同的指纹。
     *
     * <p>用真形状而不是 {@code "d1"}：基线给这一列定了 {@code varchar(64)}，
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
        // 节点与段分两张表（ADR-0029）：删节点会级联带走 window 与冷值。
        // 通道身份是另一回事——它不随节点死，所以单独清。
        jdbc.update("delete from claw4j_session.session_nodes");
        jdbc.update("delete from claw4j_session.conversations");
        // 段的消息在框架那两张表里，没有指向节点的外键，得自己清。
        jdbc.update("delete from claw4j_session.ai_session");
        jdbc.update("delete from claw4j_run.approval_request");
        // 审计也要清：上限那条用例会插十万行，留着会让"30 天只留一条"的断言看到别人的行。
        jdbc.update("delete from claw4j_run.audit_event");
        // allow-always 的条目是部署级配置，跨用例串起来会让"第一次要问"的断言直接绿掉。
        jdbc.update("delete from claw4j_run.exec_allowlist");
        // 进度卡与意图跟着会话走：不清的话，上一条用例的卡会出现在下一条的读回里。
        jdbc.update("delete from claw4j_session.session_progress_cards");
        jdbc.update("delete from claw4j_session.standing_intents");
    }

    @Test
    @DisplayName("V24 之后 claw4j_run 里只剩四张表：run 与 step 都不在了")
    void migrationLeavesOnlyTheTablesThatSurvived() {
        // 审计视图长在 step 上，随 step 一起删（V24）；agent_session_budget 在 V22 已经删了。
        // 这条断言存在的理由是"兼容层"那个诱惑：留一张空表"以防有人要查历史"，
        // 下一个人就会往里加一列。历史在 audit_event 上，不在 step 上。
        // exec_allowlist：它不属于"某一轮"，是部署级的 allow-always 条目
        // （ADR-0030），所以留在这个 schema 里而不是跟 run 一起走。
        // execution_decision_fact：审批与执行的交接（ADR-0037 票 05），一轮之内的一次调用一条，
        // 取走即删——它不是"历史"，所以不会长大到需要保留期之外的东西。
        // audit_identity_key：消息审计的假名密钥（ADR-0037 票 04），单行——换密钥等于换一套假名，
        // 所以它必须只有一行，放在这里也提醒读的人"这张表不该长"。
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'claw4j_run'"
                        + " order by table_name", String.class))
                .as("run 只是一轮的句柄（ADR-0027），不落表")
                .containsExactly("agent_schedule", "approval_request", "audit_event",
                        "audit_identity_key", "exec_allowlist", "execution_decision_fact");

        // 名字也是契约（ADR-0038）：这一格是"哪一次执行"，不是"哪一个 run"。
        // 三张表都叫 execution_id，run_id 一个都不剩——留着旧名的话，读的人会以为那是
        // 它那个"可能跨轮共享的 runId"，而我们从没有过那一层。
        assertThat(jdbc.queryForList(
                "select table_name || '.' || column_name from information_schema.columns"
                        + " where table_schema = 'claw4j_run' and column_name in ('run_id', 'execution_id')"
                        + " order by table_name", String.class))
                .as("三张表都是 execution_id，run_id 一个都不剩")
                .containsExactly("approval_request.execution_id", "audit_event.execution_id",
                        "execution_decision_fact.execution_id");
    }

    @Test
    @DisplayName("审批行上有一列冻结计划，jsonb 且允许为空")
    void approvalRowCarriesAFrozenPlan() {
        // 允许为空是必须的：除 exec 之外的审批（工具名 + 参数指纹）没有"一条命令"可冻结，
        // 非 exec 的审批不写它。为空时按"说不清批的是哪条命令"处理——不放行。
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns"
                + " where table_schema = 'claw4j_run' and table_name = 'approval_request'"
                + " and column_name = 'frozen_plan'", String.class))
                .isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns"
                + " where table_schema = 'claw4j_run' and table_name = 'approval_request'"
                + " and column_name = 'frozen_plan'", String.class))
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("六态之外、空白目标、0 预算由 Java 拒绝；一个会话第二行仍由主键拒绝")
    void goalsAreSessionStateWithAClosedVocabulary() {
        insertGoalRow("k-a", "active", "让 CI 变绿", null);

        // 目标搬进 entry_json 之后，库上没有 CHECK 了（ADR-0029）：JSON 里写不出
        // "status in (...)" 还能跟着枚举一起改。所以这三条判据挪到了 Java 侧，断言的对象
        // 从约束名变成了抛出的那句话——否则"库里不拦了"这件事没人看着。
        assertThatThrownBy(() -> GoalStatus.fromColumn("running"))
                .as("六态是闭合词表：写成 running 会被拒绝，而不是变成一个没人认得的状态")
                .hasMessageContaining("未知的目标状态");
        assertThatThrownBy(() -> new SessionGoal("k-c", 1, UUID.randomUUID(), "   ",
                GoalStatus.ACTIVE, Instant.now(), Instant.now(), 0, true, 0, null, 0,
                null, null, null, null, null, null))
                .as("空白目标不是空目标，是存了一个空格")
                .hasMessageContaining("目标内容不能为空");
        assertThatThrownBy(() -> new SessionGoal("k-d", 1, UUID.randomUUID(), "x",
                GoalStatus.ACTIVE, Instant.now(), Instant.now(), 0, true, 0, 0L, 0,
                null, null, null, null, null, null))
                .as("0 预算的意思是「一点额度都不给」，不限额的正确写法是不给预算")
                .hasMessageContaining("预算必须是正数");
        assertThatThrownBy(() -> insertGoalRow("k-a", "active", "同一个键再来一行", null))
                .as("主键就是「一个会话节点一行」：目标挂在节点上，也就没有第二行可插")
                .hasMessageContaining("session_nodes_pkey");
    }

    @Test
    @DisplayName("goalId 挡住旧按钮：清掉再建之后，拿旧 id 改不动新目标")
    void aStaleGoalIdCannotChangeTheReplacement() {
        Instant now = Instant.parse("2026-11-09T02:00:00Z");
        String key = SessionKeys.conversationKeyOf("u-1@fp-1", "web", "c-1");
        SessionEntryStore entries = new JdbcSessionEntryStore(jdbc, SessionSchema.DEFAULT_SCHEMA);
        entries.segmentOf(new SessionEntryStore.Key(key, "u-1@fp-1", "web", "c-1", true),
                new SessionEntryStore.Rotation("seg-1", now, now.minusSeconds(7200),
                        now.minusSeconds(86_400), false, false));

        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, SessionSchema.DEFAULT_SCHEMA, null);
        goals.start(key, "把三张表的口径对齐", 100L);
        UUID old = goals.of(key).orElseThrow().id();
        assertThat(goals.clear(key, old)).isTrue();

        goals.start(key, "另一件事", null);
        UUID current = goals.of(key).orElseThrow().id();
        assertThat(current).isNotEqualTo(old);

        assertThat(goals.setStatus(key, old, GoalStatus.COMPLETE, "拿旧 id 按下"))
                .as("清掉再建之后，旧界面那个按钮带着上一个 id——它改不到新目标")
                .isEmpty();
        assertThat(goals.edit(key, old, "改个说法")).isEmpty();
        assertThat(goals.clear(key, old))
                .as("同一个理由：删也要认 id，否则旧按钮能把新目标删掉")
                .isFalse();
        assertThat(goals.of(key).orElseThrow().objective())
                .as("目标一个字都没动")
                .isEqualTo("另一件事");
    }

    @Test
    @DisplayName("重复 complete 保留首次完成时间；resume 之后再次达成才记新的")
    void completingTwiceKeepsTheFirstCompletionTime() {
        Instant now = Instant.parse("2026-11-09T02:00:00Z");
        String key = SessionKeys.conversationKeyOf("u-1@fp-1", "web", "c-1");
        SessionEntryStore entries = new JdbcSessionEntryStore(jdbc, SessionSchema.DEFAULT_SCHEMA);
        entries.segmentOf(new SessionEntryStore.Key(key, "u-1@fp-1", "web", "c-1", true),
                new SessionEntryStore.Rotation("seg-1", now, now.minusSeconds(7200),
                        now.minusSeconds(86_400), false, false));

        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, SessionSchema.DEFAULT_SCHEMA, null);
        goals.start(key, "把三张表的口径对齐", null);
        UUID id = goals.of(key).orElseThrow().id();

        goals.setStatus(key, id, GoalStatus.COMPLETE, "推上去了");
        Instant first = goals.of(key).orElseThrow().completedAt();
        assertThat(first).isNotNull();

        assertThat(goals.setStatus(key, id, GoalStatus.COMPLETE, "又按了一次"))
                .as("它的规则允许再 complete（幂等），只挡 complete → paused / blocked")
                .isPresent();
        assertThat(goals.of(key).orElseThrow().completedAt())
                .as("重复 complete 不覆盖首次完成时间——那一次达成的时刻是历史，不是时间戳")
                .isEqualTo(first);

        goals.resume(key, id, "还有一件事");
        assertThat(goals.of(key).orElseThrow().completedAt())
                .as("resume 把完成时刻清掉：完成属于上一次达成")
                .isNull();
        goals.setStatus(key, id, GoalStatus.COMPLETE, "第二次达成");
        assertThat(goals.of(key).orElseThrow().completedAt())
                .as("再一次达成记新的时刻")
                .isNotNull();
    }

    @Test
    @DisplayName("跨天边界在读时算：at-hour 4 改成 9 之后，同一条旧行按新边界判过期")
    void dailyBoundaryIsEvaluatedAtReadTime() {
        java.time.ZoneId shanghai = java.time.ZoneId.of("Asia/Shanghai");
        // 段开始于 11-09 05:00（+08），现在 11-09 10:00（+08）。
        Instant startedAt = Instant.parse("2026-11-08T21:00:00Z");
        Instant now = Instant.parse("2026-11-09T02:00:00Z");
        String key = SessionKeys.conversationKeyOf("u-1@fp-1", "web", "c-1");
        SessionEntryStore entries = new JdbcSessionEntryStore(jdbc, SessionSchema.DEFAULT_SCHEMA);
        SessionEntryStore.Key row = new SessionEntryStore.Key(key, "u-1@fp-1", "web", "c-1", true);

        entries.segmentOf(row, new SessionEntryStore.Rotation("seg-1", startedAt,
                startedAt.minusSeconds(1), startedAt.minusSeconds(86_400), true, false));

        // at-hour=4：边界 04:00，段开始 05:00 不早于它 → 不换段。
        assertThat(entries.segmentOf(row, new SessionEntryStore.Rotation("seg-new", now,
                startedAt.minusSeconds(1),
                new SessionResetProperties(null, 4, 120).dailyResetAt(now, shanghai), true, false)))
                .contains("seg-1");

        // at-hour=9：边界 09:00，段开始 05:00 早于它 → 换段。
        assertThat(entries.segmentOf(row, new SessionEntryStore.Rotation("seg-new", now,
                startedAt.minusSeconds(1),
                new SessionResetProperties(null, 9, 120).dailyResetAt(now, shanghai), true, false)))
                .as("边界若在写的时候就算好（存 day_started），这一行会带着按 4 点算的那一天，"
                        + "改配置之后该换的段不换")
                .contains("seg-new");
    }

    @Test
    @DisplayName("token_start_fresh=false：下一次真实用量被采纳成起点，旧用量保持；null 什么都不改")
    void aStaleTokenStartIsAdoptedOnTheNextRealUsage() {
        String key = SessionKeys.conversationKeyOf("u-1@fp-1", "web", "c-1");
        insertGoalRow(key, "active", "把三张表的口径对齐", 100L, false, 40L);

        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, SessionSchema.DEFAULT_SCHEMA, null);
        assertThat(goals.of(key).orElseThrow().tokenStartFresh()).isFalse();

        goals.account(key, null);
        assertThat(goals.of(key).orElseThrow().tokensUsed())
                .as("拿不到用量（null）与「这一轮花了 0」是两件事：前者不能拿来重算")
                .isEqualTo(40);

        goals.account(key, 5L);
        SessionGoal adopted = goals.of(key).orElseThrow();
        assertThat(adopted.tokenStart())
                .as("下一次真实用量到来时被采纳成起点（它的 shouldAdoptFreshStart）")
                .isEqualTo(5);
        assertThat(adopted.tokenStartFresh()).isTrue();
        assertThat(adopted.tokensUsed())
                .as("旧用量保持：建目标之前的花费不算给它")
                .isEqualTo(40);

        goals.account(key, 7L);
        assertThat(goals.of(key).orElseThrow().tokensUsed())
                .as("起点定了之后按快照差算：累计 12 - 起点 5 = 7，小于旧值 40，取 40")
                .isEqualTo(40);
    }

    private void insertGoalRow(String key, String status, String objective, Long budget) {
        insertGoalRow(key, status, objective, budget, true, 0);
    }

    /**
     * 插一行带目标的节点。
     *
     * <p>目标在 {@code entry_json.goal} 里（ADR-0029），不再摊成十七个列，所以这里用
     * {@code jsonb_build_object} 拼。列上那几个 CHECK（六态、空白目标、正预算）跟着没了，
     * 校验挪到 {@code SessionGoal} 的构造器与 {@code GoalStatus.fromColumn} 上。
     */
    private void insertGoalRow(String key, String status, String objective, Long budget,
                               boolean startFresh, long tokensUsed) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                insert into claw4j_session.session_nodes
                       (session_key, current_session_id, entry_json, session_started_at,
                        last_interaction_at, created_at, updated_at, entry_valid)
                values (?, ?, jsonb_build_object(
                            'sessionId', ?, 'updatedAt', ?, 'totalTokens', ?,
                            'goal', jsonb_build_object(
                                'schemaVersion', 1, 'id', ?, 'objective', ?, 'status', ?,
                                'createdAt', ?, 'updatedAt', ?, 'tokenStart', 0,
                                'tokenStartFresh', ?, 'tokensUsed', ?, 'tokenBudget', cast(? as bigint),
                                'continuationTurns', 0)),
                        now(), now(), now(), now(), 1)
                """,
                key, key + ":seg-1", key + ":seg-1", now, 0L,
                UUID.randomUUID().toString(), objective, status, now, now,
                startFresh, tokensUsed, budget);
    }

    @Test
    @DisplayName("通道会话登记成幂等的一行：这一段的主对话是 primary，另一条通道挂成 participant")
    void aConversationIsCataloguedOnceAndBoundToTheSegment() {
        Instant now = Instant.parse("2026-11-09T02:00:00Z");
        // dm-scope 默认 main：两条通道的私聊落到同一个节点，这正是"多个会话共用一段"的场合。
        String key = SessionKeys.conversationKeyOf(
                "u-1@fp-1", true, "feishu", "c-feishu", SessionScopeProperties.defaults());
        SessionEntryStore entries = new JdbcSessionEntryStore(jdbc, SessionSchema.DEFAULT_SCHEMA);

        entries.segmentOf(new SessionEntryStore.Key(key, "u-1@fp-1", "feishu", "c-feishu", true),
                new SessionEntryStore.Rotation("seg-1", now, now.minusSeconds(7200),
                        now.minusSeconds(86_400), false, false));
        // 同一个人的网页私聊：节点相同、段没到期，所以落在 seg-1 上，挂成 participant。
        entries.segmentOf(new SessionEntryStore.Key(key, "u-1@fp-1", "web", "c-web", true),
                new SessionEntryStore.Rotation("seg-2", now.plusSeconds(5), now.minusSeconds(7200),
                        now.minusSeconds(86_400), false, false));

        String feishuRef = SessionKeys.conversationRefOf("feishu", "direct", "c-feishu");
        assertThat(jdbc.queryForList(
                "select conversation_id from claw4j_session.conversations order by conversation_id",
                String.class))
                .as("两条通道各一行，id 由通道身份派生——不是随机 uuid，所以登记是幂等的")
                .containsExactlyInAnyOrder(
                        feishuRef,
                        SessionKeys.conversationRefOf("web", "direct", "c-web"));

        assertThat(jdbc.queryForList(
                "select conversation_id || ':' || role from claw4j_session.session_conversations"
                        + " order by role", String.class))
                .as("先建段的飞书是这一段的主对话，后到的网页是 participant——"
                        + "一个段只有一个 primary，这正是「所有私聊共用主会话」的落法")
                .containsExactlyInAnyOrder(feishuRef + ":primary",
                        SessionKeys.conversationRefOf("web", "direct", "c-web") + ":participant");

        assertThat(jdbc.queryForObject(
                "select primary_conversation_id from claw4j_session.session_windows"
                        + " where session_id = ?", String.class, key + ":seg-1"))
                .as("window 上这一列是权威判据（OpenClaw 的 currentSessionKey 认它），"
                        + "而不是只看 role 那一列")
                .isEqualTo(feishuRef);

        // ---- 同一对 (段, 会话) 再来一次：一行都不该多出来。
        entries.segmentOf(new SessionEntryStore.Key(key, "u-1@fp-1", "feishu", "c-feishu", true),
                new SessionEntryStore.Rotation("seg-9", now.plusSeconds(9), now.minusSeconds(7200),
                        now.minusSeconds(86_400), false, false));

        assertThat(jdbc.queryForObject("select count(*) from claw4j_session.conversations",
                Long.class)).as("重复登记只刷新，不新增").isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_session.session_conversations", Long.class))
                .as("主键是 (段, 会话, role)：同一对换个 role 再插会多出一行，"
                        + "而 currentSessionKey 只认其中一行")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("换段就是清空同一行的目标：行还在、段变了、goal 没了；edit 不动状态与用量")
    void aFreshSegmentClearsTheGoalOnTheSameRow() {
        Instant now = Instant.parse("2026-11-09T02:00:00Z");
        String key = SessionKeys.conversationKeyOf("u-1@fp-1", "web", "c-1");
        SessionEntryStore entries = new JdbcSessionEntryStore(jdbc, SessionSchema.DEFAULT_SCHEMA);
        SessionEntryStore.Key row = new SessionEntryStore.Key(key, "u-1@fp-1", "web", "c-1", true);
        entries.segmentOf(row, new SessionEntryStore.Rotation("seg-1", now,
                now.minusSeconds(7200), now.minusSeconds(86_400), false, false));

        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, SessionSchema.DEFAULT_SCHEMA, null);
        goals.start(key, "让 CI 变绿", 50_000L);
        UUID id = goals.of(key).orElseThrow().id();
        goals.account(key, 1200L);
        goals.edit(key, id, "让 CI 变绿并更新文档");

        SessionGoal edited = goals.of(key).orElseThrow();
        assertThat(edited.objective()).isEqualTo("让 CI 变绿并更新文档");
        assertThat(edited.status()).as("改个措辞不该把状态也改了").isEqualTo(GoalStatus.ACTIVE);
        assertThat(edited.tokensUsed())
                .as("先 clear 再 start 的写法会让用量归零——改个措辞把额度还回去了")
                .isEqualTo(1200);
        assertThat(edited.tokenBudget()).isEqualTo(50_000L);
        assertThat(edited.tokenStart())
                .as("预算窗口从建目标那一刻的会话累计用量起算（它 tokenStart 的语义）")
                .isZero();

        java.time.Instant createdAt = jdbc.queryForObject(
                "select created_at from claw4j_session.session_nodes where session_key = ?",
                java.sql.Timestamp.class, key).toInstant();

        assertThat(jdbc.queryForObject(
                "select (extract(epoch from updated_at) * 1000)::bigint"
                        + " - (entry_json ->> 'updatedAt')::bigint"
                        + " from claw4j_session.session_nodes where session_key = ?",
                Long.class, key))
                .as("列是 JSON 的投影：目标写一次，两处必须是同一个时刻——"
                        + "各写各的，索引与真相从第一次记用量起就分叉")
                .isZero();

        // ---- /new：插一行新 window，把 current_session_id 指过去，并清掉 entry_json 里的目标。
        entries.segmentOf(row, new SessionEntryStore.Rotation("seg-2", now.plusSeconds(1),
                now.minusSeconds(7200), now.minusSeconds(86_400), false, true));

        assertThat(goals.of(key))
                .as("换段即无目标：不是「新段读不到」，而是 entry_json 里的 goal 被整份换掉了")
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "select current_session_id from claw4j_session.session_nodes where session_key = ?",
                String.class, key))
                .as("段现在是完整对话键：节点键 + 段，与 ai_session.id 同值")
                .isEqualTo(SessionKeys.sessionIdOf(key, "seg-2"));
        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_session.session_nodes where session_key = ?",
                Long.class, key))
                .as("节点还在——它是会话的锚点，不是目标；删行等于把这一段的存在也抹掉")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select (entry_json ->> 'totalTokens')::bigint"
                        + " from claw4j_session.session_nodes where session_key = ?",
                Long.class, key))
                .as("新段开新窗口：累计用量归零，否则新目标的预算从旧段的账起算")
                .isZero();
        assertThat(jdbc.queryForObject(
                "select previous_session_id from claw4j_session.session_windows"
                        + " where session_id = ?",
                String.class, SessionKeys.sessionIdOf(key, "seg-2")))
                .as("上一段记在新段那一行上（它的 previousSessionId）：还答得出「上一段是什么」")
                .isEqualTo(SessionKeys.sessionIdOf(key, "seg-1"));
        assertThat(jdbc.queryForObject(
                "select account_id from claw4j_session.session_windows where session_id = ?",
                String.class, SessionKeys.sessionIdOf(key, "seg-2")))
                .as("account_id 是「哪台通道账号」，与 channel 是两件事。本仓库没有这一维，"
                        + "它就该是空的；曾经两个参数都传了 channel，库里每一段都在声称"
                        + "自己属于一台名叫 web 的账号")
                .isNull();
        assertThat(jdbc.queryForObject(
                "select created_at from claw4j_session.session_nodes where session_key = ?",
                java.sql.Timestamp.class, key).toInstant())
                .as("节点创建时间跨段不变——它记的是「这个人第一次来」，不是「这一段什么时候开始」")
                .isEqualTo(createdAt);

        assertThat(goals.start(key, "另一件事", null))
                .as("新段可以有自己的目标")
                .isPresent();
        assertThat(goals.setStatus(key, goals.of(key).orElseThrow().id(), GoalStatus.COMPLETE, null))
                .as("达成是终态")
                .isPresent();
        assertThat(goals.setStatus(key, goals.of(key).orElseThrow().id(),
                GoalStatus.ACTIVE, null))
                .as("已完成之后不能直接改回 active，要先 resume")
                .isEmpty();
    }

    /**
     * 提升列为空的行（{@code entry_valid = -1} 那一档）不该把整段对话打死。
     *
     * <p>{@code entry_valid = -1} 那一档的语义就是"行还在、{@code entry_json} 置空、提升列置空"，所以这两个时间
     * 为空是<b>合法状态</b>，不是脏数据。读成 {@code toInstant()} 的话这里是一次 NPE，
     * 而它会被上一层兜底吞成"这一段没有段"：这个人的会话从此不再换段，日志只有第一条，
     * 指标上只是一个不痛不痒的计数。
     */
    @Test
    @DisplayName("段的两个时间为空（entry 被清空过的那一档）：不抛，按陈旧处理并把时间写回去")
    void aRowWithEmptyTimesIsTreatedAsStaleInsteadOfBlowingUp() {
        String key = SessionKeys.conversationKeyOf("u-1@fp-1", "web", "c-1");
        jdbc.update("insert into claw4j_session.session_nodes"
                        + " (session_key, current_session_id, entry_json, session_started_at,"
                        + "  last_interaction_at, created_at, updated_at, entry_valid)"
                        + " values (?, ?, '{}'::jsonb, null, null, now(), now(), -1)",
                key, SessionKeys.sessionIdOf(key, "seg-old"));

        Instant now = Instant.parse("2026-11-10T02:00:00Z");
        String segment = new JdbcSessionEntryStore(jdbc, SessionSchema.DEFAULT_SCHEMA).segmentOf(
                new SessionEntryStore.Key(key, "u-1@fp-1", "web", "c-1", true),
                new SessionEntryStore.Rotation("seg-new", now, now.minusSeconds(7200),
                        now.minusSeconds(86_400), true, false)).orElseThrow();

        assertThat(segment)
                .as("空时间按「很久没动」判，于是换一段——那一行就自己好了")
                .isEqualTo("seg-new");
        assertThat(jdbc.queryForObject(
                "select last_interaction_at from claw4j_session.session_nodes where session_key = ?",
                java.sql.Timestamp.class, key))
                .as("换段时把时间写回去了，不是每轮重新判一次空")
                .isNotNull();
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
    @DisplayName("审批表五态与终态列绑在一起：未知状态、批了没时间、过期没说为什么，都进不去")
    void approvalRequestPinsItsStatusAndDecision() {
        jdbc.update("insert into claw4j_run.approval_request"
                        + " (id, owner, tool_name, status, expires_at)"
                        + " values ('ap-pending', 'u-1', 'sendEmail', 'pending', now() + interval '10 minutes')");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, expires_at)"
                + " values ('ap-maybe', 'u-1', 'sendEmail', 'maybe', now() + interval '10 minutes')"))
                .as("未知状态必须被拒")
                // 两条都可能先触发：status 那条词表约束，与 state 那条穷举五态的约束，
                // 对一个未知值都会拒。PG 的检查顺序不属于契约，钉死其中一条会让这条用例
                // 在"约束重排"时无谓地红，而它真正要守的是"未知状态进不来"。
                .hasMessageContaining("ck_approval_request_");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, expires_at)"
                + " values ('ap-allowed-no-time', 'u-1', 'sendEmail', 'allowed',"
                + " now() + interval '10 minutes')"))
                .as("批了但没批：没有结论时间的 allowed 追不回来")
                .hasMessageContaining("ck_approval_request_state");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, expires_at, resolved_at)"
                + " values ('ap-pending-with-time', 'u-1', 'sendEmail', 'pending',"
                + " now() + interval '10 minutes', now())"))
                .as("还挂着的请求不该有结论时间")
                .hasMessageContaining("ck_approval_request_state");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, decision, terminal_reason, resolved_at,"
                + " resolver_kind, expires_at)"
                + " values ('ap-expired-says-user', 'u-1', 'sendEmail', 'expired', 'deny',"
                + " 'user', now(), 'system', now() + interval '10 minutes')"))
                .as("过期只能由超时结束：写 user 等于说有人做过决定")
                .hasMessageContaining("ck_approval_request_state");

        // 只撞 consumed 那一条：allow-always 本来就该有结论时间，但不该带兑现时间。
        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, decision, terminal_reason, resolved_at,"
                + " resolver_kind, consumed_at, consumed_by, expires_at)"
                + " values ('ap-always-consumed', 'u-1', 'sendEmail', 'allowed', 'allow-always',"
                + " 'user', now(), 'channel', now(), 'u-1', now() + interval '10 minutes')"))
                .as("只有 allow-once 会被兑现：长期批准没有「用掉」这回事")
                .hasMessageContaining("ck_approval_request_consumed");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, requested_at, expires_at)"
                + " values ('ap-backwards', 'u-1', 'sendEmail', 'pending', now(),"
                + " now() - interval '1 minute')"))
                .as("过期点早于请求时间：那条批准一出生就过期，只可能是算错了")
                .hasMessageContaining("ck_approval_request_times");
    }

    @Test
    @DisplayName("清扫只把到点的 pending 落成 expired：没到点的不动，已经有结论的也不动")
    void theSweepClosesOnlyOverduePendingRows() {
        // 三条：到点的 pending、没到点的 pending、已经 allowed 但时间同样过去了。
        // 第三条是关键——清扫按 status 过滤，不是按时间过滤；否则一次清扫会把
        // 所有"批准过、也已到期"的行从 allowed 改成 expired，而那条批准原本是有效的。
        jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, requested_at, expires_at)"
                + " values ('ap-due', 'u-1', 'sendEmail', 'pending',"
                + " now() - interval '11 minutes', now() - interval '1 minute')");
        jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, expires_at)"
                + " values ('ap-later', 'u-1', 'sendEmail', 'pending', now() + interval '10 minutes')");
        jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, status, decision, terminal_reason, requested_at,"
                + " resolved_at, resolver_kind, expires_at)"
                + " values ('ap-allowed', 'u-1', 'sendEmail', 'allowed', 'allow-once', 'user',"
                + " now() - interval '11 minutes', now() - interval '10 minutes', 'channel',"
                + " now() - interval '1 minute')");

        int expired = ledger(Duration.ofMinutes(10)).expireDue();

        assertThat(expired).as("只有那一条到点的 pending").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request where id = 'ap-due'",
                String.class)).isEqualTo("expired");
        assertThat(jdbc.queryForMap("select decision, terminal_reason, resolver_kind"
                + " from claw4j_run.approval_request where id = 'ap-due'"))
                .as("过期在结论上是拒绝、原因是超时、判定者是系统")
                .containsEntry("decision", "deny")
                .containsEntry("terminal_reason", "timeout")
                .containsEntry("resolver_kind", "system");
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request where id = 'ap-later'",
                String.class)).as("还没到点的不该被写").isEqualTo("pending");
        assertThat(jdbc.queryForObject("select status from claw4j_run.approval_request where id = 'ap-allowed'",
                String.class)).as("已经有结论的行不动它").isEqualTo("allowed");
    }

    /**
     * 有效批准的口径：这个人、这个工具、这一种参数、还没过期、批不是拒、还没兑现，六条同时成立。
     *
     * <p>每一条都单独立了一行，任意一条写错都只表现成拦了本该放行的，或者放了本该拦的，两种
     * 都不报错。过期那一条尤其容易写成读出来再在 Java 里比，那样判的是 JVM 的时钟，
     * 而 {@code expires_at} 是库的时间。
     *
     * <p>参数那一条是 P02 的核心：只按工具名批的话，批了发一封给张三的邮件同时放行了发给全公司。
     * 指纹变了就是另一次，必须重新批。
     */
    /** 审批装配：这一层验的是 SQL 与约束，名单与卡片都不参与。 */
    private static JdbcApprovals ledger(Duration ttl) {
        return new JdbcApprovals(jdbc, new ApproverRoster(java.util.Set.of("approver-1")),
                null, null, null, java.util.List.of(), ttl.toMillis());
    }

    @Test
    @DisplayName("审批按人 + 工具 + 参数命中：过期、别人的、被拒的、已兑现的都不算")
    void onlyAnUnexpiredUnconsumedGrantOfThisPersonAndArgsCounts() {
        Approvals ledger = ledger(Duration.ofMinutes(10));
        granted("ap-ok", "u-1", "sendEmail", DIGEST, "10 minutes");
        granted("ap-expired", "u-1", "runScript", DIGEST, "-1 minute");
        granted("ap-other-person", "u-2", "sendEmail", DIGEST, "10 minutes");
        granted("ap-other-args", "u-1", "sendEmail", OTHER_DIGEST, "10 minutes");
        jdbc.update("insert into claw4j_run.approval_request"
                + " (id, owner, tool_name, args_digest, status, decision, terminal_reason,"
                + " reason, resolved_at, resolver_kind, expires_at)"
                + " values ('ap-denied', 'u-1', 'draftScript', '" + DIGEST + "', 'denied', 'deny',"
                + " 'user', '这批脚本不该进生产', now(), 'channel', now() + interval '10 minutes')");

        // 参数不同是另一次：那条只放行它自己那一种参数
        assertThat(ledger.redeem("u-1", "sendEmail", OTHER_DIGEST)).get()
                .extracting(Approvals.Grant::id).isEqualTo("ap-other-args");
        assertThat(ledger.redeem("u-1", "sendEmail", DIGEST)).get()
                .extracting(Approvals.Grant::id).isEqualTo("ap-ok");
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
        Approvals ledger = ledger(Duration.ofMinutes(3));

        String id = ledger.ask(new Approvals.Ask("run-x", "u-1", "sendEmail", DIGEST,
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
                + " (event_id, kind, action, status, actor, execution_id, source_id, source_sequence, at)"
                + " values ('e-old', 'agent_run', 'agent.run.started',"
                + " 'started', 'u-1', 'run-1', 'legacy:e-old', 1, now() - interval '31 days')");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, actor, execution_id, source_id, source_sequence)"
                + " values ('e-fresh', 'agent_run', 'agent.run.started',"
                + " 'started', 'u-1', 'run-2', 'legacy:e-fresh', 1)");

        AuditLedger.Report aged = new AuditLedger(jdbc, null).prune();

        assertThat(aged.expired()).as("31 天前那一条").isEqualTo(1);
        assertThat(jdbc.queryForList("select event_id from claw4j_run.audit_event", String.class))
                .as("30 天内不动").containsExactly("e-fresh");

        // ---- 10 万行上限。一条 SQL 插进去，比循环十万次快得多。
        jdbc.update("delete from claw4j_run.audit_event");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, actor, source_id, source_sequence)"
                + " select 'bulk-' || g, 'agent_run', 'agent.run.started',"
                + " 'started', 'run-bulk', 'agent-bulk', 'bulk:' || g, 1"
                + " from generate_series(1, 100001) g");

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
        // 词表逐字照 OpenClaw 的 {@code AuditEventErrorCodeSchema}
        // （{@code packages/gateway-protocol/src/schema/audit.ts:27}）。这条断言的理由不是多一条
        // 约束，而是按 error_code 过滤时词表外的值一个都命中不了，而它的缺席看起来像没发生过。
        //
        // 两头都要验。只验旧词写不进去的话，把枚举值改成旧词，这条测试照样是绿的，它压根没读枚举；
        // 只验枚举里的都写得进去的话，约束被删了也照样是绿的。
        for (AuditErrorCode code : AuditErrorCode.values()) {
            if (code.column().startsWith("message_")) {
                // 消息那三个码只出现在 kind=message 的行上（ADR-0037 票 04）：下面单独试。
                continue;
            }
            boolean runLevel = code.column().startsWith("run_");
            // 状态由码推出来，而不是一律 blocked：V11 起终态与码必须配对（ADR-0037 票 01），
            // 一律写 blocked 的话除了 run_blocked / tool_blocked 全都撞约束。
            String status = code.column().endsWith("_failed") ? "failed"
                    : code.column().endsWith("_cancelled") ? "cancelled"
                    : code.column().endsWith("_timed_out") ? "timed_out"
                    : code.column().endsWith("_blocked") ? "blocked"
                    : "unknown";
            jdbc.update("insert into claw4j_run.audit_event"
                            + " (event_id, kind, action, status, error_code, execution_id, actor,"
                            + " source_id, source_sequence)"
                            + " values (?, ?, ?, ?, ?, 'run-1', 'agent-1', ?, 1)",
                    "e-" + code.column(),
                    runLevel ? "agent_run" : "tool_action",
                    runLevel ? "agent.run.finished" : "tool.action.finished",
                    status,
                    code.column(),
                    "code:" + code.column());
        }

        // 消息失败码写进消息行：它不在 V5 那九个里，扩词表这件事本身要被钉住。
        // 通道 / 对话类别 / 失败阶段三格从票 13 起必填（V15），所以夹具也照它的必填形状写。
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, error_code, actor, source_id, source_sequence,"
                + " direction, channel, conversation_kind, message_outcome, failure_stage)"
                + " values ('e-msg-failed', 'message', 'message.outbound.finished', 'failed',"
                + " 'message_delivery_failed', 'agent-1', 'code:message_delivery_failed', 1,"
                + " 'outbound', 'http', 'direct', 'failed', 'platform_send')");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, error_code, execution_id, source_id, source_sequence,"
                + " actor)"
                + " values ('e-old-word', 'tool_action', 'tool.action.finished',"
                + " 'blocked', 'policy_denied', 'run-1', 'code:policy_denied', 1, 'agent-1')"))
                .as("这是换词表之前的值，现在不在词表里")
                .hasMessageContaining("ck_audit_event_error_code");

        // 消息那三个码由上面那一行消息事件覆盖（它们只能出现在 kind=message 上），
        // 所以这里数的是"运行 + 工具九个码各一行"加"消息一行"。
        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_run.audit_event where error_code is not null",
                Integer.class))
                .as("运行与工具的词各一行，消息三个码靠上面那一行代表")
                .isEqualTo(AuditErrorCode.values().length - 2);
    }

    @Test
    @DisplayName("execution_id 必填：没有轮次的事件落不下去，账本连 SQL 都不发")
    void anAuditEventWithoutAnExecutionIdDoesNotLand() {
        // OpenClaw 的 {@code AuditEventSchema.runId} 是 NonEmptyString
        // （{@code packages/gateway-protocol/src/schema/audit.ts:56}）。这一条两头都验：
        // 库拒绝空值；账本在没有编号时直接跳过，否则每一行都撞约束，write_errors 会一直涨，
        // 把没有归属错报成库坏了。
        jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, execution_id, actor, source_id, source_sequence)"
                + " values ('e-with-run', 'agent_run', 'agent.run.started',"
                + " 'started', 'run-1', 'agent-1', 'legacy:e-with-run', 1)");

        assertThatThrownBy(() -> jdbc.update("insert into claw4j_run.audit_event"
                + " (event_id, kind, action, status, actor, source_id, source_sequence)"
                + " values ('e-no-run', 'agent_run', 'agent.run.started', 'started',"
                + " 'agent-1', 'legacy:e-no-run', 1)"))
                .as("没有 execution_id 的运行行会被库拒绝（消息行是唯一允许没有 execution_id 的）")
                .hasMessageContaining("ck_audit_event_execution_required");

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
    /**
     * 一条有效的批准（allowed / allow-once / 人批的）。
     *
     * <p>{@code requested_at} 必须显式早于 {@code expires_at}：表上有时间单调约束，
     * 而"已经过期的批准"这条用例把它传到负数——默认的 now() 会让它一出生就违反约束，
     * 于是用例红在时间约束上，看起来像"过期判定不存在"。
     */
    private void granted(String id, String owner, String tool, String digest, String offset) {
        jdbc.update("insert into claw4j_run.approval_request"
                        + " (id, owner, tool_name, args_digest, status, decision, terminal_reason,"
                        + " requested_at, resolved_at, resolver_kind, resolver_id, expires_at)"
                        + " values (?, ?, ?, ?, 'allowed', 'allow-once', 'user',"
                        + " now() + interval '" + offset + "' - interval '10 minutes', now(),"
                        + " 'channel', 'approver-1', now() + interval '" + offset + "')",
                id, owner, tool, digest);
    }

    // ---------------------------------------------------------------- 进度卡（票 01）

    @Test
    @DisplayName("进度卡整卡替换、revision 每次 +1（票 01）")
    void progressCardIsReplacedWholesale() {
        ProgressCards cards = new JdbcProgressCards(jdbc, "claw4j_session");

        cards.save("s-1", new ProgressCards.Write(null, List.of(
                new ProgressCards.Step("核对表结构", ProgressCards.Status.IN_PROGRESS)), null));
        cards.save("s-1", new ProgressCards.Write(null, List.of(
                new ProgressCards.Step("生成报告", ProgressCards.Status.IN_PROGRESS),
                new ProgressCards.Step("交差", ProgressCards.Status.PENDING)), null));

        ProgressCards.Card card = cards.of("s-1").orElseThrow();
        assertThat(card.revision()).isEqualTo(2);
        assertThat(card.steps()).hasSize(2);
        assertThat(card.steps().getFirst().step())
                .as("整卡替换：上一次那一步不该残留")
                .isEqualTo("生成报告");
    }

    @Test
    @DisplayName("清空保留墓碑：行还在、内容为空、revision 再 +1（票 01）")
    void progressCardClearLeavesTombstone() {
        ProgressCards cards = new JdbcProgressCards(jdbc, "claw4j_session");
        cards.save("s-1", new ProgressCards.Write(null, List.of(
                new ProgressCards.Step("核对表结构", ProgressCards.Status.PENDING)), null));

        cards.save("s-1", new ProgressCards.Write(null, List.of(), null));

        assertThat(cards.of("s-1")).as("读的一侧只看到\"没有卡\"").isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_session.session_progress_cards where session_key = 's-1'",
                Integer.class))
                .as("清空不是删行：墓碑留着，版本号才不会回到原点")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select revision from claw4j_session.session_progress_cards where session_key = 's-1'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("版本不符不覆盖：第二个写入者拿到当前那份（票 01）")
    void progressCardStaleRevisionIsNotOverwritten() {
        ProgressCards cards = new JdbcProgressCards(jdbc, "claw4j_session");
        cards.save("s-1", new ProgressCards.Write(null, List.of(
                new ProgressCards.Step("第一步", ProgressCards.Status.PENDING)), null));

        ProgressCards.Outcome first = cards.save("s-1", new ProgressCards.Write(null, List.of(
                new ProgressCards.Step("第二步", ProgressCards.Status.PENDING)), 1L));
        ProgressCards.Outcome second = cards.save("s-1", new ProgressCards.Write(null, List.of(
                new ProgressCards.Step("第三步", ProgressCards.Status.PENDING)), 1L));

        assertThat(first).isInstanceOf(ProgressCards.Outcome.Saved.class);
        assertThat(second).isInstanceOf(ProgressCards.Outcome.Stale.class);
        assertThat(cards.of("s-1").orElseThrow().steps().getFirst().step())
                .as("过期的那个写入者不能覆盖新的一版")
                .isEqualTo("第二步");
    }

    // ---------------------------------------------------------------- 意图（票 02）

    @Test
    @DisplayName("意图触发一次就进冷却，冷却内不再算候选（票 02）")
    void intentFiresOnceThenCoolsDown() {
        StandingIntents intents = new JdbcStandingIntents(jdbc, "claw4j_session");
        Instant now = Instant.now();
        StandingIntent created = intents.create(new StandingIntents.New("alice",
                "确认是签约数还是活跃数", "客户数", null, null, "alice",
                now.plus(Duration.ofDays(90)), 3, 86_400, "s-1"));

        assertThat(intents.eligible("alice", "http", "alice", now, 256))
                .as("刚建的直接 armed，能参与匹配")
                .hasSize(1);
        assertThat(intents.fire(created.id(), now)).isTrue();
        assertThat(intents.eligible("alice", "http", "alice", now.plusSeconds(1), 256))
                .as("冷却靠那条 update 的 where 判，不靠调用方记得")
                .isEmpty();
        assertThat(intents.fire(created.id(), now.plusSeconds(1)))
                .as("冷却中再记一次必须失败：否则同一句话会提醒两遍")
                .isFalse();
        assertThat(intents.list("alice").getFirst().fireCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("预算用完转 done：max_fires=1 时触发一次就不再是候选（票 02）")
    void intentStopsAfterFireBudget() {
        StandingIntents intents = new JdbcStandingIntents(jdbc, "claw4j_session");
        Instant now = Instant.now();
        StandingIntent created = intents.create(new StandingIntents.New("alice",
                "只提醒一次", "客户数", null, null, "alice",
                now.plus(Duration.ofDays(90)), 1, 0, "s-1"));

        assertThat(intents.fire(created.id(), now)).isTrue();

        assertThat(intents.eligible("alice", "http", "alice", now.plusSeconds(1), 256))
                .as("次数用完了就不该再参与匹配")
                .isEmpty();
        assertThat(intents.list("alice").getFirst().status())
                .isEqualTo(StandingIntent.Status.DONE);
    }

    @Test
    @DisplayName("过期就不参与匹配（票 02）")
    void intentExpiresAtDeadline() {
        StandingIntents intents = new JdbcStandingIntents(jdbc, "claw4j_session");
        Instant now = Instant.now();
        intents.create(new StandingIntents.New("alice", "已经过期了", "客户数", null, null, "alice",
                now.minusSeconds(1), 3, 0, "s-1"));

        assertThat(intents.eligible("alice", "http", "alice", now, 256))
                .as("expires_at 在过去的行不该被捞出来")
                .isEmpty();
    }

    @Test
    @DisplayName("trigger_embedding 建了但恒为 null：本票只做关键词匹配（ADR-0036）")
    void triggerEmbeddingStaysNull() {
        StandingIntents intents = new JdbcStandingIntents(jdbc, "claw4j_session");
        Instant now = Instant.now();
        intents.create(new StandingIntents.New("alice", "确认口径", "客户数", null, null, "alice",
                now.plus(Duration.ofDays(90)), 3, 86_400, "s-1"));

        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_session.standing_intents"
                        + " where trigger_embedding is not null", Integer.class))
                .as("写了 embedding 就等于多一次模型调用与一份延迟，这一票明确不做")
                .isZero();
    }
}
