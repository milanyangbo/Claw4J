package io.github.claw4j.app;

import io.github.claw4j.agent.ChatClientAgentPort;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.JdbcSessionGoalStore;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.observability.OtelRunTracer;
import io.github.claw4j.agent.observability.RunTracer;
import io.github.claw4j.agent.run.DeliveryTarget;
import io.github.claw4j.agent.run.JdbcRunStore;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunDelivery;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.run.Step;
import io.github.claw4j.agent.schedule.ScheduleProperties;
import io.github.claw4j.agent.session.SessionMaintenance;
import io.github.claw4j.agent.session.SessionMaintenanceProperties;
import io.github.claw4j.agent.session.SessionResetProperties;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一次 run 的周边两件事：**它花了多少**（A04）与**结果送到哪**（A05，ADR-0018），真跑在 Postgres 上。
 *
 * <p><b>为什么合成一份</b>：这两件事本来各占一个文件，setup 是同一套（同一个容器、同一份迁移、
 * 同一张 step 表），差别只在断言的是额度还是地址。合成之后空出来的文件位给了
 * {@code TraceExportTest}——而"观测"与"这一件事花了多少、送到哪"本来也是同一层的问题。
 * 断言一条没删，搬运时只把两个 {@code @BeforeEach} 并成一个（三张表一起清）。
 *
 * <p><b>为什么用真库</b>：预算的全部意义是"跨请求、跨进程之后仍然记得花了多少"，
 * 投递的全部意义是"地址是从库里读出来的"（换通道接着做时新的地址要一起进来）。
 * 内存版会把这两条判据都变成一句自我声明。换一个 store 实例读，等价于进程重启。
 *
 * <p><b>判据是库里的数与人收到的内容，不是回执的措辞</b>：与 run 那两张表同一个纪律——
 * "没抛异常"证明不了额度被扣了、也证明不了谁收到了什么。
 *
 * <p>推送那一头用假的（{@link OutboundDelivery} 的实现属于各通道，它自己那份测试管它）；
 * 库上的那一半不在这里：工具循环、鉴权、审批在 agent 模块各自的测试里。
 */
@Testcontainers
class RunLifecycleTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

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
        // 投递表先清：它按 run 的外键指着 agent_run。
        jdbc.update("delete from claw4j_run.agent_step");
        jdbc.update("delete from claw4j_run.agent_run");
        freshSessionTables();
        runs = new JdbcRunStore(jdbc);
    }

    // ---------------------------------------------------------------- 花了多少（A04）

    @Test
    @DisplayName("一轮就是一件事、答完即收尾：跨轮不存在「还没做完的 run」")
    void anAnsweredTurnLeavesNothingUnfinished() {
        Principal principal = Principal.readOnly("u-1");
        String owner = SessionKeys.ownerOf(principal);
        ChatClientAgentPort port = new ChatClientAgentPort(
                ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs);

        OutboundMessage first = port.reply(new InboundMessage(principal, "chat-1", "华东上周成交额是多少？"));
        OutboundMessage second = port.reply(new InboundMessage(principal, "chat-1", "那华南呢？"));

        assertThat(runs.find(first.runId()).orElseThrow().status())
                .as("答完就是 done：run 的寿命是一轮，不是一段对话")
                .isEqualTo(RunStatus.DONE);
        assertThat(second.runId())
                .as("第二轮是新开的一件——openRun 只在消息带回 runId 时才续跑")
                .isNotEqualTo(first.runId());

        Integer unfinished = jdbc.queryForObject(
                "select count(*) from claw4j_run.agent_run"
                        + " where owner = ? and status in ('running', 'suspended')",
                Integer.class, owner);
        assertThat(unfinished)
                .as("会话里没有「没做完的事」可钉：A08 那种进度前缀，按 run 传的是刚开的空 run，"
                        + "按 session 又查不到未收尾的 run——两条路都拿不到内容。"
                        + "真要跨轮记住的是对话本身，那是压缩摘要的活。"
                        + "哪天这里不是 0，说明 run 的寿命变了，那条前缀才有重新讨论的余地")
                .isZero();
    }

    @Test
    @DisplayName("这一轮花掉的 token 记到目标上：撞上预算进 budget_limited，下一轮不再注入")
    void tokensOfATurnAreChargedToTheGoal() {
        Principal principal = Principal.readOnly("u-1");
        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, null);
        String sessionId = SessionKeys.sessionIdOf(principal, null, "chat-goal");
        goals.start(sessionId, "把三张表的口径对齐", 100L);

        List<String> prompts = new ArrayList<>();
        ChatClientAgentPort port = goalPort(prompts);

        // 每轮 12 + 34 = 46：两轮 92 还在预算内，第三轮 138 撞线。
        port.reply(new InboundMessage(principal, "chat-goal", "先核对华东"));
        port.reply(new InboundMessage(principal, "chat-goal", "再核对华南"));
        assertThat(goals.of(sessionId).orElseThrow().status())
                .as("还没到 100：目标照旧是活跃的").isEqualTo(GoalStatus.ACTIVE);
        assertThat(goals.of(sessionId).orElseThrow().tokensUsed()).isEqualTo(92L);

        port.reply(new InboundMessage(principal, "chat-goal", "最后核对一遍"));
        assertThat(goals.of(sessionId).orElseThrow().status())
                .as("到了 100 就进 budget_limited——不是这一轮失败，是这件事停下来了")
                .isEqualTo(GoalStatus.BUDGET_LIMITED);
        assertThat(goals.of(sessionId).orElseThrow().tokensUsed()).isEqualTo(138L);

        port.reply(new InboundMessage(principal, "chat-goal", "接着说"));
        assertThat(prompts.getLast())
                .as("受限的目标不再注入：额度用完还逼模型继续，等于没停")
                .doesNotContain("活跃目标：");
        assertThat(jdbc.queryForObject(
                "select status from claw4j_run.agent_run where id = "
                        + "(select id from claw4j_run.agent_run order by created_at desc limit 1)",
                String.class))
                .as("这一轮本身照常跑完：额度状态在目标上，不在 run 上")
                .isEqualTo("done");
    }

    /**
     * 恢复的两条规则（OpenClaw：resume 开新窗口，但只对"额度用完"那个状态）。
     *
     * <p>写在真库上：清零与判超都在一条 SQL 里（`case when status = 'budget_limited'`），
     * 用内存替身验的是我写的 if，不是那条语句。
     */
    @Test
    @DisplayName("/goal resume：额度用完的开新窗口，暂停过的用量照旧")
    void resumingResetsTheWindowOnlyWhenTheBudgetWasReached() {
        SessionGoalStore goals = new JdbcSessionGoalStore(jdbc, null);
        String capped = "claw4j:user:u-1@fp-1:-:capped";
        String paused = "claw4j:user:u-1@fp-1:-:paused";
        goals.start(capped, "把三张表的口径对齐", 100L);
        goals.addTokens(capped, 138L);
        goals.start(paused, "等 CI 变绿", 100L);
        goals.addTokens(paused, 40L);
        goals.setStatus(paused, GoalStatus.PAUSED, "等 CI");

        var resumed = goals.resume(capped, "再看看").orElseThrow();

        assertThat(resumed.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(resumed.tokensUsed()).as("新窗口：用量从 0 起算").isZero();
        assertThat(resumed.objective()).isEqualTo("把三张表的口径对齐");
        assertThat(resumed.tokenBudget()).as("预算本身不变").isEqualTo(100L);

        var back = goals.resume(paused, "继续").orElseThrow();
        assertThat(back.tokensUsed())
                .as("暂停一下不该白拿一份额度：只有限额那一个状态才清零")
                .isEqualTo(40L);
    }

    // ---------------------------------------------------------------- 送到哪（A05）

    @Test
    @DisplayName("结果推回它开始的那段对话：没有登记表，也不回退到「最近用过的通道」")
    void theResultGoesBackToTheConversationItStartedIn() {
        Recording feishu = new Recording("feishu");
        RunDelivery delivery = delivery(feishu);

        delivery.announce(new DeliveryTarget("feishu", "oc_zhang"), NotifyPolicy.DONE_ONLY, "口径对完了");
        // 没有实现推的通道（HTTP）不在 deliveries 里：它靠轮询，这不是失败
        delivery.announce(new DeliveryTarget("http", "http:u-1"), NotifyPolicy.DONE_ONLY, "口径对完了");

        assertThat(feishu.sent)
                .as("只推给它自己那段对话（ADR-0027 决定四）")
                .containsExactly("oc_zhang|口径对完了");
    }

    @Test
    @DisplayName("silent 一句都不推，但结果落库、按 id 查得到（不打扰不等于丢了）")
    void silentKeepsTheResultButSaysNothing() {
        Recording deliveryFeishu = new Recording("feishu");
        RunDelivery delivery = delivery(deliveryFeishu);
        Principal asker = Principal.readOnly("u-1");
        Run run = runs.start(owner("u-1"), "fp-1", "每周一跑一遍口径核对",
                NotifyPolicy.SILENT).orElseThrow();

        runs.finish(run.id(), RunStatus.DONE, "这周没有对不上的地方");
        delivery.announce(new DeliveryTarget("feishu", "oc_zhang"), NotifyPolicy.SILENT,
                "这周没有对不上的地方");

        assertThat(deliveryFeishu.sent).as("定时任务每跑一趟推一条，等于把自动化做成骚扰").isEmpty();
        assertThat(delivery.find(run.id(), asker)).get()
                .satisfies(view -> {
                    assertThat(view.result()).isEqualTo("这周没有对不上的地方");
                    assertThat(view.notifyPolicy()).isEqualTo(NotifyPolicy.SILENT);
                });
    }

    @Test
    @DisplayName("按 id 回收归 owner 判，不归通道判：本人查得到，换个人就是查不到")
    void theRunBelongsToAPersonNotAChannel() {
        RunDelivery delivery = delivery();
        Run run = runs.start(owner("u-1"), "fp-1", "上周华东成交额").orElseThrow();
        runs.finish(run.id(), RunStatus.DONE, "华东上周成交 128400");

        assertThat(delivery.find(run.id(), Principal.readOnly("u-1")))
                .hasValueSatisfying(view -> assertThat(view.status()).isEqualTo("done"));
        assertThat(delivery.find(run.id(), Principal.readOnly("u-9")))
                .as("别人的事与没有这件事给同一个答案，不替对方确认 id 存在")
                .isEmpty();
    }

    @Test
    @DisplayName("通知策略三档写死在约束里：拼错的那一次连库都进不去")
    void unknownNotifyPolicyIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into claw4j_run.agent_run (id, owner, principal_digest, goal, status, notify_policy)"
                        + " values ('r-bad', 'u-1@fp', 'fp', 'goal', 'running', 'slience')"))
                .as("拼错的表现是「该说的一次没说」，那种错只在事后被发现")
                .hasMessageContaining("ck_agent_run_notify_policy");
    }

    // ---------------------------------------------------------------- 观测不碰这两张表（P14）

    @Test
    @DisplayName("观测不写业务库：开了 trace 之后 agent_step 的行数与内容一模一样（P14）")
    void tracingAddsNothingToTheAuditTables() {
        Principal principal = Principal.readOnly("u-1");

        Run plain = runs.start(owner("u-1"), "fp-1", "上周华东成交额").orElseThrow();
        new ChatClientAgentPort(ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs)
                .reply(new InboundMessage(principal, "chat-1", "上周华东成交额", Instant.now(), null));

        List<SpanData> spans = new CopyOnWriteArrayList<>();
        Run traced = runs.start(owner("u-1"), "fp-1", "上周华东成交额").orElseThrow();
        new ChatClientAgentPort(ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs,
                null, tracerInto(spans))
                .reply(new InboundMessage(principal, "chat-1", "上周华东成交额", Instant.now(), null));

        // 先证明真的画了：不然这条用例只证明了"什么都没发生"，而那种绿毫无意义。
        assertThat(spans).isNotEmpty();

        List<Step> tracedSteps = runs.stepsOf(traced.id());
        List<Step> plainSteps = runs.stepsOf(plain.id());
        assertThat(tracedSteps).hasSameSizeAs(plainSteps);
        assertThat(tracedSteps).extracting(Step::kind)
                .as("trace 不是审计的第二份副本（ADR-0020）：表里该只有那两类 step，一条不多")
                .isEqualTo(plainSteps.stream().map(Step::kind).toList());
        assertThat(tracedSteps).extracting(Step::status)
                .isEqualTo(plainSteps.stream().map(Step::status).toList());
        assertThat(tracedSteps).extracting(Step::resultSummary)
                .as("摘要也不该因为开了 trace 而变化")
                .isEqualTo(plainSteps.stream().map(Step::resultSummary).toList());
    }

    // ---------------------------------------------------------------- 旧会话怎么清（04）

    @Test
    @DisplayName("清理按「多久没动」判，不按「建了多久」：活跃桶的旧段走、当前段留")
    void staleSessionsAreJudgedByLastActivity() {
        Instant now = Instant.parse("2026-11-06T02:00:00Z");
        Instant longAgo = now.minus(Duration.ofDays(40));
        Instant justNow = now.minus(Duration.ofMinutes(5));
        String ow = owner("u-1");

        // 桶 A：最后动作在 40 天前。段与指针都该走。
        session("A", ow, longAgo, longAgo);
        pointer(ow, "web", "c-a", "s-a", longAgo);
        // 桶 B：活跃桶。旧段最后一次事件在 40 天前（该走），当前段刚写过（该留）。
        // B-now 的 created_at 也写成 40 天前——照 created_at 判的话它会被误删。
        session("B-old", ow, longAgo, longAgo);
        session("B-now", ow, longAgo, justNow);
        pointer(ow, "web", "c-b", "s-b-now", justNow);
        // mode: none 的形状：id 没有段，created_at 在 40 天前，但刚刚说过话。
        // 它靠事件时间活下来——这正是"none 也要能被判活"的判据（指针表里没有它的行）。
        session(SessionKeys.sessionIdOf(ow, "web", "c-legacy"), ow, longAgo, justNow);
        // cron：25 小时前的那一次运行。它的口径是 24 小时，不是 30 天。
        session(SessionKeys.sessionIdOf(ow, "http", "cron:task-1:1762300000000"),
                ow, now, now.minus(Duration.ofHours(25)));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMaintenance.Report report = maintenance("enforce", 500, registry).sweep(now);

        assertThat(report.enforced()).isTrue();
        assertThat(report.prunedSegments())
                .as("两个 40 天前的段：A 那一段（桶整体过期）与 B 的旧段（桶还活着、这一段死了）")
                .isEqualTo(2);
        assertThat(report.prunedConversations()).as("A 的指针没人要了").isEqualTo(1);
        assertThat(report.prunedCron()).as("cron 那条 25 小时，超过 24 小时的口径").isEqualTo(1);

        assertThat(sessionsInTable())
                .as("留下的是两个「刚说过话」的：B-now 的 created_at 也是 40 天前，"
                        + "无段形状那条更是连指针行都没有——它们留下来这件事本身就说明判据不是 created_at")
                .containsExactlyInAnyOrder("B-now",
                        SessionKeys.sessionIdOf(ow, "web", "c-legacy"));
        assertThat(jdbc.queryForObject("select chat_id from claw4j_session.conversation_pointer",
                String.class)).isEqualTo("c-b");

        // ---- 上限：桶比 max-entries 多时，驱逐最久没动的（OpenClaw 的 maxEntries）。
        freshSessionTables();
        // segment 与时间故意反着排：按字符串序会驱逐 c-3（最新那个），按时间序才是 c-1。
        pointer(ow, "web", "c-1", "zzz-oldest", now.minus(Duration.ofHours(3)));
        pointer(ow, "web", "c-2", "mmm-middle", now.minus(Duration.ofHours(2)));
        pointer(ow, "web", "c-3", "aaa-newest", now.minus(Duration.ofHours(1)));

        SessionMaintenance.Report evicted =
                maintenance("enforce", 2, new SimpleMeterRegistry()).sweep(now);

        assertThat(evicted.prunedOverLimit()).as("三个桶留两个，走的是最久没动的那个").isEqualTo(1);
        assertThat(jdbc.queryForList("select chat_id from claw4j_session.conversation_pointer",
                        String.class))
                .as("按 segment 的字符串序排会驱逐错人，按 touched_at 才是「最久没动」")
                .containsExactlyInAnyOrder("c-2", "c-3");
    }

    @Test
    @DisplayName("warn 档位一行不删，但把「本来会删多少」数出来——默认档位不能是静默空转")
    void warnModeDeletesNothingButCounts() {
        Instant now = Instant.parse("2026-11-06T02:00:00Z");
        Instant longAgo = now.minus(Duration.ofDays(40));
        String ow = owner("u-1");
        session("A", ow, longAgo, longAgo);
        pointer(ow, "web", "c-a", "s-a", longAgo);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMaintenance.Report report = maintenance("warn", 500, registry).sweep(now);

        assertThat(report.enforced()).isFalse();
        assertThat(report.pruned()).isZero();
        assertThat(sessionsInTable()).as("warn 下一条都不许删").containsExactly("A");
        assertThat(jdbc.queryForObject("select count(*) from claw4j_session.conversation_pointer",
                Integer.class)).isEqualTo(1);
        assertThat(registry.get(SessionMaintenance.PRUNABLE).tag("kind", "segments").gauge().value())
                .as("数得出来才叫「只报告」：这个值恒为 0 的话，默认档位就是一次静默空转")
                .isEqualTo(1.0);
        assertThat(registry.find(SessionMaintenance.PRUNED).counter())
                .as("一行没删，pruned 根本不该被注册出来")
                .isNull();
    }

    private SessionMaintenance maintenance(String mode, int maxEntries, MeterRegistry registry) {
        return new SessionMaintenance(jdbc,
                new SessionMaintenanceProperties(mode, Duration.ofDays(30), maxEntries),
                ScheduleProperties.defaults(), registry);
    }

    private void session(String id, String owner, Instant createdAt, Instant lastEventAt) {
        jdbc.update("insert into claw4j_session.ai_session (id, user_id, created_at)"
                + " values (?, ?, ?)", id, owner, Timestamp.from(createdAt));
        jdbc.update("insert into claw4j_session.ai_session_event"
                        + " (id, session_id, \"timestamp\", message_type) values (?, ?, ?, 'USER')",
                id + "-e", id, Timestamp.from(lastEventAt));
    }

    private void pointer(String owner, String channel, String chatId, String segment, Instant touchedAt) {
        jdbc.update("insert into claw4j_session.conversation_pointer"
                        + " (owner, channel, chat_id, segment, touched_at, day_started)"
                        + " values (?, ?, ?, ?, ?, ?)",
                owner, channel, chatId, segment, Timestamp.from(touchedAt),
                touchedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
    }

    private java.util.List<String> sessionsInTable() {
        return jdbc.queryForList("select id from claw4j_session.ai_session order by id", String.class);
    }

    private void freshSessionTables() {
        jdbc.update("delete from claw4j_session.ai_session_event");
        jdbc.update("delete from claw4j_session.ai_session");
        jdbc.update("delete from claw4j_session.conversation_pointer");
    }

    // ---------------------------------------------------------------- 装配

    private static String owner(String userId) {
        return SessionKeys.ownerOf(Principal.readOnly(userId));
    }

    /**
     * 一台会顺手记下提示词的端口：目标"注入没注入"只能从提示词上看，回执里看不出来。
     * usage 固定 12 + 34，于是"记了多少账"有一个确定的数可判。
     */
    private ChatClientAgentPort goalPort(List<String> prompts) {
        ChatModel model = new ChatModel() {

            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt.getInstructions().stream()
                        .map(message -> message.getText() == null ? "" : message.getText())
                        .reduce("", String::concat));
                return new FixedUsage(12, 34).call(prompt);
            }
        };
        return new ChatClientAgentPort(ChatClient.builder(model).build(), null, runs,
                null, null, null, null, null, new JdbcSessionGoalStore(jdbc, null));
    }

    private RunDelivery delivery(OutboundDelivery... channels) {
        // 没有 registry 时照常投递，只是没有指标——与 run 落库同一个降级口径。
        return new RunDelivery(runs, List.of(channels), null);
    }

    /** 把 span 收进列表的 tracer：这一条只关心"画了没有"，不关心画成什么样。 */
    private static RunTracer tracerInto(List<SpanData> spans) {
        SpanExporter exporter = new SpanExporter() {

            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                spans.addAll(batch);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        return new OtelRunTracer(SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build());
    }

    /** 记下"谁收到了什么"，不抛：这一层要验的是投递的路由，不是平台的限流。 */
    private static final class Recording implements OutboundDelivery {

        private final String channel;

        private final List<String> sent = new ArrayList<>();

        Recording(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public void send(String channelRef, String text) {
            sent.add(channelRef + "|" + text);
        }
    }

    /** 固定回答、固定 usage 的模型：token 那一项要有一个确定的数可判。 */
    private record FixedUsage(int promptTokens, int completionTokens) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage("华东上周成交 128400"))))
                    .metadata(ChatResponseMetadata.builder()
                            .model("test")
                            .usage(new DefaultUsage(promptTokens, completionTokens))
                            .build())
                    .build();
        }
    }
}
