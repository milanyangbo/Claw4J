package io.github.claw4j.app;

import io.github.claw4j.agent.approval.JdbcApprovalLedger;
import io.github.claw4j.agent.audit.AuditProperties;
import io.github.claw4j.agent.audit.AuditRetention;
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
import io.github.claw4j.agent.run.UpdatePlanTool;
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
                .containsExactly("agent_run", "agent_run_delivery", "agent_schedule", "agent_session_budget",
                        "agent_step", "approval_request", "audit_trail");

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
     * "继续"不是"再来一次"：不插新行、状态回到 running、序号接着涨。
     *
     * <p>这三条里最要紧的是第一条——另开一个 run 也能给出"第 1 步"，
     * 只有 runId 相同才说明它真的是同一件事的续集。
     */
    @Test
    @DisplayName("接上：不新建 run，状态回到 running，序号接着涨")
    void resumeReusesTheRunAndContinuesTheSequence() {
        Run run = runs.start("u-1@fp-1", "fp-1", "对齐三张表的口径").orElseThrow();
        runs.append(new StepInput(run.id(), StepKind.MODEL_TURN, "第一轮", null, "答一", StepStatus.DONE));
        runs.finish(run.id(), RunStatus.DONE);

        Run resumed = runs.resume(run.id(), "u-1@fp-1").orElseThrow();

        assertThat(resumed.id()).isEqualTo(run.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(jdbc.queryForObject("select count(*) from claw4j_run.agent_run", Integer.class))
                .as("接上不该多出一行").isEqualTo(1);
        Step next = runs.append(
                new StepInput(run.id(), StepKind.MODEL_TURN, "继续", null, "答二", StepStatus.DONE)).orElseThrow();
        assertThat(next.seq()).isEqualTo(2);
        assertThat(runs.stepsOf(run.id())).extracting(Step::seq).containsExactly(1, 2);
    }

    /** 只有 id 拿不到别人的事：挡在 where 里，而且被拒的那次不能顺手改别人的状态。 */
    @Test
    @DisplayName("别人的 run 与不存在的 runId 都接不上")
    void resumeRejectsRunsThatAreNotYoursOrDoNotExist() {
        Run run = runs.start("u-1@fp-1", "fp-1", "goal").orElseThrow();
        runs.finish(run.id(), RunStatus.DONE);

        assertThat(runs.resume(run.id(), "u-2@fp-2")).isEmpty();
        assertThat(runs.resume("no-such-run", "u-1@fp-1")).isEmpty();
        assertThat(jdbc.queryForObject(
                "select status from claw4j_run.agent_run where id = ?", String.class, run.id()))
                .as("被拒的那次不能把别人的 run 改成 running").isEqualTo("done");
    }

    /**
     * 等审批的 run 只由审批放行（P02）。
     *
     * <p>这条是那道门的锁：一句"继续"就能把它唤醒的话，审批这道门等于没装，
     * 而它的表现会比没有审批更糟——系统看起来有审批，实际上绕得过去。
     */
    @Test
    @DisplayName("等审批的 run 接不上：一句「继续」不能当放行用")
    void resumeDoesNotWakeASuspendedRun() {
        Run run = runs.start("u-1@fp-1", "fp-1", "发邮件").orElseThrow();
        jdbc.update("update claw4j_run.agent_run set status = 'suspended' where id = ?", run.id());

        assertThat(runs.resume(run.id(), "u-1@fp-1")).isEmpty();
        assertThat(jdbc.queryForObject(
                "select status from claw4j_run.agent_run where id = ?", String.class, run.id()))
                .isEqualTo("suspended");
    }

    // ---------------------------------------------------------------- 计划外置（A03）

    /**
     * V7 那条按 kind 分支的约束。
     *
     * <p>它挡的不是"写错字"，是两种读法的混淆：一件已经发生的事不能被标成待做，
     * 而计划的一步也没有"失败"这回事——没做就是待做，做了就是已做。
     * 这两句的下一步动作完全不同（接着往下做 vs 重试）。
     */
    @Test
    @DisplayName("计划能写待做与跳过；模型轮仍然只能是成了还是没成")
    void planStatusIsAllowedOnlyForPlanSteps() {
        Run run = runs.start("u-1@fp-1", "fp-1", "goal").orElseThrow();

        assertThat(runs.setPlan(run.id(), List.of("对齐订单表", "对齐退款表"))).isPresent();
        assertThat(runs.markPlan(run.id(), 1, StepStatus.DONE)).isPresent();
        assertThat(runs.markPlan(run.id(), 2, StepStatus.SKIPPED)).isPresent();
        assertThat(runs.planOf(run.id())).extracting(Step::status)
                .containsExactly(StepStatus.DONE, StepStatus.SKIPPED);

        assertThatThrownBy(() -> jdbc.update(
                "insert into claw4j_run.agent_step (id, run_id, seq, kind, status)"
                        + " values ('s-turn-pending', ?, 90, 'model_turn', 'pending')", run.id()))
                .as("已经发生的一步不能是待做")
                .hasMessageContaining("ck_agent_step_status");
        assertThatThrownBy(() -> jdbc.update(
                "insert into claw4j_run.agent_step (id, run_id, seq, kind, status)"
                        + " values ('s-plan-failed', ?, 91, 'plan', 'failed')", run.id()))
                .as("计划的一步没有失败：没做就是待做")
                .hasMessageContaining("ck_agent_step_status");
    }

    /**
     * 计划内序号不等于 {@code seq}：模型手里的"第 2 步"是清单里的第二个。
     *
     * <p>这两者在计划没被重写过时恰好相等，所以这里<b>先把一轮模型回答插进去</b>把两者顶开——
     * 相等的时候这条用例验不出任何东西。
     */
    @Test
    @DisplayName("计划按清单里的第几个标状态：不是 seq，换一个 store 读也照样")
    void planIsMarkedByItsPositionInTheListNotBySeq() {
        Run run = runs.start("u-1@fp-1", "fp-1", "把三张表的口径对齐").orElseThrow();
        runs.append(new StepInput(run.id(), StepKind.MODEL_TURN, "第一轮", null, "答一", StepStatus.DONE));

        RunStore restarted = new JdbcRunStore(jdbc);
        restarted.setPlan(run.id(), List.of("对齐订单表", "对齐退款表", "对齐优惠券表"));
        restarted.markPlan(run.id(), 1, StepStatus.DONE);

        assertThat(restarted.planOf(run.id()))
                .as("计划里只有计划那三步，不含模型轮")
                .extracting(Step::input)
                .containsExactly("对齐订单表", "对齐退款表", "对齐优惠券表");
        assertThat(restarted.planOf(run.id())).extracting(Step::status)
                .containsExactly(StepStatus.DONE, StepStatus.PENDING, StepStatus.PENDING);
        assertThat(jdbc.queryForObject(
                "select status from claw4j_run.agent_step where run_id = ? and seq = 1",
                String.class, run.id()))
                .as("seq = 1 是那一轮模型回答，不该被当成计划的第一步")
                .isEqualTo("done");
    }

    /** 重写计划不是删计划：旧的那版不再算数，但审计还得能回答"当时打算怎么干"。 */
    @Test
    @DisplayName("重写计划：旧的那版不再被读到，但仍留在库里")
    void rewritingAPlanSupersedesTheOldOneWithoutDeletingIt() {
        Run run = runs.start("u-1@fp-1", "fp-1", "goal").orElseThrow();
        runs.setPlan(run.id(), List.of("第一步", "第二步", "第三步"));
        runs.markPlan(run.id(), 1, StepStatus.DONE);

        runs.setPlan(run.id(), List.of("换个做法", "再来一步"));

        assertThat(runs.planOf(run.id())).extracting(Step::input)
                .as("读到的只有当前这一版")
                .containsExactly("换个做法", "再来一步");
        assertThat(jdbc.queryForList(
                "select input from claw4j_run.agent_step where run_id = ? and kind = 'plan' order by seq",
                String.class, run.id()))
                .as("旧的没被删")
                .containsExactly("第一步", "第二步", "第三步", "换个做法", "再来一步");
        assertThat(runs.markPlan(run.id(), 3, StepStatus.DONE))
                .as("当前这版只有两步，第 3 步不存在").isEmpty();
    }

    /**
     * 工具这一层的闭环：写进去、标上、回执里说清"还剩哪几步没做"。
     *
     * <p>回执要写"还剩什么"而不是回一个 JSON 让模型自己去数——
     * 那等于把"还剩什么没做"又交回给自然语言，正是 A03 要消掉的那个错。
     */
    @Test
    @DisplayName("计划工具：写进当前这一轮的 run，回执里说清还剩哪几步")
    void planToolWritesThePlanOfTheCurrentRun() {
        Run run = runs.start("u-1@fp-1", "fp-1", "把三张表的口径对齐").orElseThrow();
        UpdatePlanTool tool = new UpdatePlanTool(runs);
        ToolContext context = new ToolContext(Map.of(ContextKeys.RUN_ID, run.id()));

        String written = tool.call("{\"steps\":[\"对齐订单表\",\"对齐退款表\",\"对齐优惠券表\"]}", context);
        String marked = tool.call("{\"done\":[2]}", context);

        assertThat(written).contains("计划共 3 步").contains("第 1 步 [待做]")
                .contains("还剩 3 步没做");
        assertThat(marked).contains("第 2 步 [已做]").contains("还剩 2 步没做：第 1、3 步");
        // 判据是库里那三行，不是回执的措辞：回执只是给模型看的那一份。
        // 标的是清单里第二个（"对齐退款表"），不是 seq = 2 那一行——
        // 这一轮里两者恰好相等，所以"第 2 步"到底指什么由上面那条用例专门钉住。
        assertThat(runs.planOf(run.id())).extracting(Step::status)
                .containsExactly(StepStatus.PENDING, StepStatus.DONE, StepStatus.PENDING);
    }

    /** 没有 run 就没有可写的落点：拒绝，而不是退化成"写到一个写死的 run 上"。 */
    @Test
    @DisplayName("上下文里没有 run：工具拒绝，不写到别的 run 上")
    void planToolRefusesWhenThereIsNoRunInContext() {
        UpdatePlanTool tool = new UpdatePlanTool(runs);

        assertThat(tool.call("{\"steps\":[\"一步\"]}")).contains("这一轮没有 run");
        assertThat(tool.call("{\"steps\":[\"一步\"]}", new ToolContext(Map.of())))
                .contains("这一轮没有 run");
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
        // EPOCH = 窗口永不过期：这条 run 没有会话归属（没 attachSession），
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

        // ---- 对话地址（ADR-0026 决定二）：审批续跑没有入站消息，只能靠这一列找回地址。
        // 与会话键分开存：那个随段轮换，这个不随——混成一列的话，续跑会锁到另一段对话上。
        runs.attachAddress(run.id(), "addr:u-1@fp-1|web|c-1");
        assertThat(new JdbcRunStore(jdbc).sessionAddressOf(run.id()).orElseThrow())
                .as("读不回来就退化成不加锁，而那个失败是「审批续跑和用户同时写一段会话」")
                .isEqualTo("addr:u-1@fp-1|web|c-1");

        // ---- 会话指针（ADR-0026 / V17）：同一行反复改值，不是每次插一行。
        // 这里验的是那条 on conflict ... case ... returning 在真 Postgres 上的行为——
        // 内存实现（SessionMemoryTest）能验"时间怎么驱动段"，但验不到 SQL 本身。
        ConversationPointerStore pointers = new JdbcConversationPointerStore(jdbc);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant tenAm = Instant.parse("2026-11-05T02:00:00Z");   // 东八区 11-05 10:00
        ConversationPointerStore.Key key =
                new ConversationPointerStore.Key("u-1@fp-1", "web", "c-1");

        String first = pointers.segmentOf(key, rotation(tenAm, zone, "s-1")).orElseThrow();
        String again = pointers.segmentOf(key, rotation(tenAm.plusSeconds(60), zone, "s-2"))
                .orElseThrow();
        assertThat(again)
                .as("同一天连着两句必须是同一段：换段等于换对话，每句都换就没有'这段对话'了")
                .isEqualTo(first);

        assertThat(jdbc.queryForObject("select touched_at from claw4j_session.conversation_pointer"
                        + " where owner = ? and channel = ? and chat_id = ?",
                Timestamp.class, key.owner(), key.channel(), key.chatId()).toInstant())
                .as("不换段的那一次也要刷新 touched_at。不刷新的话空闲是从这一段的第一句话算起的，"
                        + "120 分钟之后每句话都会换段——而那一刻之前一切正常")
                .isAfter(tenAm);

        // 从"上一次说话"（10:01）再空 121 分钟。从 10:00 起算会差一分钟——
        // 判定是严格小于（超过阈值才算空闲），边界上不换段是对的。
        assertThat(pointers.segmentOf(key,
                rotation(tenAm.plusSeconds(60 + 121 * 60), zone, "s-3")).orElseThrow())
                .as("空过阈值必须换段")
                .isNotEqualTo(first);

        assertThat(jdbc.queryForObject(
                "select count(*) from claw4j_session.conversation_pointer where owner = ?",
                Integer.class, key.owner()))
                .as("换段是同一行改值。插一行的话这个键会越积越多，"
                        + "而查询'当前段'只能靠 order by，早晚会拿到错的那一行")
                .isEqualTo(1);
    }

    /**
     * 一次访问带来的那一组参数，交给指针 store。
     *
     * <p>与 {@code SessionResetProperties} 的默认口径一致：120 分钟空闲、4 点换日、
     * 日历参与判定。写在这里而不是复用那个类，是因为这条用例要验的是
     * <b>SQL 对这四个入参的反应</b>，不是配置怎么绑定。
     */
    private static ConversationPointerStore.Rotation rotation(Instant now, ZoneId zone,
                                                              String segment) {
        return new ConversationPointerStore.Rotation(segment, now,
                now.minus(Duration.ofMinutes(SessionResetProperties.DEFAULT_IDLE_MINUTES)),
                now.atZone(zone).minusHours(SessionResetProperties.DEFAULT_AT_HOUR).toLocalDate(),
                true);
    }

    // ---------------------------------------------------------------- 审批（P01）

    /**
     * V10 的约束：三种状态、以及"有结论就必须有决定时间"。
     *
     * <p>这两条挡的都不是错别字，是两种会让审批悄悄失效的形状：
     * 多一个拼错的 {@code granted} 之后，查询按 {@code status = 'granted'} 过滤时会把它当成
     * "没批准"——记录看起来是批过的，行为是没批；而没有决定时间的 {@code granted}
     * 事后答不出"谁在什么时候点的头"，也就是一条无法追溯的断言。
     */
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
                "{\"to\":\"zhang@example.com\"}")).orElseThrow();

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
     * 留存（P07）：{@code claw4j.audit.retention} 只是写在配置里的一个数，
     * 没有清理任务的话那一天的行一条都不会消失（这个坑本项目踩过三次）。
     *
     * <p>删的是<b>整件旧事</b>：step 跟着 run 级联，所以不会出现"有审计行、但它指向的执行记录没了"。
     * 审批没有外键（V10 说过为什么不设），所以要单独删，并且按<b>提出时间</b>算——
     * 按结论时间算的话，挂了三个月没人点的那条永远不会被删。
     */
    @Test
    @DisplayName("保留期到了整件旧事一起删：step 跟着 run 走，审批按提出时间算")
    void theRetentionWindowDeletesWholeRunsAndStaleApprovals() {
        Run current = runs.start("u-1@fp", "fp", "上周成交额").orElseThrow();
        Run stale = runs.start("u-1@fp", "fp", "三个月前的成交额").orElseThrow();
        runs.append(new StepInput(current.id(), StepKind.MODEL_TURN, "上周成交额", null, "128400",
                StepStatus.DONE));
        runs.append(new StepInput(stale.id(), StepKind.MODEL_TURN, "三个月前", null, "900", StepStatus.DONE));
        granted("ap-fresh", "u-1", "sendEmail", DIGEST, "10 minutes");
        granted("ap-stale", "u-1", "sendEmail", OTHER_DIGEST, "10 minutes");
        jdbc.update("update claw4j_run.agent_run set created_at = now() - interval '100 days'"
                + " where id = ?", stale.id());
        jdbc.update("update claw4j_run.approval_request set requested_at = now() - interval '100 days'"
                + " where id = ?", "ap-stale");

        int purged = new AuditRetention(jdbc, new AuditProperties(), null).purge();

        assertThat(purged).as("一件旧事 + 一条旧审批").isEqualTo(2);
        assertThat(runs.find(stale.id())).as("过了保留期的整件旧事").isEmpty();
        assertThat(runs.find(current.id())).as("保留期内的不动").isPresent();
        assertThat(jdbc.queryForList("select run_id from claw4j_run.agent_step", String.class))
                .as("step 跟着 run 一起走，不会留下指向空处的审计行")
                .containsExactly(current.id());
        assertThat(jdbc.queryForList("select id from claw4j_run.approval_request", String.class))
                .as("审批没有外键，得单独删——留下它等于留一条查不到执行记录的批准")
                .containsExactly("ap-fresh");
    }

    private void granted(String id, String owner, String tool, String digest, String offset) {
        jdbc.update("insert into claw4j_run.approval_request"
                        + " (id, owner, tool_name, args_digest, status, expires_at, decided_at, decided_by)"
                        + " values (?, ?, ?, ?, 'granted', now() + interval '" + offset + "', now(),"
                        + " 'approver-1')",
                id, owner, tool, digest);
    }
}
