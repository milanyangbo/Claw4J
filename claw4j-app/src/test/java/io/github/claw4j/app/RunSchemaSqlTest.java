package io.github.claw4j.app;

import io.github.claw4j.agent.approval.JdbcApprovalLedger;
import io.github.claw4j.agent.audit.AuditProperties;
import io.github.claw4j.agent.audit.AuditLedger;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.JdbcSessionGoalStore;
import io.github.claw4j.agent.goal.SessionGoal;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.run.Budget;
import io.github.claw4j.agent.run.JdbcRunStore;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.agent.run.StepAudit;
import io.github.claw4j.agent.run.StepInput;
import io.github.claw4j.agent.run.StepKind;

import java.time.Instant;
import io.github.claw4j.agent.run.StepStatus;
import io.github.claw4j.agent.session.ConversationPointerStore;
import io.github.claw4j.agent.session.JdbcConversationPointerStore;
import io.github.claw4j.agent.session.SessionResetProperties;
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
class RunSchemaSqlTest {

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

    private RunStore runs;

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
        jdbc.update("delete from claw4j_run.agent_step");
        jdbc.update("delete from claw4j_run.agent_run");
        runs = new JdbcRunStore(jdbc);
    }

    @Test
    @DisplayName("V6/V9/V10/V12/V14 真的建了表：落在 claw4j_run 而不是 public，且状态受约束")
    void migrationCreatesRunTablesInTheirOwnSchema() {
        // audit_trail 是视图而不是表，但它出现在同一份目录里——顺着 information_schema 一起查，
        // 是为了让"审计的读法真的存在"与"表落在对的 schema"用一条断言说清。
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'claw4j_run'"
                        + " order by table_name", String.class))
                .containsExactly("agent_run", "agent_schedule", "agent_session_budget",
                        "agent_step", "approval_request", "audit_event", "audit_trail");

        // 约束不是装饰：没有它，任何字符串都能当状态写进去，
        // "做到第几步"会在某天读出一个没人认得的状态——那时候没人知道该重试还是该重开。
        assertThatThrownBy(() -> jdbc.update(
                "insert into claw4j_run.agent_run (id, owner, principal_digest, goal, status)"
                        + " values ('r-bad', 'u-1', 'fp', 'goal', 'almost-done')"))
                .as("未知状态必须被拒")
                .hasMessageContaining("ck_agent_run_status");
    }

    @Test
    @DisplayName("写进去能读回来：换一个 store 实例读（等价于进程重启之后）")
    void writtenRunSurvivesAFreshStore() {
        Run run = runs.start("u-1@fp-1", "fp-1", "上周华东成交额").orElseThrow();

        runs.append(new StepInput(run.id(), StepKind.MODEL_TURN, "上周华东成交额",
                null, "华东上周成交 128400", StepStatus.DONE));
        runs.finish(run.id(), RunStatus.DONE);

        // 新实例、新连接：重启之后"这件事做到第几步"必须照样答得出来。
        // 今天的答案是"答不出"——这正是 A01 存在的理由。
        RunStore restarted = new JdbcRunStore(jdbc);
        assertThat(restarted.find(run.id())).isPresent()
                .get()
                .satisfies(found -> {
                    assertThat(found.status()).isEqualTo(RunStatus.DONE);
                    assertThat(found.goal()).isEqualTo("上周华东成交额");
                    assertThat(found.owner()).isEqualTo("u-1@fp-1");
                });
        List<Step> steps = restarted.stepsOf(run.id());
        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().seq()).isEqualTo(1);
        assertThat(steps.getFirst().kind()).isEqualTo(StepKind.MODEL_TURN);
    }

    @Test
    @DisplayName("序号由存储分配：第二步是 2，同一 run 内不会撞号")
    void sequenceNumbersAreAssignedByTheStore() {
        Run run = runs.start("u-1@fp-1", "fp-1", "goal").orElseThrow();

        runs.append(new StepInput(run.id(), StepKind.MODEL_TURN, "第一句", null, "答一", StepStatus.DONE));
        runs.append(new StepInput(run.id(), StepKind.MODEL_TURN, "第二句", null, "答二", StepStatus.DONE));

        assertThat(runs.stepsOf(run.id())).extracting(Step::seq).containsExactly(1, 2);

        // 唯一键是"第几步"唯一的来源：撞号会让"做到第几步"给出两个答案。
        assertThatThrownBy(() -> jdbc.update(
                "insert into claw4j_run.agent_step (id, run_id, seq, kind, status)"
                        + " values ('s-dup', ?, 1, 'model_turn', 'done')", run.id()))
                .as("同一 run 内 seq 重复必须被拒")
                .hasMessageContaining("uq_agent_step_run_seq");
    }

    @Test
    @DisplayName("库没迁时降级：落不下去也不把提问打成 500")
    void missingTablesDegradeInsteadOfFailing() {
        // 同一个容器里另一个没跑迁移的库：模拟"表还没建"这种最常见的降级现场。
        jdbc.execute("create database unrunnable");
        JdbcTemplate unMigrated = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl().replace(POSTGRES.getDatabaseName(), "unrunnable"),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        RunStore store = new JdbcRunStore(unMigrated);

        assertThat(store.start("u-1@fp-1", "fp-1", "goal")).as("落不下去时返回空，不抛").isEmpty();
        assertThatCode(() -> store.finish("whatever", RunStatus.DONE)).doesNotThrowAnyException();
        assertThat(store.stepsOf("whatever")).isEmpty();
    }

    // ---------------------------------------------------------------- resume（A02）

    /**
     * 库不再接受 {@code suspended}（ADR-0027）。
     *
     * <p><b>这不是"少了一个可选值"</b>：那个状态配着 {@code reopen} / {@code resume} 一整套，
     * 存在的理由是"一件事能跨请求活着"。删掉它之后，等审批的那一轮以 done 收尾
     * （它回给用户的是"我需要你批准"），而批准之后是同一段对话里的下一轮。
     *
     * <p>V21 里那条 {@code update ... set status='done' where status='suspended'} 是配套的：
     * 不迁数据就删枚举值，读回一行旧 suspended 会让 {@code RunStatus.fromColumn} 直接抛，
     * 于是一条历史记录能让整个列表页 500。
     */
    @Test
    @DisplayName("没有 suspended 这个状态：等审批不是一种能跨请求的状态")
    void thereIsNoSuspendedStatus() {
        Run run = runs.start("u-1@fp-1", "fp-1", "发邮件").orElseThrow();

        assertThatThrownBy(() -> jdbc.update(
                "update claw4j_run.agent_run set status = 'suspended' where id = ?", run.id()))
                .as("约束会拒绝它，而不是存下一个没人认得的状态")
                .hasMessageContaining("ck_agent_run_status");
    }
    // ---------------------------------------------------------------- step 的状态约束（V7）

    /**
     * V7 那条按 kind 分支的约束。
     *
     * <p>它挡的不是"写错字"，是两种读法的混淆：一件已经发生的事不能被标成待做。
     * "待做"是计划才有的状态，而计划那条路已经随 ADR-0027 换成会话目标了——
     * 约束仍然留着：库里还有历史的 plan 行，约束对它们照样成立。
     */
    @Test
    @DisplayName("已经发生的一步不能是待做：待做是计划才有的状态")
    void modelTurnsCannotBePending() {
        Run run = runs.start("u-1@fp-1", "fp-1", "goal").orElseThrow();

        assertThatThrownBy(() -> jdbc.update(
                "insert into claw4j_run.agent_step (id, run_id, seq, kind, status)"
                        + " values ('s-turn-pending', ?, 90, 'model_turn', 'pending')", run.id()))
                .as("已经发生的一步不能是待做")
                .hasMessageContaining("ck_agent_step_status");
    }

    // ---------------------------------------------------------------- 会话目标（ADR-0027）

    /**
     * 目标挂在会话上（{@code claw4j_session}），不是挂在 run 上。
     *
     * <p>这里的四条约束各有各的失败长相：状态词表错了是"存下一个没人认得的态"，
     * 空白目标错了是注入那一行变成"活跃目标： — 推进目标…"（看起来一切正常），
     * 预算 0 错了是"第一轮就预算受限"，主键错了是"一个会话两个目标、注入哪一行看运气"。
     */
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
        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, null);
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
     * 用量那三列（V8，A04）在 SQL 这一层的行为：默认 0，各自累加，换实例读得到。
     *
     * <p>主链路那份（{@code RunBudgetTest}）验的是"什么时候扣、扣完停在哪"，
     * 这里验的是"库真的会累加"——尤其 {@code +=} 而不是"读出来加一遍再写回"：
     * 后一种写法在两轮并发时会用旧值覆盖新值，表现是额度凭空多出来。
     */
    @Test
    @DisplayName("用量与会话指针都只在库里长一行：前者累加，后者每次刷新 touched_at")
    void budgetColumnsAccumulateAndThePointerKeepsOneRow() {
        Run run = runs.start("u-1@fp-1", "fp-1", "对齐三张表的口径").orElseThrow();
        // EPOCH = 窗口永不过期：这条 run 没有会话归属（start 时没给会话键），
        // 读的就是它自己那一行，这正是这里要验的 SQL 累加
        assertThat(runs.spent(run.id(), Instant.EPOCH).orElseThrow()).isEqualTo(Budget.NONE);

        runs.charge(run.id(), 2, 1_200L, 3_000L);
        runs.charge(run.id(), 1, 300L, 500L);

        RunStore restarted = new JdbcRunStore(jdbc);
        assertThat(restarted.spent(run.id(), Instant.EPOCH).orElseThrow())
                .isEqualTo(new Budget(3, 1_500L, 3_500L));
        assertThat(restarted.find(run.id()).orElseThrow().spent())
                .as("读 run 时也带上用量：停在 budget_exhausted 的 run 只能靠它回答'为什么停'")
                .isEqualTo(new Budget(3, 1_500L, 3_500L));
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

    /**
     * 验收 2（P07）：按人、按时间、按结局都查得出来，而且行里带着"谁批的"。
     *
     * <p>查的是视图 {@code claw4j_run.audit_trail}——它就是"审计"在库里的形状。
     * 视图存在的理由是不复制真相：它把 step 的列、run 的目标与审批那一侧的结论接在同一行上，
     * 于是"谁在什么时候让谁做了什么、谁批的"是一条 select 而不是三张表的 join 作业。
     */
    @Test
    @DisplayName("审计视图按人、按时间、按结局都查得出来，并且带着「谁批的」")
    void theAuditViewAnswersWhoWhenAndHow() {
        Run mine = runs.start("u-analyst@fp", "fp", "查上周成交额").orElseThrow();
        Run other = runs.start("u-other@fp", "fp", "查昨天的成交额").orElseThrow();
        granted("ap-1", "u-analyst", "querySales", DIGEST, "10 minutes");

        runs.append(new StepInput(mine.id(), StepKind.TOOL_CALLS, null, "querySales", null,
                StepStatus.DONE,
                StepAudit.executed("u-analyst", "analyst", DIGEST, "{\"region\":\"华东\"}", "ap-1")));
        runs.append(new StepInput(mine.id(), StepKind.TOOL_CALLS, null, "sendEmail",
                "没有权限：角色 analyst 不允许执行 send_email 动作（工具 sendEmail）。",
                StepStatus.FAILED,
                StepAudit.rejected("u-analyst", "analyst", OTHER_DIGEST, "{\"to\":\"x@example.com\"}")));
        runs.append(new StepInput(other.id(), StepKind.TOOL_CALLS, null, "querySales", null,
                StepStatus.DONE,
                StepAudit.executed("u-other", "analyst", DIGEST, null, null)));
        // 把别人的那一行挪到十天前：时间过滤要能把它滤出去，而"最近一天"里只剩我的两条。
        jdbc.update("update claw4j_run.agent_step set created_at = now() - interval '10 days'"
                + " where run_id = ?", other.id());

        assertThat(jdbc.queryForList(
                "select tool from claw4j_run.audit_trail where actor = ? order by at", String.class,
                "u-analyst")).containsExactly("querySales", "sendEmail");

        assertThat(jdbc.queryForList(
                "select actor from claw4j_run.audit_trail where outcome = ?", String.class, "rejected"))
                .as("越权那一行按结局查得出来").containsExactly("u-analyst");

        assertThat(jdbc.queryForList(
                "select run_id from claw4j_run.audit_trail where at >= now() - interval '1 day'"
                        + " order by at", String.class))
                .as("按时间查：十天前那一条不该出现在最近一天里").hasSize(2).doesNotContain(other.id());

        assertThat(jdbc.queryForMap("select approved_by, approval_status, goal from"
                + " claw4j_run.audit_trail where approval_id = ?", "ap-1"))
                .as("视图把审批那一侧接了过来：谁批的、什么结论、当时要干什么")
                .containsEntry("approved_by", "approver-1")
                .containsEntry("approval_status", "granted")
                .containsEntry("goal", "查上周成交额");
    }

    /**
     * 验收 1（P07）：<b>批准记录与执行记录一一对应</b>——库里的唯一索引是这句话的机器版本。
     *
     * <p>谓词里只算了 {@code executed}：等审批那一行也带着同一个编号（"这条停在谁身上"要能查），
     * 它不是执行。少了那个谓词，一条"先等批、后执行"的正常流程会撞约束——那是惩罚正确的用法。
     */
    @Test
    @DisplayName("一条批准只对应一条执行：第二条执行行被拒，等审批那一行不算")
    void oneApprovalBacksExactlyOneExecution() {
        Run run = runs.start("u-mailer@fp", "fp", "发一封周报").orElseThrow();
        granted("ap-1", "u-mailer", "sendEmail", DIGEST, "10 minutes");
        StepInput awaiting = new StepInput(run.id(), StepKind.TOOL_CALLS, null, "sendEmail",
                "这个动作要先审批", StepStatus.FAILED,
                StepAudit.awaiting("u-mailer", "mailer", DIGEST, "{\"to\":\"a\"}", "ap-1"));
        StepInput executed = new StepInput(run.id(), StepKind.TOOL_CALLS, null, "sendEmail", null,
                StepStatus.DONE,
                StepAudit.executed("u-mailer", "mailer", DIGEST, "{\"to\":\"a\"}", "ap-1"));

        assertThat(runs.append(awaiting)).as("等审批那一行与执行行可以共用一个编号").isPresent();
        assertThat(runs.append(executed)).isPresent();

        assertThat(runs.append(executed))
                .as("第二条同编号的执行行被库拒，store 把它降级成空（与其它写失败同一个姿势）")
                .isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.audit_trail"
                + " where approval_id = ? and outcome = 'executed'", Integer.class, "ap-1"))
                .isEqualTo(1);
    }

    /**
     * 留存（ADR-0027 决定二）：30 天与 10 万行，两个都是照 OpenClaw 的固定上限。
     *
     * <p><b>判据是库的时钟，不是应用时间</b>：SQL 里用 {@code now()} 比，
     * 于是"库里那一列与比较用的是同一个时钟"这件事不依赖应用服务器的时区设置。
     *
     * <p><b>上限按 {@code seq} 删，不按 {@code at} 删</b>：同一秒里写进来的几百条，
     * 按时间删会随机丢一部分，而"账本上限"本来该是一个确定的位置。
     */
    @Test
    @DisplayName("审计按 30 天与 10 万行清：一个按时钟，一个按账本序号")
    void theAuditLedgerPrunesByAgeAndSize() {
        jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status, actor, at)"
                + " values (gen_random_uuid(), 'e-old', 'agent_run', 'agent.run.started',"
                + " 'started', 'u-1', now() - interval '31 days')");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status, actor)"
                + " values (gen_random_uuid(), 'e-fresh', 'agent_run', 'agent.run.started',"
                + " 'started', 'u-1')");

        AuditLedger.Report aged = new AuditLedger(jdbc, null).prune();

        assertThat(aged.expired()).as("31 天前那一条").isEqualTo(1);
        assertThat(jdbc.queryForList("select event_id from claw4j_run.audit_event", String.class))
                .as("30 天内不动").containsExactly("e-fresh");

        // ---- 10 万行上限。一条 SQL 插进去，比循环十万次快得多，也比 sleep 诚实。
        jdbc.update("delete from claw4j_run.audit_event");
        jdbc.update("insert into claw4j_run.audit_event"
                + " (id, event_id, kind, action, status)"
                + " select gen_random_uuid(), 'bulk-' || g, 'agent_run', 'agent.run.started', 'started'"
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

    private void granted(String id, String owner, String tool, String digest, String offset) {
        jdbc.update("insert into claw4j_run.approval_request"
                        + " (id, owner, tool_name, args_digest, status, expires_at, decided_at, decided_by)"
                        + " values (?, ?, ?, ?, 'granted', now() + interval '" + offset + "', now(),"
                        + " 'approver-1')",
                id, owner, tool, digest);
    }
}
