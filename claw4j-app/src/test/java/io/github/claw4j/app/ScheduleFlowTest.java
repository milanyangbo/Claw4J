package io.github.claw4j.app;

import io.github.claw4j.agent.ChatClientAgentPort;
import io.github.claw4j.agent.ToolCallBudget;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.run.DeliveryTarget;
import io.github.claw4j.agent.run.JdbcRunStore;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunBudget;
import io.github.claw4j.agent.run.RunBudgetProperties;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.schedule.Schedule;
import io.github.claw4j.agent.schedule.ScheduleAuthorization;
import io.github.claw4j.agent.schedule.ScheduleProperties;
import io.github.claw4j.agent.schedule.ScheduleScanner;
import io.github.claw4j.agent.schedule.ScheduleStore;
import io.github.claw4j.agent.schedule.ScheduleTools;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到点就跑这一条链（P04、P05），跑在真库上。
 *
 * <p><b>为什么必须真库</b>：这条链上的三件事全在 SQL 里——"同一时刻只有一个人认领到"
 * 是 {@code for update skip locked}、"实例重启不丢时间点"是 {@code next_run_at} 那一列、
 * "连续三次停用"是失败计数与停用写在同一句 update 里。换成内存替身，这三条怎么改都绿，
 * 而它们正是最容易改错的地方（把 {@code skip locked} 删掉，一条用例都不会红）。
 *
 * <p><b>模型是假的那一段</b>：这里验的是"到点有没有开 run、开的是不是任务主人的、
 * 失败几次之后停没停、收权之后停不停"，不是模型的判断力。真模型闭环另有一条
 * （{@code ApplicationAssemblyTest}）。
 *
 * <p><b>权限按当前的角色表重算</b>（P05）：{@link #roleTable} 就是那张表，
 * 用例里把它删掉一个角色、或者把某个角色的表权限收窄，等价于运维改了配置。
 * 这一组同时守着"收权对定时任务真的有效"——那是这条链上唯一会持续越权的形状。
 *
 * <p>扫描器的**决策分支**（什么情况记失败、什么情况直接停用、通知里说什么）在 agent 的
 * {@code ObservabilityTest} 里用替身跑，那里不需要库；这里只管库上的那一半。
 */
@Testcontainers
class ScheduleFlowTest {

    /** 每天九点（Asia/Shanghai）。用例里的时间断言都按它算。 */
    private static final String DAILY_NINE = "0 0 9 * * *";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final String GOAL = "每天九点报一次华东成交额";

    /** 任务声明会用到的那个工具。权限登记在 {@link #permissions()} 里。 */
    private static final String TOOL = "query_sales";

    /** 与 run 的归属键同一个口径（{@code SessionKeys.ownerOf}：userId@权限指纹）。 */
    private static final Principal OWNER_PRINCIPAL =
            Principal.of("u-owner", "admin", Set.of("*"), Set.of("*"));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private static JdbcRunStore runs;

    /**
     * 部署里的那张角色表（{@code claw4j.channel.http.roles}）：角色 → 权限。
     *
     * <p>用例在它上面做两件事：删一个角色（收权）、把某个角色的表权限收窄。
     * 用真的可变表而不是给每个用例造一个新的 {@link RoleDirectory}，
     * 是因为"改配置之后下一次到点会怎样"正是这张票要验的那件事。
     */
    private final Map<String, Principal> roleTable = new LinkedHashMap<>();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        runs = new JdbcRunStore(jdbc);
    }

    @BeforeEach
    void freshTables() {
        jdbc.update("delete from claw4j_run.agent_schedule");
        jdbc.update("delete from claw4j_run.agent_run_delivery");
        jdbc.update("delete from claw4j_run.agent_step");
        jdbc.update("delete from claw4j_run.agent_run");
        roleTable.clear();
        roleTable.put("admin", OWNER_PRINCIPAL);
    }

    @Test
    @DisplayName("到点了：开一个 run，跑在任务主人的身份上，回答落在 run 上")
    void aDueTaskStartsARunOnTheOwnersBehalf() {
        Recorder delivery = new Recorder();
        String id = dueTask(DAILY_NINE, NotifyPolicy.SILENT);

        scannerWith(new ScriptedModel("上周华东 128400"), delivery).scan();

        List<Run> created = jdbc.query("select id from claw4j_run.agent_run",
                (row, index) -> runs.find(row.getString("id")).orElseThrow());
        assertThat(created).hasSize(1);
        Run run = created.getFirst();
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(run.owner()).isEqualTo(SessionKeys.ownerOf(OWNER_PRINCIPAL));
        assertThat(run.goal()).isEqualTo(GOAL);
        // 静默档：结果落在 run 上，但一条都不推（spec 第 16 条）
        assertThat(run.result()).isEqualTo("上周华东 128400");
        assertThat(delivery.sent).isEmpty();
        // 下一次推到明天九点，而不是"30 秒后再来一次"
        assertThat(nextRunAt(id)).isAfter(Instant.now());
        assertThat(schedule(id).failures()).isZero();
    }

    @Test
    @DisplayName("两个实例同时扫：同一条任务只开一个 run")
    void twoInstancesScanningAtTheSameMomentRunItOnce() {
        Recorder delivery = new Recorder();
        dueTask(DAILY_NINE, NotifyPolicy.SILENT);
        CyclicBarrier gate = new CyclicBarrier(2);

        // 两个"实例"各有一份 store（各用自己的连接），同一时刻扫同一张表
        List<Thread> workers = List.of(
                new Thread(() -> scanTogether(gate,
                        scannerWith(new ScriptedModel("a"), delivery))),
                new Thread(() -> scanTogether(gate, new ScheduleScanner(
                        store(), port(new ScriptedModel("b")), authorization(), List.of(delivery),
                        ScheduleProperties.defaults(), null))));
        workers.forEach(Thread::start);
        workers.forEach(ScheduleFlowTest::join);

        assertThat(count("select count(*) from claw4j_run.agent_run")).isEqualTo(1);
    }

    @Test
    @DisplayName("实例重启不丢时间点：库里的 next_run_at 决定跑不跑")
    void aRestartedInstanceStillKnowsWhenTheTaskWasDue() {
        Recorder delivery = new Recorder();
        String id = createTask(DAILY_NINE, NotifyPolicy.SILENT);
        Instant planned = nextRunAt(id);
        // 还没到点：两个"实例"先后扫描都不跑，时间点也不动
        for (int i = 0; i < 2; i++) {
            scannerWith(new ScriptedModel("不该跑"), delivery).scan();
        }
        assertThat(count("select count(*) from claw4j_run.agent_run")).isZero();
        assertThat(nextRunAt(id)).isEqualTo(planned);

        // 时间到了：换一个"刚启动的实例"，它照样知道该跑（时间点在库里，不在进程里）
        makeDue(id);
        scannerWith(new ScriptedModel("到了"), delivery).scan();

        assertThat(count("select count(*) from claw4j_run.agent_run")).isEqualTo(1);
    }

    @Test
    @DisplayName("连着三次没跑成就停用，并推一条带最后错误的通知给任务主人")
    void threeFailuresInARowDisableTheTaskAndTellTheOwner() {
        Recorder delivery = new Recorder();
        String id = dueTask(DAILY_NINE, NotifyPolicy.SILENT);

        // 每一次都让模型炸：三次扫描 = 三个到点
        for (int i = 0; i < 3; i++) {
            makeDue(id);
            scannerWith(new BrokenModel("模型服务 503"), delivery).scan();
        }

        Schedule after = schedule(id);
        assertThat(after.enabled()).isFalse();
        assertThat(after.failures()).isEqualTo(3);
        assertThat(after.disabledReason()).contains("连续 3 次");
        assertThat(after.lastError()).contains("模型服务 503");

        // 通知里要能看出"哪个任务、为什么停"——只发一句"已停用"等于让人自己去翻
        assertThat(delivery.sent).hasSize(1);
        assertThat(delivery.sent.getFirst()).contains(GOAL)
                .contains("模型服务 503").contains("已停用");
    }

    @Test
    @DisplayName("中间成功过一次就不算连续：两次失败 + 一次成功 + 再败一次仍然启用")
    void aSuccessResetsTheFailureCount() {
        Recorder delivery = new Recorder();
        String id = dueTask(DAILY_NINE, NotifyPolicy.SILENT);

        for (int i = 0; i < 2; i++) {
            makeDue(id);
            scannerWith(new BrokenModel("超时"), delivery).scan();
        }
        makeDue(id);
        scannerWith(new ScriptedModel("好了"), delivery).scan();
        makeDue(id);
        scannerWith(new BrokenModel("又超时"), delivery).scan();

        Schedule after = schedule(id);
        assertThat(after.enabled()).isTrue();
        assertThat(after.failures()).isEqualTo(1);
        assertThat(after.lastError()).contains("又超时");
        assertThat(delivery.sent).isEmpty();
    }

    @Test
    @DisplayName("cron 坏了当场停用：再试三次也不会变好，攒三次只是晚三天才停")
    void aBrokenCronIsDisabledImmediately() {
        Recorder delivery = new Recorder();
        String id = brokenCronTask("每天九点");

        scannerWith(new ScriptedModel("不该跑"), delivery).scan();

        Schedule after = schedule(id);
        assertThat(after.enabled()).isFalse();
        // 失败计数不动：它不是"跑失败了"，是"根本没跑"
        assertThat(after.failures()).isZero();
        assertThat(after.disabledReason()).contains("cron 解析不了");
        assertThat(count("select count(*) from claw4j_run.agent_run")).isZero();
        assertThat(delivery.sent).hasSize(1);
    }

    @Test
    @DisplayName("错过的时间点不补跑：停了三天回来，只跑一次，下一次从现在往后算")
    void aMissedOccurrenceIsNotBackfilled() {
        Recorder delivery = new Recorder();
        String id = createTask(DAILY_NINE, NotifyPolicy.SILENT);
        jdbc.update("update claw4j_run.agent_schedule set next_run_at = now() - interval '3 days'"
                + " where id = ?", id);

        scannerWith(new ScriptedModel("补一份"), delivery).scan();

        assertThat(count("select count(*) from claw4j_run.agent_run")).isEqualTo(1);
        ZonedDateTime next = nextRunAt(id).atZone(SHANGHAI);
        assertThat(next).isAfter(ZonedDateTime.now(SHANGHAI));
        assertThat(next.getHour()).isEqualTo(9);
        assertThat(next.getMinute()).isZero();
    }

    @Test
    @DisplayName("停用的任务到点也不跑：停用是给扫描看的，不是给人看的")
    void aDisabledTaskIsNotPickedUp() {
        Recorder delivery = new Recorder();
        String id = dueTask(DAILY_NINE, NotifyPolicy.SILENT);
        jdbc.update("update claw4j_run.agent_schedule set enabled = false where id = ?", id);

        scannerWith(new ScriptedModel("不该跑"), delivery).scan();

        assertThat(count("select count(*) from claw4j_run.agent_run")).isZero();
    }

    // ---- P05：从对话里建任务，以及到点那一刻的身份 ----

    @Test
    @DisplayName("从对话里建一条：库里记的是身份与工具名（不是权限），回执里说清下一次什么时候")
    void aTaskBuiltFromTheConversationKeepsIdentityNotPermissions() throws Exception {
        ToolCallback create = toolNamed(ScheduleTools.CREATE);

        String reply = create.call("""
                {"goal":"每天九点报一次华东成交额","cron":"0 0 9 * * *",
                 "zone":"Asia/Shanghai","tools":["query_sales"]}""",
                toolContext(OWNER_PRINCIPAL));

        assertThat(reply).contains("已建好").contains("query_sales")
                .as("回执要带上「下一次是什么时候」：模型自己算不出 cron 的日历语义")
                .contains("下一次");
        // 库里那一行：身份两列 + 工具清单，没有任何一份权限快照
        Map<String, Object> row = jdbc.queryForMap(
                "select owner_user_id, owner_role, required_tools, notify_policy, channel,"
                        + " channel_ref from claw4j_run.agent_schedule");
        assertThat(row.get("owner_user_id")).isEqualTo(OWNER_PRINCIPAL.userId());
        assertThat(row.get("owner_role")).isEqualTo(OWNER_PRINCIPAL.role());
        assertThat((String[]) ((java.sql.Array) row.get("required_tools")).getArray())
                .containsExactly(TOOL);
        assertThat(row.get("notify_policy")).as("定时任务默认静默").isEqualTo("silent");
        assertThat(row.get("channel")).as("登记过会话就记住送回哪儿").isEqualTo(Recorder.CHANNEL);
        assertThat(row.get("channel_ref")).isEqualTo("oc_sched");
    }

    @Test
    @DisplayName("建不成的不落库：没权限的工具当场拒，理由原样交给模型")
    void aTaskOnAToolTheCreatorCannotUseIsRefusedOnTheSpot() {
        // 角色还在，但读不到 sales_order（工具要的那张表）。调用者的身份也是这一份——
        // 真实入口那里 principal 就是从这张表算出来的，测试里造两份不一致的身份等于造一个不存在的世界。
        Principal weakened = Principal.of("u-owner", "admin", Set.of("region"), Set.of("*"));
        roleTable.put("admin", weakened);

        String reply = toolNamed(ScheduleTools.CREATE).call("""
                {"goal":"每天九点报一次华东成交额","cron":"0 0 9 * * *","tools":["query_sales"]}""",
                toolContext(weakened));

        assertThat(reply).contains("没建成").contains("sales_order");
        assertThat(count("select count(*) from claw4j_run.agent_schedule"))
                .as("建不成的任务一条都不该留：留着它明天九点必然失败一次")
                .isZero();
    }

    @Test
    @DisplayName("把创建者的角色删掉：下一次到点就停用并通知主人，一次模型都不调")
    void losingTheRoleStopsTheTaskAndTellsItsOwner() {
        Recorder delivery = new Recorder();
        String id = dueTask(DAILY_NINE, NotifyPolicy.SILENT);
        // 收权：这个角色从授权表里删掉（运维改了配置）
        roleTable.clear();

        scannerWith(new ScriptedModel("不该跑"), delivery).scan();

        Schedule after = schedule(id);
        assertThat(after.enabled()).isFalse();
        assertThat(after.disabledReason()).contains("身份认不出来");
        assertThat(after.failures()).as("再试三次也不会变好，所以不攒次数").isZero();
        assertThat(count("select count(*) from claw4j_run.agent_run")).isZero();
        assertThat(delivery.sent).hasSize(1);
        assertThat(delivery.sent.getFirst()).contains(GOAL).contains("已停用");
    }

    @Test
    @DisplayName("角色还在但那个工具不再给：同样当场停用，理由说清是哪张表")
    void losingTheToolStopsTheTaskToo() {
        Recorder delivery = new Recorder();
        String id = dueTask(DAILY_NINE, NotifyPolicy.SILENT);
        roleTable.put("admin", Principal.of("x", "admin", Set.of("region"), Set.of("*")));

        scannerWith(new ScriptedModel("不该跑"), delivery).scan();

        Schedule after = schedule(id);
        assertThat(after.enabled()).isFalse();
        assertThat(after.disabledReason()).contains("sales_order");
        assertThat(count("select count(*) from claw4j_run.agent_run")).isZero();
    }

    @Test
    @DisplayName("两次到点各开一个 run、各记一笔预算：工具用的是任务主人的身份")
    void eachFiringRunsWithTheOwnersIdentityAndItsOwnBudget() {
        Recorder delivery = new Recorder();
        String id = dueTask(DAILY_NINE, NotifyPolicy.SILENT);

        toolCallingScanner(delivery).scan();
        makeDue(id);
        toolCallingScanner(delivery).scan();

        List<String> runIds = jdbc.queryForList("select id from claw4j_run.agent_run order by created_at",
                String.class);
        assertThat(runIds).as("每次到点都是新的一件").hasSize(2);
        for (String runId : runIds) {
            assertThat(runs.spent(runId, java.time.Instant.EPOCH).orElseThrow().toolCalls())
                    .as("定时任务的 chatId 固定，两次运行落在同一段会话里，预算是合成 2；"
                            + "跨了 idle-minutes 就清零，所以它不会把第二天的运行卡住")
                    .isEqualTo(2);
            assertThat(runs.find(runId).orElseThrow().owner())
                    .isEqualTo(SessionKeys.ownerOf(OWNER_PRINCIPAL));
        }
        assertThat(seenPrincipals).as("工具在谁的身份下被放行")
                .hasSize(2).containsOnly(OWNER_PRINCIPAL.userId());
    }

    // ---- 下面是把各条用例拼起来的零件 ----

    /**
     * 建一条任务，然后把 {@code next_run_at} 拨到"刚刚到点"。
     *
     * <p><b>拨时间而不是等时间</b>：等明天九点的用例没人会跑第二遍。
     * 而"到点"这件事在库里就是一列，改它比绕开调度器诚实。
     */
    private String dueTask(String cron, NotifyPolicy policy) {
        String id = createTask(cron, policy);
        makeDue(id);
        return id;
    }

    private String createTask(String cron, NotifyPolicy policy) {
        Optional<Schedule> created = store().create(new ScheduleStore.Request(
                SessionKeys.ownerOf(OWNER_PRINCIPAL), GOAL, cron, SHANGHAI.getId(), policy,
                Recorder.CHANNEL, "oc_sched", OWNER_PRINCIPAL, List.of(TOOL)));
        assertThat(created).as("cron 是好的，任务应该建得起来").isPresent();
        return created.orElseThrow().id();
    }

    /**
     * 库里一行 cron 是坏的记录。
     *
     * <p>不经过 {@link ScheduleStore#create}（那一步会拒绝坏表达式），因为这段要模拟的是
     * "库里已经躺着一条坏记录"——旧版本写进去的、或者手改过的。调度器撞上它时该怎么办，
     * 与它当初怎么进来的无关。
     */
    private String brokenCronTask(String cron) {
        String id = "broken-cron";
        jdbc.update("insert into claw4j_run.agent_schedule"
                        + " (id, owner, channel, channel_ref, goal, cron, zone, notify_policy,"
                        + "  owner_user_id, owner_role, required_tools, next_run_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() - interval '1 second')",
                id, SessionKeys.ownerOf(OWNER_PRINCIPAL), Recorder.CHANNEL, "oc_sched",
                GOAL, cron, SHANGHAI.getId(), NotifyPolicy.SILENT.column(),
                OWNER_PRINCIPAL.userId(), OWNER_PRINCIPAL.role(),
                // 转成 Object：String[] 是 Object[]，直接当变参会被摊成一串单个参数
                (Object) new String[]{TOOL});
        return id;
    }

    private void makeDue(String id) {
        jdbc.update("update claw4j_run.agent_schedule set next_run_at = now() - interval '1 second'"
                + " where id = ?", id);
    }

    /** 每次现造：它只有几条 SQL 与一个连接池的引用，没有需要留住的状态。 */
    private static ScheduleStore store() {
        return new ScheduleStore(jdbc);
    }

    /** 部署里的角色表：问"这个角色现在还有什么权限"，答不上来就是空。 */
    private RoleDirectory roleDirectory() {
        return (userId, role) -> {
            Principal grant = roleTable.get(role);
            return grant == null ? Optional.empty()
                    : Optional.of(Principal.of(userId, role, grant.accessibleTables(),
                            grant.allowedActions()));
        };
    }

    /** 工具登记表：与生产同一份形状，{@link #TOOL} 要读 {@code sales_order}。 */
    private static List<ToolPermissionRegistry> permissions() {
        return List.of(ToolPermissionRegistry.of(
                Map.of(TOOL, ToolPermission.read("sales_order"))));
    }

    private ScheduleAuthorization authorization() {
        return new ScheduleAuthorization(roleDirectory(), permissions());
    }

    private ScheduleScanner scannerWith(ChatModel model, Recorder delivery) {
        return new ScheduleScanner(store(), port(model), authorization(),
                List.of(delivery), ScheduleProperties.defaults(), null);
    }

    /**
     * 一个"到点就真的调一次工具"的门：预算、工具计数、鉴权链都是真的。
     *
     * <p>用它验的是这张票里最容易只写在文档上的两句：任务跑在主人的身份上（工具真的被放行），
     * 以及<b>每次运行单独计预算</b>（两次到点各记一笔，不是合成两笔）。
     */
    private ChatClientAgentPort toolCallingPort() {
        RunBudget budget = new RunBudget(runs, RunBudgetProperties.defaults());
        ToolCallback metered = new MeteredToolCallback(new RecordingTool(seenPrincipals), null, budget);
        ChatClient chat = ChatClient.builder(new ToolThenAnswerModel(), ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder()
                                .toolCallingManager(ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP)))
                // 鉴权链是生产那条：工具能跑通，说明这一次调用用的是任务主人那份权限
                .defaultAdvisors(new AuthorizationAdvisor(permissions()))
                .defaultToolCallbacks(metered)
                .build();
        return new ChatClientAgentPort(chat, null, runs, budget);
    }

    private ScheduleScanner toolCallingScanner(Recorder delivery) {
        return new ScheduleScanner(store(), toolCallingPort(), authorization(),
                List.of(delivery), ScheduleProperties.defaults(), null);
    }

    /** 到点那次工具调用看到的身份。断言的对象是它，不是"日志里写了什么"。 */
    private final List<String> seenPrincipals = new CopyOnWriteArrayList<>();

    /** 真门 + 假模型：run 是真的、鉴权链是真的，只有"模型怎么答"是写死的。 */
    private static ChatClientAgentPort port(ChatModel model) {
        ChatClient chat = ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder()
                                .toolCallingManager(ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP)))
                .build();
        return new ChatClientAgentPort(chat, null, runs);
    }

    private static void scanTogether(CyclicBarrier gate, ScheduleScanner scanner) {
        try {
            gate.await();
        } catch (Exception broken) {
            throw new IllegalStateException(broken);
        }
        scanner.scan();
    }

    private static void join(Thread worker) {
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static Schedule schedule(String id) {
        return store().find(id).orElseThrow();
    }

    private static Instant nextRunAt(String id) {
        return schedule(id).nextRunAt();
    }

    /** 三个工具按生产那套装配：同一个 store、同一份角色表、同一个收件地址来源。 */
    private ScheduleTools tools() {
        return new ScheduleTools(store(), authorization(),
                runId -> List.of(new DeliveryTarget(Recorder.CHANNEL, "oc_sched")));
    }

    private ToolCallback toolNamed(String name) {
        return Arrays.stream(tools().getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有这个工具：" + name));
    }

    /** 模型那一侧看到的那一份上下文：身份 + 这一轮的 run（工具靠它找收件地址）。 */
    private static ToolContext toolContext(Principal principal) {
        return new ToolContext(Map.of(ContextKeys.PRINCIPAL, principal,
                ContextKeys.RUN_ID, "run-from-chat"));
    }

    /** 记录推出去的话。停用通知是"无人值守"唯一能被听见的地方，所以要读原文。 */
    private static final class Recorder implements OutboundDelivery {

        private static final String CHANNEL = "feishu";

        private final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public String channel() {
            return CHANNEL;
        }

        @Override
        public void send(String channelRef, String text) {
            sent.add(text);
        }
    }

    /** 固定脚本的模型桩：按顺序交出预先写好的回答。 */
    private static final class ScriptedModel implements ChatModel {

        private final Deque<String> script = new ArrayDeque<>();

        private final ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("scripted").temperature(0.0).build();

        private ScriptedModel(String... answers) {
            script.addAll(List.of(answers));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String next = script.poll();
            return answer(next == null ? "（脚本用完了）" : next);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }
    }

    /** 每次调用都炸的模型：定时任务失败那条链要的就是一个稳定的失败源。 */
    private static final class BrokenModel implements ChatModel {
        private final String message;

        private BrokenModel(String message) {
            this.message = message;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException(message);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(new IllegalStateException(message));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("broken").build();
        }
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /**
     * 第一轮要调工具、第二轮给答案的模型。
     *
     * <p>它让"到点跑一轮"走完整条工具循环：鉴权 → 执行 → 回灌 → 收尾。
     * 只回文本的桩会让这一整段都不发生，那时"任务是按主人的权限跑的"就无从验起。
     */
    private static final class ToolThenAnswerModel implements ChatModel {

        private int turn;

        @Override
        public ChatResponse call(Prompt prompt) {
            if (turn++ > 0) {
                return answer("华东上周 128400");
            }
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", TOOL, "{}")))
                    .build();
            return ChatResponse.builder().generations(List.of(new Generation(message))).build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("scripted-tool").build();
        }
    }

    /** 真的会跑一次的工具：它记下自己看到的是谁的身份——那就是"跑在主人名下"的证据。 */
    private static final class RecordingTool implements ToolCallback {

        private static final ToolDefinition DEFINITION = ToolDefinition.builder()
                .name(TOOL)
                .description("查成交额")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();

        private final List<String> seen;

        private RecordingTool(List<String> seen) {
            this.seen = seen;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DEFINITION;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            Object principal = toolContext == null ? null
                    : toolContext.getContext().get(ContextKeys.PRINCIPAL);
            seen.add(principal instanceof Principal who ? who.userId() : "（没有身份）");
            return "128400";
        }
    }
}
