package io.github.claw4j.app;

import io.github.claw4j.agent.ChatClientAgentPort;
import io.github.claw4j.agent.observability.MeteredToolCallback;
import io.github.claw4j.agent.run.Budget;
import io.github.claw4j.agent.run.JdbcRunStore;
import io.github.claw4j.agent.run.Run;
import io.github.claw4j.agent.run.RunBudget;
import io.github.claw4j.agent.run.RunBudgetProperties;
import io.github.claw4j.agent.run.RunStatus;
import io.github.claw4j.agent.run.RunStore;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预算按 run 累计（A04）：工具调用次数、token、耗时三项，跨轮不重置。
 *
 * <p><b>为什么这份放在 app 模块</b>：agent 模块的测试文件已经到上限（10 个），
 * 而门禁那条"每模块最多 10 个测试文件"钉的是文件数，不是"测试必须挨着被测类"。
 * 放这里还能顺带用真库——这恰恰是本票最需要的东西。
 *
 * <p><b>用真库而不是内存版</b>：预算的全部意义是"跨请求、跨进程之后仍然记得花了多少"。
 * 换一个 store 实例读（等价于进程重启）是这里最低的判据，
 * 内存版会把这条判据变成一句自我声明。
 *
 * <p><b>判据是库里那几个数，不是回执的措辞</b>：与 run 那两张表同一个纪律——
 * "没抛异常"证明不了额度被扣了，只有读回那个数才算。
 */
@Testcontainers
class RunBudgetTest {

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
        jdbc.update("delete from claw4j_run.agent_step");
        jdbc.update("delete from claw4j_run.agent_run");
        runs = new JdbcRunStore(jdbc);
        budget = new RunBudget(runs, RunBudgetProperties.defaults());
    }

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
        for (int i = 0; i < 6; i++) {
            assertThat(budget.chargeToolCall(run.id())).isTrue();
        }
        ChatClientAgentPort port = new ChatClientAgentPort(
                ChatClient.builder(new NeverCalled()).build(), null, runs, budget);

        OutboundMessage answer = port.reply(new InboundMessage(
                principal, "chat-1", "接着做", Instant.now(), null, run.id()));

        assertThat(answer.text()).contains("预算用尽").contains("工具调用 6/6 次");
        // 判据是库里那两个数：状态与"为什么停"，都不是回执的措辞。
        assertThat(runs.find(run.id()).orElseThrow().status()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
        assertThat(runs.stepsOf(run.id()))
                .as("为什么停在这一轮，库里要答得出来——这是审计最常问的一句")
                .anySatisfy(step -> assertThat(step.resultSummary()).contains("预算用尽"));
    }

    @Test
    @DisplayName("一轮的 token 与耗时记到 run 上：模型报多少记多少，不估算")
    void tokensAndWallClockOfATurnAreChargedToTheRun() {
        Principal principal = Principal.readOnly("u-1");
        Run run = runs.start(SessionKeys.ownerOf(principal), "fp-1", "上周华东成交额").orElseThrow();
        ChatClientAgentPort port = new ChatClientAgentPort(
                ChatClient.builder(new FixedUsage(12, 34)).build(), null, runs, budget);

        port.reply(new InboundMessage(
                principal, "chat-1", "上周华东成交额", Instant.now(), null, run.id()));

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
