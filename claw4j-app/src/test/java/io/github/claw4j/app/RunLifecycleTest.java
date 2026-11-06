package io.github.claw4j.app;

import io.github.claw4j.agent.ChatClientAgentPort;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.observability.OtelRunTracer;
import io.github.claw4j.agent.observability.RunTracer;
import io.github.claw4j.agent.run.Budget;
import io.github.claw4j.agent.run.DeliveryTarget;
import io.github.claw4j.agent.run.JdbcRunStore;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunBudget;
import io.github.claw4j.agent.run.RunBudgetProperties;
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

    private RunBudget budget;

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
        jdbc.update("delete from claw4j_run.agent_run_delivery");
        jdbc.update("delete from claw4j_run.agent_step");
        jdbc.update("delete from claw4j_run.agent_run");
        freshSessionTables();
        runs = new JdbcRunStore(jdbc);
        budget = new RunBudget(runs, RunBudgetProperties.defaults(),
                SessionResetProperties.defaults().idleMillis());
    }

    // ---------------------------------------------------------------- 花了多少（A04）

    @Test
    @DisplayName("一次 run 的第 7 次工具调用被拒：额度记在 run 上，不是每轮重置")
    void seventhToolCallOfOneRunIsRefused() {
        Run run = runs.start("u-1@fp-1", "fp-1", "对齐三张表的口径").orElseThrow();
        CountingTool tool = new CountingTool();
        MeteredToolCallback metered = new MeteredToolCallback(tool, null, budget);
        ToolContext context = new ToolContext(Map.of(ContextKeys.RUN_ID, run.id()));

        // 三轮各两次。轮次在这里不改变任何结果——这正是 A04 之前的行为的反面：
        // 那时额度每轮重置，这七次会全部放行，而"这件事一共烧了多少"没有地方记。
        for (int turn = 0; turn < 3; turn++) {
            metered.call("{}", context);
            metered.call("{}", context);
        }
        assertThat(tool.calls()).as("前 6 次都真的执行了").isEqualTo(6);

        String refused = metered.call("{}", context);

        assertThat(refused).contains("预算用尽").contains("工具调用 6/6 次");
        assertThat(tool.calls()).as("第 7 次不该真的碰到工具").isEqualTo(6);
        assertThat(budget.spent(run.id()).orElseThrow().toolCalls())
                .as("被拒的那次不算用掉的量：否则'还剩几次'会凭空少一次").isEqualTo(6);
    }

    @Test
    @DisplayName("超预算：不再调模型，写明结局，状态 budget_exhausted 能从库里读回")
    void exhaustedRunStopsWithAnOutcomeInTheDatabase() {
        Principal principal = Principal.readOnly("u-1");
        Run run = runs.start(SessionKeys.ownerOf(principal), "fp-1", "对齐三张表的口径").orElseThrow();
        InboundMessage message = new InboundMessage(
                principal, "chat-budget", "接着做", Instant.now(), null, run.id());
        // 会话键必须在第一次扣费之前写上：预算按会话累计，先扣后挂的那几笔
        // 落在 run 行上，而判据读的是会话行——看起来像"一次都没花"（ADR-0025）。
        runs.attachSession(run.id(),
                SessionKeys.sessionIdOf(principal, message.channel(), message.chatId()));
        for (int i = 0; i < 6; i++) {
            assertThat(budget.chargeToolCall(run.id())).isTrue();
        }
        ChatClientAgentPort port = new ChatClientAgentPort(
                ChatClient.builder(new NeverCalled()).build(), null, runs, budget);

        OutboundMessage answer = port.reply(message);

        assertThat(answer.text()).contains("预算用尽").contains("工具调用 6/6 次");
        // 判据是库里那两个数：状态与"为什么停"，都不是回执的措辞。
        assertThat(runs.find(run.id()).orElseThrow().status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
        assertThat(runs.stepsOf(run.id()))
                .as("为什么停在这一轮，库里要答得出来——这是审计最常问的一句")
                .anySatisfy(step -> assertThat(step.resultSummary()).contains("预算用尽"));
    }

    @Test
    @DisplayName("一轮就是一件事、答完即收尾：跨轮不存在「还没做完的 run」")
    void anAnsweredTurnLeavesNothingUnfinished() {
        Principal principal = Principal.readOnly("u-1");
        String owner = SessionKeys.ownerOf(principal);
        ChatClientAgentPort port = new ChatClientAgentPort(
                ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs, budget);

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
    @DisplayName("一轮的 token 与耗时记到 run 上：模型报多少记多少，不估算")
    void tokensAndWallClockOfATurnAreChargedToTheRun() {
        Principal principal = Principal.readOnly("u-1");
        Run run = runs.start(SessionKeys.ownerOf(principal), "fp-1", "上周华东成交额").orElseThrow();
        ChatClientAgentPort port = new ChatClientAgentPort(
                ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs, budget);

        port.reply(new InboundMessage(
                principal, "chat-tokens", "上周华东成交额", Instant.now(), null, run.id()));

        Budget spent = budget.spent(run.id()).orElseThrow();
        assertThat(spent.tokens()).as("12 + 34：模型报什么就记什么，不拿估算值补").isEqualTo(46L);
        assertThat(spent.millis()).as("耗时照记：开 run、落 step、扣额度三次往返跑不完 1 毫秒")
                .isPositive();
        assertThat(runs.find(run.id()).orElseThrow().status())
                .as("没有撞线的一轮照常是 done，不是 budget_exhausted").isEqualTo(RunStatus.DONE);
    }

    @Test
    @DisplayName("耗时是独立的一项：只撞它时，写明的结局要说是耗时")
    void wallClockIsItsOwnDimension() {
        Run run = runs.start("u-1@fp-1", "fp-1", "对齐三张表的口径").orElseThrow();

        budget.chargeTurn(run.id(), 0L, 1_500L);

        assertThat(budget.spent(run.id()).orElseThrow().millis()).isEqualTo(1_500L);
        Budget over = new Budget(0, 0L, 901_000L);
        assertThat(budget.exhausted(over)).isTrue();
        assertThat(budget.reason(over)).as("'预算用尽'四个字不够，得说清是哪一项").contains("耗时");
    }

    @Test
    @DisplayName("另一件事从零开始：定时任务每次运行各开一个 run，各计各的")
    void anotherRunStartsFromZero() {
        Run first = runs.start("u-1@fp-1", "fp-1", "第一件事").orElseThrow();
        Run second = runs.start("u-1@fp-1", "fp-1", "第二件事").orElseThrow();

        for (int i = 0; i < 6; i++) {
            assertThat(budget.chargeToolCall(first.id())).isTrue();
        }

        assertThat(budget.chargeToolCall(first.id())).isFalse();
        assertThat(budget.chargeToolCall(second.id()))
                .as("额度是 run 的属性：一件事烧完不影响另一件").isTrue();
    }

    // ---------------------------------------------------------------- 送到哪（A05）

    @Test
    @DisplayName("一件事登记两个地址：就地回答过的那个不重复推，另一个通道照样收到")
    void theOtherChannelGetsTheResult() {
        Recording deliveryFeishu = new Recording("feishu");
        Recording deliveryWeCom = new Recording("wecom");
        RunDelivery delivery = delivery(deliveryFeishu, deliveryWeCom);
        Run run = runs.start(owner("u-1"), "fp-1", "对齐三张表的口径").orElseThrow();

        // 在飞书开的头，在飞书里接着问了一句（就地回答了），企业微信那边也被登记过。
        delivery.register(run.id(), "feishu", "oc_zhang");
        delivery.register(run.id(), "wecom", "chat-9");
        runs.finish(run.id(), RunStatus.DONE, "三张表的口径对完了：成交额按支付时间算");
        delivery.announce(run.id(), RunStatus.DONE, "三张表的口径对完了：成交额按支付时间算",
                new DeliveryTarget("feishu", "oc_zhang"));

        assertThat(deliveryFeishu.sent).as("同一条内容出现两遍，人以为自己被重跑了").isEmpty();
        assertThat(deliveryWeCom.sent)
                .as("在别处接着做，原通道那头也要收到结果")
                .containsExactly("chat-9|三张表的口径对完了：成交额按支付时间算");
    }

    @Test
    @DisplayName("silent 一句都不推，但结果落库、按 id 查得到（不打扰不等于丢了）")
    void silentKeepsTheResultButSaysNothing() {
        Recording deliveryFeishu = new Recording("feishu");
        RunDelivery delivery = delivery(deliveryFeishu);
        Principal asker = Principal.readOnly("u-1");
        Run run = runs.start(owner("u-1"), "fp-1", "每周一跑一遍口径核对",
                NotifyPolicy.SILENT).orElseThrow();

        delivery.register(run.id(), "feishu", "oc_zhang");
        runs.finish(run.id(), RunStatus.DONE, "这周没有对不上的地方");
        delivery.announce(run.id(), RunStatus.DONE, "这周没有对不上的地方", null);

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

        // 同一件事在飞书开的头，在 HTTP 上查得到——这正是"绑 principal 不绑通道"。
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

    @Test
    @DisplayName("同一个地址登记两次只留一行：连着问三轮，收到结果的还是一个会话")
    void theSameAddressIsRegisteredOnce() {
        RunDelivery delivery = delivery(new Recording("feishu"));
        Run run = runs.start(owner("u-1"), "fp-1", "goal").orElseThrow();

        delivery.register(run.id(), "feishu", "oc_zhang");
        delivery.register(run.id(), "feishu", "oc_zhang");

        assertThat(delivery.targetsOf(run.id()))
                .containsExactly(new DeliveryTarget("feishu", "oc_zhang"));
    }

    // ---------------------------------------------------------------- 观测不碰这两张表（P14）

    @Test
    @DisplayName("观测不写业务库：开了 trace 之后 agent_step 的行数与内容一模一样（P14）")
    void tracingAddsNothingToTheAuditTables() {
        Principal principal = Principal.readOnly("u-1");

        Run plain = runs.start(owner("u-1"), "fp-1", "上周华东成交额").orElseThrow();
        new ChatClientAgentPort(ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs, budget)
                .reply(new InboundMessage(principal, "chat-1", "上周华东成交额", Instant.now(), null, plain.id()));

        List<SpanData> spans = new CopyOnWriteArrayList<>();
        Run traced = runs.start(owner("u-1"), "fp-1", "上周华东成交额").orElseThrow();
        new ChatClientAgentPort(ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs, budget,
                null, tracerInto(spans))
                .reply(new InboundMessage(principal, "chat-1", "上周华东成交额", Instant.now(), null, traced.id()));

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

    private RunDelivery delivery(OutboundDelivery... channels) {
        // 没有 registry 时照常投递，只是没有指标——与 run 落库同一个降级口径。
        return new RunDelivery(runs, jdbc, List.of(channels), null);
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

    /** 一个只数自己被执行了几次的工具。 */
    private static final class CountingTool implements ToolCallback {

        private int calls;

        /** 真的被执行的次数。被预算挡回的那一次不该算进来。 */
        int calls() {
            return calls;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("lookup").description("查一张表").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return "128400";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            calls++;
            return "128400";
        }
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

    /** 一旦被调用就红的模型：用它钉住"预算用尽时这一轮不该再调模型"。 */
    private static final class NeverCalled implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("预算已经用尽，这一轮不该再调用模型");
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
