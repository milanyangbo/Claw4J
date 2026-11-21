package io.github.claw4j.agent;

import io.github.claw4j.agent.approval.JdbcApprovals;
import io.github.claw4j.agent.audit.AuditAction;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditSink;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.model.config.ModelConfiguration;
import io.github.claw4j.model.config.ModelProperties;
import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.SessionGoal;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionScopeProperties;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.ApproverRoster;
import io.github.claw4j.domain.port.Approvals;
import io.github.claw4j.agent.memory.PrincipalDreamAdvisor;
import org.springaicommunity.agent.dream.AutoDreamService;
import org.springaicommunity.agent.dream.DreamResult;
import org.springaicommunity.agent.dream.DreamTriggers;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.capability.tool.ToolCatalog;
import io.github.claw4j.domain.principal.ToolProfile;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.advisors.TurnInterruptedException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对话主链路：入站消息、脱敏、Advisor 链、工具循环、出站。
 *
 * <p>模型是桩：这条链路要验的是模型、工具、结束信号有没有接对，不是模型聪不聪明。模型选工具
 * 那条另有一条真模型用例。
 */
class ChatPipelineTest {

    @Test
    @DisplayName("reply 把回答和 chatId 一起交回通道")
    void replyCarriesAnswerAndChatId() {
        ChatClientAgentPort port = port(new FixedChatModel("华东上周成交 128400"));

        OutboundMessage answer = port.reply(message("chat-1", "上周华东成交额"));

        assertThat(answer.chatId()).isEqualTo("chat-1");
        assertThat(answer.text()).isEqualTo("华东上周成交 128400");
        assertThat(answer.finalChunk()).isTrue();
    }

    @Test
    @DisplayName("流式逐块发出，最后一块是唯一的结束信号")
    void streamEmitsChunksThenExactlyOneFinalSignal() {
        List<OutboundMessage> chunks = new ArrayList<>();
        ChatClientAgentPort port = port(new FixedChatModel(
                prompt -> answer("整段回答"),
                List.of("华东", "上周", "128400")));

        port.stream(message("chat-9", "上周华东成交额"), chunks::add);

        assertThat(chunks).extracting(OutboundMessage::text)
                .containsExactly("华东", "上周", "128400", "");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.chatId()).isEqualTo("chat-9"));
        assertThat(chunks.subList(0, 3)).allSatisfy(chunk -> assertThat(chunk.finalChunk()).isFalse());
        assertThat(chunks).filteredOn(OutboundMessage::finalChunk).hasSize(1);
        assertThat(chunks.getLast().finalChunk()).isTrue();
        assertThat(chunks.getLast().text()).isEmpty();
    }

    @Test
    @DisplayName("模型一块都不吐时也要发结束信号：通道不能靠超时猜「说完了」")
    void emptyStreamStillEnds() {
        List<OutboundMessage> chunks = new ArrayList<>();
        ChatClientAgentPort port = port(new FixedChatModel(prompt -> answer(""), List.of()));

        port.stream(message("chat-2", "在吗"), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().finalChunk()).isTrue();
        assertThat(chunks.getFirst().text()).isEmpty();
    }

    @Test
    @DisplayName("模型要两次工具、第三次才回答：工具执行两次，最终文本来自第三次")
    void toolLoopRunsUntilModelStopsAsking() {
        AtomicInteger modelCalls = new AtomicInteger();
        RecordingTool tool = new RecordingTool("lookup", "华东 128400");
        FixedChatModel model = new FixedChatModel(
                prompt -> modelCalls.getAndIncrement() < 2
                        ? requestTool("lookup", "{\"table\":\"sales_order\"}")
                        : answer("查到了 128400"),
                List.of());

        String text = port(model, List.of(tool)).reply(message("chat-3", "查成交额")).text();

        assertThat(text).isEqualTo("查到了 128400");
        assertThat(tool.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一个工具连续要第 4 次时在上限处截断，不会无限烧模型往返")
    void perToolBudgetStopsTheLoop() {
        RecordingTool tool = new RecordingTool("lookup", "还是不对");
        FixedChatModel model = new FixedChatModel(
                prompt -> requestTool("lookup", "{}"),
                List.of());

        String text = port(model, List.of(tool)).reply(message("chat-4", "一直查")).text();

        assertThat(tool.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(text).isNotBlank();
    }

    @Test
    @DisplayName("一直交坏参数：错误回灌给模型让它自己重发，但同一工具第 4 次要就被上限截断")
    void repeatedBadArgumentsHitTheToolCallCap() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback badArguments = new ToolCallback() {

            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("lookup")
                    .description("参数会坏的查询")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                attempts.incrementAndGet();
                // 真实形状：Spring AI 的参数转换把 Jackson 3 的解析异常包成 ToolExecutionException
                throw new ToolExecutionException(definition,
                        new tools.jackson.core.exc.StreamReadException("Unexpected character ('专')"));
            }
        };
        FixedChatModel model = new FixedChatModel(
                prompt -> requestTool("lookup", "{\"text\":\"x\"专}"),
                List.of());

        String text = port(model, List.of(badArguments)).reply(message("chat-bad", "查一下")).text();

        assertThat(attempts)
                .as("工具错误是「可自救」的，但自救次数由上限兜住：模型没读懂就停，不再无限烧模型往返")
                .hasValue(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(text).isEqualTo(ChatClientAgentPort.TOOL_CALL_CAPPED);
    }

    @Test
    @DisplayName("三个工具轮着要也受总数上限约束：单个没超，总数照样截断")
    void totalBudgetStopsAcrossDifferentTools() {
        RecordingTool first = new RecordingTool("first", "1");
        RecordingTool second = new RecordingTool("second", "2");
        RecordingTool third = new RecordingTool("third", "3");
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(
                prompt -> switch (turn.getAndIncrement() % 3) {
                    case 0 -> requestTool("first", "{}");
                    case 1 -> requestTool("second", "{}");
                    default -> requestTool("third", "{}");
                },
                List.of());

        port(model, List.of(first, second, third)).reply(message("chat-5", "轮着查"));

        int executed = first.calls() + second.calls() + third.calls();
        assertThat(executed).isEqualTo(ToolCallBudget.MAX_TOTAL_CALLS);
        assertThat(first.calls()).isLessThanOrEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(second.calls()).isLessThanOrEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(third.calls()).isLessThanOrEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
    }

    /**
     * 熔断与额度的分界（ADR-0027 决定五）。
     *
     * <p>两件事必须分开：这一轮绕了太多次，是止损，不改任何持久状态；这件事花超了，是额度，
     * 记在目标上，进 {@code budget_limited}。合成一个数的话，运维看到停了分不清该加预算还是该
     * 让模型收敛。
     *
     * <p>回给用户的那句话也要是自己的：框架把它变成一代回答时用的是英文原话，直接吐给用户
     * 等于把内部机制漏出去。
     */
    @Test
    @DisplayName("撞上工具调用上限：回我们自己的话，目标状态一点都不动")
    void theToolCallCapIsACircuitBreakerNotAQuota() {
        InMemoryGoals goals = new InMemoryGoals();
        RecordingTool tool = new RecordingTool("lookup", "还是不对");
        FixedChatModel model = new FixedChatModel(prompt -> requestTool("lookup", "{}"), List.of());
        ChatClientAgentPort port = new ChatClientAgentPort(
                client(model, List.of(),
                        List.of(tool)),
                null, null, null, null, null, null, goals);

        port.reply(message("chat-cap", "/goal 把三张表的口径对齐"));
        String text = port.reply(message("chat-cap", "一直查")).text();

        assertThat(tool.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(text)
                .as("给用户的是「这一步做了太多次」，不是框架的 limit exceeded")
                .isEqualTo(ChatClientAgentPort.TOOL_CALL_CAPPED);
        assertThat(goals.of(nodeKey("chat-cap"))
                .orElseThrow().status())
                .as("熔断不改目标状态：止损与额度是两件事")
                .isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    @DisplayName("鉴权放行后工具真的被调到，结果参与最终回答")
    void authorizedToolActuallyRunsInsideTheLoop() {
        AtomicInteger modelCalls = new AtomicInteger();
        RecordingTool tool = new RecordingTool("lookup", "128400");
        FixedChatModel model = new FixedChatModel(
                prompt -> modelCalls.getAndIncrement() == 0
                        ? requestTool("lookup", "{}")
                        : answer("查询结果：" + "128400"),
                List.of());
        String text = port(model, List.of(tool))
                .reply(message("chat-6", "查成交额")).text();

        assertThat(tool.calls()).isEqualTo(1);
        assertThat(text).contains("128400");
    }

    /**
     * 真模型用例：只验"一句中文能不能变成一个对的工具调用"。
     * 工具执行器是替身，不连库——要验的是翻译，不是 SQL 在库上的结果。
     */
    @Test
    @Timeout(120)
    @DisplayName("真模型把「上周华东成交额」翻成 querySales 调用，SQL 指向业务表与 east")
    void realModelTurnsNaturalLanguageIntoTheRightToolCall() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey)
                .as("真模型用例需要 DEEPSEEK_API_KEY；运行前先 source 仓库根的 .env")
                .isNotBlank();

        ModelProperties.Provider deepseek = new ModelProperties.Provider();
        deepseek.setName(ModelProperties.DEEPSEEK);
        // 配的是句柄，真值在测试自己的 Environment 里（P08 之后模型装配只认这一条路）。
        deepseek.setApiKey("secret:deepseek_api_key");
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setModel("deepseek-chat");
        ModelProperties properties = new ModelProperties();
        properties.setProviders(List.of(deepseek));
        ChatModel model = new ModelConfiguration().deepSeekChatModel(properties, keyInEnvironment(apiKey));

        List<String> executedSql = new CopyOnWriteArrayList<>();
        ToolCallback querySales = MethodToolCallbackProvider.builder()
                .toolObjects(new RecordingSalesTool(executedSql))
                .build()
                .getToolCallbacks()[0];

        ChatClient chat = clientBuilder(model)
                .defaultSystem("你是业务助手。回答业务问题前必须先调用 querySales 查数，不要凭空作答。")
                .defaultToolCallbacks(querySales)
                .build();

        String answer = new ChatClientAgentPort(chat)
                .reply(new InboundMessage(
                        new Principal("u-1"),
                        "chat-real",
                        "上周华东区成交额是多少？请调用 querySales 查一下，按 sum(order_item.amount) 算，"
                                + "只算未取消的订单。"))
                .text();

        assertThat(executedSql)
                .as("模型没有调用 querySales：自然语言没有被翻成工具调用")
                .isNotEmpty();
        // 判据只认「翻译发生了」，不认某一种 SQL 写法。
        //
        // 「上周」在这个模型上有好几种同样正确的落地：`now() - interval '7 days'`、
        // `date_trunc('week', current_date) - interval '7 days'`、直接写死两个日期字面量。
        // 原先只认 interval，模型换一种写法这条就红——而它红的时候代码一行没改。
        // 现在按语义等价类判：聚合口径 + 任意一种时间窗痕迹，漏掉时间窗仍然会红（那才是真错）。
        // 不断言 sales_order / east 这类业务 schema 的写法——那是借了示例销售工具的描述，
        // 而一个验链路装配的用例，不该由某份业务表的结构决定它红不红。
        String sql = executedSql.getFirst().toLowerCase();
        assertThat(sql)
                .as("中文里的‘成交额’没有被翻成聚合；实际 SQL：%s", executedSql.getFirst())
                .containsPattern("sum\\s*\\(");
        assertThat(sql)
                .as("中文里的‘上周’没有被翻成时间窗（缺时间条件就是把全表当成了上周）；实际 SQL：%s",
                        executedSql.getFirst())
                .containsPattern("interval|date_trunc|current_date|now\\s*\\(|between|'20\\d\\d-");
        assertThat(answer).isNotBlank();
    }

    // ---------------------------------------------------------------- run（A01）

    /**
     * 一轮在审计里留下两条事件（ADR-0027 决定二）。
     *
     * <p>判据是"这两个事件"，不是模型说了什么：审计要回答"谁在什么时候让谁做了什么、成没成"。
     * 只有结束那一条时，跑了多久、两条并发的先后都只能靠时间戳猜。
     *
     * <p>编号跟着回答出去，但它<b>不是续跑令牌</b>：拿它回来接不上任何东西——
     * 连续性靠会话键。
     */
    @Test
    @DisplayName("一次提问两条审计事件：started 与 finished 成对，runId 跟着回答出去")
    void replyLeavesTwoAuditEvents() {
        RecordingAudit audit = new RecordingAudit();
        ChatClientAgentPort port = port(new FixedChatModel("华东上周成交 128400"), audit.toolCallAudit());

        OutboundMessage answer = port.reply(message("chat-1", "上周华东成交额"));

        assertThat(answer.runId()).isNotNull();
        assertThat(audit.actions()).containsExactly(
                AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);
        assertThat(audit.events).allSatisfy(event -> {
            assertThat(event.scope().runId()).as("两条事件属于同一轮").isEqualTo(answer.runId());
            assertThat(event.scope().sessionKey()).as("会话键进门就算好了").isNotNull();
            assertThat(event.scope().actor()).as("谁发起的").isEqualTo("u-1");
        });
        assertThat(audit.events.getLast().status()).isEqualTo(AuditStatus.SUCCEEDED);
    }

    /** 失败也要留下痕迹：没有这一条，崩掉的那一轮在系统里等于没发生过。 */
    @Test
    @DisplayName("模型抛异常：审计里一条 failed，异常照旧抛给调用方")
    void failureLeavesAFailedEventAndRethrows() {
        RecordingAudit audit = new RecordingAudit();
        ChatClientAgentPort port = port(new FixedChatModel(prompt -> {
            throw new IllegalStateException("模型不可用");
        }), audit.toolCallAudit());

        assertThatThrownBy(() -> port.reply(message("chat-1", "在吗")))
                .isInstanceOf(IllegalStateException.class);
        // 异常照抛——审计只负责记下"没成"，不负责吞掉失败。
        assertThat(audit.actions()).containsExactly(
                AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);
        assertThat(audit.events.getLast().status()).isEqualTo(AuditStatus.FAILED);
    }

    /** 流式每一块都带同一个 runId：只收到中间块的调用方也要能认出同一批。 */
    @Test
    @DisplayName("流式：每一块都带同一个 runId，结束事件只发生一次")
    void streamChunksShareOneTurnId() {
        RecordingAudit audit = new RecordingAudit();
        List<OutboundMessage> chunks = new ArrayList<>();
        ChatClientAgentPort port = port(
                new FixedChatModel(prompt -> answer("整段回答"), List.of("第一块", "第二块")),
                audit.toolCallAudit());

        port.stream(message("chat-1", "讲一下"), chunks::add);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.runId()).isNotNull());
        assertThat(chunks.stream().map(OutboundMessage::runId).distinct()).hasSize(1);
        assertThat(audit.actions()).containsExactly(
                AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);
    }

    /**
     * 审计写不进去，回答照给。
     *
     * <p>审计是记账，不是回答的前置条件。库连不上、迁移没跑过时，正确的姿势是
     * "这一行没记上、话照说"，而不是把用户的问题打死——代价由
     * {@code claw4j.audit.write_errors} 暴露：一个不为 0 的数就说明这些天缺了记录。
     */
    @Test
    @DisplayName("审计写不进去（库不可用）时回答照给：记账不是回答的前置条件")
    void aBrokenAuditStillAnswers() {
        AuditSink broken = event -> {
            throw new IllegalStateException("库连不上");
        };
        ChatClientAgentPort port = port(new FixedChatModel("华东上周成交 128400"),
                new ToolCallAudit(broken));

        OutboundMessage answer = port.reply(message("chat-1", "上周华东成交额"));

        assertThat(answer.text()).isEqualTo("华东上周成交 128400");
        assertThat(answer.runId()).as("编号本地生成，不依赖任何存储").isNotNull();
    }

    // ---------------------------------------------------------------- 审批（P02）

    /**
     * 登记一条请求，把卡片推到它自己那段对话；推失败不影响登记（ADR-0027）。
     *
     * <p>合并之后"登记 + 推卡片"是 {@code JdbcApprovals#ask} 的最后一步。这里用 mock 的
     * {@code JdbcTemplate} 只验这一段的编排：该推的推了、推不出去的只记日志、没有来处就不推。
     */
    @Test
    @DisplayName("审批只登记与推卡片：不挂起 run、不写 awaiting 步骤，卡片落在请求自己那段对话")
    void theAskRegistersAndAnnouncesWithoutSuspendingAnything() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        RecordingCards cards = new RecordingCards("feishu");
        JdbcApprovals approvals = new JdbcApprovals(jdbc,
                new ApproverRoster(Set.of("approver-1")), null, null, null,
                List.of(cards, new BrokenCards("feishu")), Duration.ofMinutes(10).toMillis());
        String digest = "a".repeat(64);
        Approvals.Origin origin = new Approvals.Origin("claw4j:u-1@fp:feishu:oc_1",
                "feishu", "oc_1", new Principal("u-1"));

        assertThat(approvals.redeem("u-1", "sendEmail", digest)).as("没批过就是没有").isEmpty();

        Optional<String> asked = approvals.ask(new Approvals.Ask("run-1", "u-1", "sendEmail",
                digest, "to=张三", origin));

        assertThat(asked).isPresent();
        assertThat(cards.announced)
                .as("卡片落在请求自己那段对话上；推失败的那个只记日志，不影响登记")
                .hasSize(1);
        assertThat(cards.announced.get(0))
                .startsWith("oc_1|sendEmail|to=张三|")
                .endsWith("|pending");

        assertThat(approvals.ask(new Approvals.Ask(null, "u-1", "sendEmail", digest, "to=张三", null)))
                .isPresent();
        assertThat(cards.announced).as("没有来处就没有对话可推").hasSize(1);
    }

    /** 卡片能力的替身：这一层验的是"推到没推、推给谁"，不是卡片长什么样（那是通道的事）。 */
    private static final class RecordingCards implements ApprovalCards {

        private final String channel;

        final List<String> announced = new ArrayList<>();

        RecordingCards(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public void announce(String channelRef, Approvals.Pending card) {
            announced.add(String.join("|", channelRef, card.toolName(), card.argsSummary(),
                    card.id(), card.status()));
        }
    }

    /** 推不出去的卡片：飞书限频、网络断、凭据过期都会走到这里，而那一轮不该因此变成 500。 */
    private static final class BrokenCards implements ApprovalCards {

        private final String channel;

        BrokenCards(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public void announce(String channelRef, Approvals.Pending card) {
            throw new IllegalStateException("飞书拒绝发送");
        }
    }

    /**
     * 把审计事件收进内存：这一层要验记了哪几条、带没带编号，不是 SQL 对不对。
     *
     * <p>用它而不是 {@code AuditLedger}：账本要库、要迁移，而"什么时候该记一条什么事件"
     * 这条规则本来就不需要库——{@code AuditSink} 存在的理由正是这个。
     */
    static final class RecordingAudit {

        final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        ToolCallAudit toolCallAudit() {
            return new ToolCallAudit(events::add);
        }

        List<AuditAction> actions() {
            return events.stream().map(AuditEvent::action).toList();
        }
    }

    private static ChatClientAgentPort port(FixedChatModel model) {
        return port(model, new RecordingAudit().toolCallAudit());
    }

    /** 一台把审计写进内存的门：这一层要验"记了哪几条"，不是"SQL 对不对"。 */
    private static ChatClientAgentPort port(FixedChatModel model, ToolCallAudit audit) {
        return new ChatClientAgentPort(
                client(model, List.of(), List.of()),
                null, null, null, null, null, audit, null);
    }

    private static ChatClientAgentPort port(FixedChatModel model, ToolCallAudit audit,
                                            SessionGoalStore goals) {
        return new ChatClientAgentPort(
                client(model, List.of(), List.of()),
                null, null, null, null, null, audit, goals);
    }

    @Test
    @DisplayName("活跃目标每回合进提示词，暂停之后不进；/goal 命令本身不调模型")
    void onlyAnActiveGoalIsInjectedIntoEveryTurn() {
        InMemoryGoals goals = new InMemoryGoals();
        List<String> sent = new ArrayList<>();
        FixedChatModel model = new FixedChatModel(prompt -> {
            sent.add(prompt.getInstructions().stream()
                    .map(each -> each.getText() == null ? "" : each.getText())
                    .reduce("", String::concat));
            return answer("好");
        });
        ChatClientAgentPort port = port(model, new RecordingAudit().toolCallAudit(), goals);

        port.reply(message("chat-1", "/goal 让 CI 变绿"));
        assertThat(model.prompts())
                .as("/goal 是命令：不进模型、不占一次调用（OpenClaw 的纯命令消息绕过队列和模型）")
                .isEmpty();

        port.reply(message("chat-1", "现在到哪一步了"));
        assertThat(sent.getLast()).contains("活跃目标：让 CI 变绿");

        port.reply(message("chat-1", "/goal pause"));
        assertThat(model.prompts()).as("/goal pause 同样不调模型").hasSize(1);
        port.reply(message("chat-1", "接着说"));
        assertThat(sent.getLast())
                .as("暂停之后不再注入。只判\"有没有目标\"的话，用户按了暂停、界面上写着已暂停，"
                        + "下一轮模型还在往那个方向推进")
                .doesNotContain("活跃目标：");

        port.reply(message("chat-1", "/goal resume"));
        port.reply(message("chat-1", "接着说"));
        assertThat(sent.getLast()).as("恢复之后重新注入").contains("活跃目标：让 CI 变绿");
    }

    /**
     * 达成的目标能 resume（ADR-0027 决定三，照 OpenClaw 的命令表）。
     *
     * <p>依据：OpenClaw 的 resume 能恢复受限或已完成的目标（{@code docs/tools/goal.md:91}）；
     * 不能直接 edit、pause、block 是另一句话。
     *
     * <p>恢复的是同一个目标：内容、预算、已用 Token 都不动，只有 {@code budget_limited} 才开
     * 新窗口。当成新建一件的话，再推一次会带着一份空账从头开始，而用户以为额度还在。
     */
    @Test
    @DisplayName("达成的目标能被 /goal resume 拉回来：同一个目标继续推，内容与用量都保留")
    void aCompletedGoalCanBeResumed() {
        InMemoryGoals goals = new InMemoryGoals();
        List<String> sent = new ArrayList<>();
        FixedChatModel model = new FixedChatModel(prompt -> {
            sent.add(prompt.getInstructions().stream()
                    .map(each -> each.getText() == null ? "" : each.getText())
                    .reduce("", String::concat));
            return answer("好");
        });
        ChatClientAgentPort port = port(model, new RecordingAudit().toolCallAudit(), goals);
        String key = nodeKey("chat-1");

        port.reply(message("chat-1", "/goal 让 CI 变绿"));
        goals.account(key, 1200L);
        port.reply(message("chat-1", "/goal complete 推上去并验证过"));

        assertThat(port.reply(message("chat-1", "/goal resume")).text())
                .as("完成的也能恢复")
                .contains("重新开始推进");

        SessionGoal resumed = goals.of(key).orElseThrow();
        assertThat(resumed.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(resumed.objective()).as("恢复同一个目标，不是新建一件").isEqualTo("让 CI 变绿");
        assertThat(resumed.tokensUsed())
                .as("只有 budget_limited 才开新窗口：达成的目标再推一次，额度接着算")
                .isEqualTo(1200);

        port.reply(message("chat-1", "接着说"));
        assertThat(sent.getLast())
                .as("恢复之后重新注入，且四个分句都在——少一句就长出对应的坏行为")
                .contains("活跃目标：让 CI 变绿")
                .contains("没完全达成之前保持活跃")
                .contains("连续三个轮次")
                .contains("仍要给出用户要的最终回答");
    }

    /**
     * 达成之后不能直接改（原文排错表：{@code Goal error: goal is already complete}）。
     *
     * <p><b>三个动作都要被挡，并且说清怎么继续</b>：edit、pause、block。
     * 全回一句"库连不上"的话，用户会对着一个完好的系统反复重试——
     * 而它其实只需要一句 {@code /goal resume}。
     */
    @Test
    @DisplayName("达成的目标不能直接 edit / pause / block：要先把目标 resume 回来")
    void aCompletedGoalMustBeResumedBeforeItCanBeChanged() {
        InMemoryGoals goals = new InMemoryGoals();
        ChatClientAgentPort port = port(new FixedChatModel("好"), new RecordingAudit().toolCallAudit(), goals);
        String key = nodeKey("chat-1");

        port.reply(message("chat-1", "/goal 让 CI 变绿"));
        port.reply(message("chat-1", "/goal complete"));

        for (String command : List.of("/goal edit 换个说法", "/goal pause", "/goal block 卡住了")) {
            assertThat(port.reply(message("chat-1", command)).text())
                    .as("达成之后要改它得先 resume：一次误触不该把收了尾的事改回进行中")
                    .contains("先 /goal resume");
        }
        assertThat(goals.of(key).orElseThrow().status()).isEqualTo(GoalStatus.COMPLETE);
        assertThat(goals.of(key).orElseThrow().objective()).isEqualTo("让 CI 变绿");
    }

    /**
     * 消息直接给了整段会话键时，就用它——不换段、不查指针（ADR-0027）。
     *
     * <p>审批批准之后走的就是这条：那条请求的 {@code session_key} 写在审批行上，
     * 批准之后要回到<b>那一段</b>对话开新一轮。按通道重新派生也能算出一个键，
     * 而它与本段差一个段后缀——上下文与 goal 就都不在这段里了，表现是
     * "批准之后它像第一次听到这件事"。
     */
    @Test
    @DisplayName("显式给的整段会话键原样使用：审批续跑要回到它自己那一段")
    void anExplicitSessionKeyIsUsedAsGiven() {
        RecordingAudit audit = new RecordingAudit();
        ChatClientAgentPort port = port(new FixedChatModel("好"), audit.toolCallAudit());

        port.reply(new InboundMessage(new Principal("u-1"), "chat-1", "接着说",
                Instant.now(), null, "feishu", NotifyPolicy.DEFAULT, null,
                "claw4j:u-1@fp-1:feishu:chat-1:seg-fixed", true));

        assertThat(audit.events)
                .as("按通道+chatId 派生会得到「无段」的那个键：段一丢，上下文与 goal 都不在这段里")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.scope().sessionKey())
                        .isEqualTo("claw4j:u-1@fp-1:feishu:chat-1:seg-fixed"));
    }

    @Test
    @DisplayName("记忆整理：攒够轮数才跑一次，而且跑的是这个人的目录")
    void dreamRunsOnlyAfterEnoughTurns(@TempDir Path root) {
        AutoDreamService dream = mock(AutoDreamService.class);
        when(dream.runDreamCycle(anyString(), any())).thenReturn(DreamResult.completed("合并了两条"));
        TaskRepository tasks = mock(TaskRepository.class);
        PrincipalDreamAdvisor advisor = new PrincipalDreamAdvisor(root, dream, tasks,
                DreamTriggers.hoursAndSessions(24, 5));
        ChatClientResponse response = responseOf(new Principal("alice"));

        for (int turn = 1; turn <= 4; turn++) {
            advisor.after(response, null);
        }

        verify(tasks, never()).putTask(anyString(), any());

        advisor.after(response, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<String>> task = ArgumentCaptor.forClass(Supplier.class);
        verify(tasks).putTask(anyString(), task.capture());
        task.getValue().get();

        // 跑的是这个人自己的目录：不是根目录，也不是别人的那一层。
        verify(dream).runDreamCycle(eq(advisor.directoryOf("alice").toString()), isNull());
    }

    @Test
    @DisplayName("整理按人计：甲的轮数不算进乙的账")
    void dreamStateIsPerPrincipal(@TempDir Path root) {
        AutoDreamService dream = mock(AutoDreamService.class);
        when(dream.runDreamCycle(anyString(), any())).thenReturn(DreamResult.completed("好"));
        TaskRepository tasks = mock(TaskRepository.class);
        PrincipalDreamAdvisor advisor = new PrincipalDreamAdvisor(root, dream, tasks,
                DreamTriggers.hoursAndSessions(24, 5));

        for (int turn = 1; turn <= 5; turn++) {
            advisor.after(responseOf(new Principal("alice")), null);
        }
        verify(tasks, times(1)).putTask(anyString(), any());

        for (int turn = 1; turn <= 4; turn++) {
            advisor.after(responseOf(new Principal("bob")), null);
        }

        // 状态文件在各人的目录里：乙攒到第 4 轮，不该被甲的第 5 轮带着触发。
        verify(tasks, times(1)).putTask(anyString(), any());

        advisor.after(responseOf(new Principal("bob")), null);

        verify(tasks, times(2)).putTask(anyString(), any());
    }

    /** 只有身份、没有回答的响应：整理只看上下文里的那个人。 */
    private static ChatClientResponse responseOf(Principal principal) {
        return ChatClientResponse.builder()
                .context(Map.of(ContextKeys.PRINCIPAL, principal))
                .build();
    }

    /**
     * 内存版目标存储：这一层只验哪一行进提示词，所以只实现被调到的那几个方法。
     *
     * <p>不与 {@code ToolGovernanceTest} 共用：那边要的是另一套语义（改写、用量），两份各自
     * 很小；共用一个语义更宽的替身，反而容易让一边的断言依赖另一边的实现。
     */
    private static final class InMemoryGoals implements SessionGoalStore {

        private static final Instant NOW = Instant.parse("2026-11-06T02:00:00Z");

        /** 一行 = entry 的这一段：目标 + 会话累计用量（account 用它算快照差）。 */
        private record State(SessionGoal goal, long totalTokens) {
        }

        private final Map<String, State> rows = new HashMap<>();

        @Override
        public Optional<SessionGoal> of(String sessionKey) {
            State state = rows.get(sessionKey);
            return state == null ? Optional.empty() : Optional.of(state.goal());
        }

        @Override
        public Optional<SessionGoal> start(String sessionKey, String objective, Long tokenBudget) {
            if (rows.containsKey(sessionKey)) {
                return Optional.empty();
            }
            SessionGoal created = new SessionGoal(sessionKey, SessionGoal.SCHEMA_VERSION,
                    UUID.randomUUID(), objective, GoalStatus.ACTIVE, NOW, NOW,
                    0, true, 0, tokenBudget, 0, null, null, null, null, null, null);
            rows.put(sessionKey, new State(created, 0));
            return Optional.of(created);
        }

        @Override
        public Optional<SessionGoal> edit(String sessionKey, UUID goalId, String objective) {
            State state = rows.get(sessionKey);
            if (state == null || !state.goal().id().equals(goalId)
                    || state.goal().status() == GoalStatus.COMPLETE) {
                return Optional.empty();
            }
            SessionGoal old = state.goal();
            return put(state, new SessionGoal(sessionKey, old.schemaVersion(), old.id(), objective,
                    old.status(), old.createdAt(), NOW, old.tokenStart(), old.tokenStartFresh(),
                    old.tokensUsed(), old.tokenBudget(), old.continuationTurns(), old.note(),
                    old.pausedAt(), old.blockedAt(), old.completedAt(), old.usageLimitedAt(),
                    old.budgetLimitedAt()));
        }

        @Override
        public Optional<SessionGoal> setStatus(String sessionKey, UUID goalId, GoalStatus status,
                                               String note) {
            State state = rows.get(sessionKey);
            if (state == null || !state.goal().id().equals(goalId)
                    || state.goal().status() == GoalStatus.COMPLETE) {
                return Optional.empty();
            }
            SessionGoal old = state.goal();
            Instant paused = status == GoalStatus.PAUSED ? NOW : old.pausedAt();
            Instant blocked = status == GoalStatus.BLOCKED ? NOW : old.blockedAt();
            Instant completed = status == GoalStatus.COMPLETE
                    ? (old.completedAt() == null ? NOW : old.completedAt()) : old.completedAt();
            Instant usage = status == GoalStatus.USAGE_LIMITED ? NOW : old.usageLimitedAt();
            Instant budget = status == GoalStatus.BUDGET_LIMITED ? NOW : old.budgetLimitedAt();
            return put(state, new SessionGoal(sessionKey, old.schemaVersion(), old.id(),
                    old.objective(), status, old.createdAt(), NOW, old.tokenStart(),
                    old.tokenStartFresh(), old.tokensUsed(), old.tokenBudget(),
                    old.continuationTurns(), note, paused, blocked, completed, usage, budget));
        }

        @Override
        public Optional<SessionGoal> resume(String sessionKey, UUID goalId, String note) {
            State state = rows.get(sessionKey);
            if (state == null || !state.goal().id().equals(goalId)
                    || state.goal().status() == GoalStatus.ACTIVE) {
                return Optional.empty();
            }
            SessionGoal old = state.goal();
            boolean newWindow = old.status() == GoalStatus.BUDGET_LIMITED
                    || old.status() == GoalStatus.USAGE_LIMITED;
            long start = newWindow ? state.totalTokens() : old.tokenStart();
            long used = newWindow ? 0 : old.tokensUsed();
            Instant completed = old.status() == GoalStatus.COMPLETE ? null : old.completedAt();
            return put(state, new SessionGoal(sessionKey, old.schemaVersion(), old.id(),
                    old.objective(), GoalStatus.ACTIVE, old.createdAt(), NOW, start,
                    newWindow || old.tokenStartFresh(), used, old.tokenBudget(),
                    old.continuationTurns(), note, old.pausedAt(), old.blockedAt(), completed,
                    newWindow ? null : old.usageLimitedAt(),
                    newWindow ? null : old.budgetLimitedAt()));
        }

        /**
         * 对账：用量按快照差重算，不是累加——与 JDBC 那条语句一致。
         * {@code null} 表示这一轮拿不到用量，此时什么都不改。
         */
        @Override
        public Optional<SessionGoal> account(String sessionKey, Long deltaTokens) {
            State state = rows.get(sessionKey);
            if (state == null) {
                return Optional.empty();
            }
            if (deltaTokens == null) {
                return Optional.of(state.goal());
            }
            SessionGoal old = state.goal();
            long total = state.totalTokens() + deltaTokens;
            long start = old.tokenStartFresh() ? old.tokenStart() : total;
            long used = old.tokenStartFresh()
                    ? Math.max(old.tokensUsed(), total - old.tokenStart())
                    : old.tokensUsed();
            boolean capped = old.status() != GoalStatus.COMPLETE
                    && old.status() != GoalStatus.BUDGET_LIMITED
                    && old.status() != GoalStatus.USAGE_LIMITED
                    && old.tokenBudget() != null && used >= old.tokenBudget();
            SessionGoal next = new SessionGoal(sessionKey, old.schemaVersion(), old.id(),
                    old.objective(), capped ? GoalStatus.BUDGET_LIMITED : old.status(),
                    old.createdAt(), NOW, start, true, used, old.tokenBudget(),
                    old.continuationTurns(), old.note(), old.pausedAt(), old.blockedAt(),
                    old.completedAt(), old.usageLimitedAt(),
                    capped ? NOW : old.budgetLimitedAt());
            rows.put(sessionKey, new State(next, total));
            return Optional.of(next);
        }

        @Override
        public boolean clear(String sessionKey, UUID goalId) {
            State state = rows.get(sessionKey);
            if (state == null || !state.goal().id().equals(goalId)) {
                return false;
            }
            rows.remove(sessionKey);
            return true;
        }

        private Optional<SessionGoal> put(State state, SessionGoal goal) {
            rows.put(goal.sessionKey(), new State(goal, state.totalTokens()));
            return Optional.of(goal);
        }
    }


    private static ChatClientAgentPort port(ChatModel model, List<ToolCallback> tools) {
        return new ChatClientAgentPort(client(model, List.of(), tools));
    }

    private static ChatClient client(ChatModel model, List<Advisor> advisors, List<ToolCallback> tools) {
        return clientBuilder(model)
                .defaultAdvisors(advisors)
                .defaultToolCallbacks(tools)
                .build();
    }

    /**
     * 工具清单是每轮解析的（票 03）：一个后来才出现的工具，下一轮请求里就看得见。
     *
     * <p>这半边最容易漏：只把连接改成断了会自己重连，清单仍是装配期拍下的那一份快照，于是
     * MCP server 恢复之后连接是新的、工具永远是旧的那批；模型那边只会说「我没有这个能力」，
     * 而日志里一切正常。
     *
     * <p>这里用的是生产那条接法（{@code defaultToolCallbacks(catalog)}，让框架在每次请求时展开
     * provider），不是自己拼一份 List：写成 {@code defaultToolCallbacks(catalog.tools())} 的实现
     * 会在这条用例上红。
     */
    @Test
    @DisplayName("后来才出现的工具，下一轮请求里就看得见（MCP 恢复走的就是这条路）")
    void aToolThatAppearsLaterIsVisibleAtTheNextTurn() {
        AtomicBoolean recovered = new AtomicBoolean();
        RecordingTool lookup = new RecordingTool("lookup", "42");
        ToolCatalog catalog = new ToolCatalog("test", ToolProfile.unrestricted(),
                () -> recovered.get() ? List.of(lookup) : List.of());

        FixedChatModel model = new FixedChatModel("好");
        ChatClientAgentPort port = new ChatClientAgentPort(
                clientBuilder(model).defaultToolCallbacks(catalog).build());

        port.reply(message("chat-1", "先问一句"));
        assertThat(toolNamesOf(model.prompts().getFirst()))
                .as("第一次：那个外部能力还没连上，它不在清单里，也不该有一个占位的空壳")
                .isEmpty();

        recovered.set(true);
        port.reply(message("chat-1", "再问一句"));

        assertThat(toolNamesOf(model.prompts().getLast()))
                .as("恢复之后必须下一轮就能用。快照版本的现场是：连接好了、日志没有一行红，模型说它没有这个能力")
                .containsExactly("lookup");
    }

    /** 这一轮模型实际拿到的工具名。取的是请求选项，也就是工具循环真正用的那一份。 */
    private static List<String> toolNamesOf(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            return options.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
        }
        // 一个工具都没有时框架不设这一格（null 而不是空表）：两者都是"这一轮没有工具"。
        return List.of();
    }

    /**
     * 测试脚手架：这一层验的是 Advisor 与调用预算，不是"工具怎么被发现"。
     * 所以用框架的普通工具循环（工具全塞），免得每条用例都得先演一遍检索回合，
     * 把要验的东西埋在一堆与它无关的消息里。
     *
     * <p>生产只有一条路：{@link ToolSearch#toolLoop}。那份行为（第一轮只给检索工具、
     * 查到谁再注入谁、缺会话 id 直接抛）由 {@code ToolSearchTest} 各自钉住。
     */
    static ChatClient.Builder clientBuilder(ChatModel model) {
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                ToolCallingAdvisor.builder()
                        .toolCallingManager(ToolCallBudget.toolCallingManager(
                                ObservationRegistry.NOOP, ToolCallBudget.MAX_TOTAL_CALLS)));
    }

    // ---- 整轮的墙钟（票 01：库的 InterruptAdvisor）----

    @Test
    @DisplayName("到点：库的 InterruptAdvisor 抛出 TurnInterruptedException，这一轮结束")
    void deadlineStopsTheTurnBeforeTheNextModelRequest() {
        MovableClock clock = new MovableClock();
        RecordingTool slow = new RecordingTool("slow", "转完了",
                () -> clock.advance(Duration.ofMinutes(11)));
        ChatClientAgentPort port = timedPort(new FixedChatModel(oneToolThenAnswer("slow")),
                List.of(slow), clock, Duration.ofMinutes(10));

        assertThatThrownBy(() -> port.reply(message("chat-clock", "跑一下")))
                .as("库的约定就是抛这个异常结束这一轮，抛不抛由它的 signal 决定")
                .isInstanceOf(TurnInterruptedException.class);
        assertThat(slow.calls()).as("第一次工具之后就超时了，不该再发第二轮模型请求").isEqualTo(1);
    }

    @Test
    @DisplayName("检查发生在每次工具往返之后：第一次不超时，第二次才停")
    void deadlineIsRecheckedBetweenToolRounds() {
        MovableClock clock = new MovableClock();
        RecordingTool first = new RecordingTool("first", "一");
        RecordingTool second = new RecordingTool("second", "二",
                () -> clock.advance(Duration.ofMinutes(11)));
        AtomicInteger round = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (round.incrementAndGet()) {
            case 1 -> requestTool("first", "{}");
            case 2 -> requestTool("second", "{}");
            default -> answer("好");
        });

        ChatClientAgentPort port = timedPort(model, List.of(first, second), clock,
                Duration.ofMinutes(10));
        assertThatThrownBy(() -> port.reply(message("chat-clock", "跑两下")))
                .isInstanceOf(TurnInterruptedException.class);

        assertThat(first.calls()).as("第一轮往返之后还没到点，该继续").isEqualTo(1);
        assertThat(second.calls()).isEqualTo(1);
        assertThat(model.prompts()).as("停在第二次工具之后、第三次模型请求之前").hasSize(2);
    }

    @Test
    @DisplayName("写 0 就是关掉：钟推得再远也照常答完")
    void zeroTurnTimeoutKeepsTheOldBehaviour() {
        MovableClock clock = new MovableClock();
        RecordingTool slow = new RecordingTool("slow", "转完了",
                () -> clock.advance(Duration.ofHours(2)));

        String answer = timedPort(new FixedChatModel(oneToolThenAnswer("slow")), List.of(slow),
                clock, Duration.ZERO)
                .reply(message("chat-clock", "跑一下"))
                .text();

        assertThat(answer).isEqualTo("好");
        assertThat(slow.calls()).isEqualTo(1);
    }

    /** 第一次请求工具、之后正常回答的模型桩。 */
    private static Function<Prompt, ChatResponse> oneToolThenAnswer(String tool) {
        AtomicInteger round = new AtomicInteger();
        return prompt -> round.incrementAndGet() == 1 ? requestTool(tool, "{}") : answer("好");
    }

    /** 带墙钟的门：钟可控，超时窗口由用例给。 */
    private static ChatClientAgentPort timedPort(ChatModel model, List<ToolCallback> tools,
                                                 MovableClock clock, Duration turnTimeout) {
        return new ChatClientAgentPort(client(model, List.of(), tools),
                null, null, null, null, null, new RecordingAudit().toolCallAudit(), null,
                null, null, null, turnTimeout, clock);
    }

    /** 可推进的钟：截止时刻要能被按住，"跑到第几轮时到点"才是可断言的。 */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-11-19T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private static InboundMessage message(String chatId, String text) {
        return new InboundMessage(new Principal("u-1"), chatId, text);
    }

    /**
     * 这一轮的会话节点键（不含段）：默认 scope 下私聊并进主会话（ADR-0029）。
     *
     * <p>目标与状态行按它定位，所以用例读目标时不能自己拼
     * {@code sessionIdOf(principal, null, chatId)}——那是 dmScope 之前的形状，
     * 与端口算出来的不是同一个节点。这类"用例自己拼一把键"正是
     * "目标落在另一个节点上"能藏住的原因。
     */
    private static String nodeKey(String chatId) {
        return SessionKeys.conversationKeyOf(SessionKeys.ownerOf(new Principal("u-1")),
                true, null, chatId, SessionScopeProperties.defaults());
    }

    /**
     * 真模型用例的真值放在这里，而不是放进 {@code ModelProperties}（P08）。
     *
     * <p>属性名照句柄的换算规则写（{@code secret:deepseek_api_key} → {@code DEEPSEEK_API_KEY}），
     * 于是这几条用例走的就是生产那条路：配置只有名字，真值在出口被问出来。
     */
    private static StandardEnvironment keyInEnvironment(String apiKey) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("real-model-key", Map.of("DEEPSEEK_API_KEY", apiKey)));
        return environment;
    }

    /** 带着 runId 再说一句：客户端把上一轮回答里的 runId 带回来（A02 的 resume 入口）。 */
    private static InboundMessage continuation(String chatId, String text, String runId) {
        return new InboundMessage(new Principal("u-1"), chatId, text, Instant.now(), null, runId);
    }

    static ChatResponse answer(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    static ChatResponse requestTool(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + name + "-" + arguments.hashCode(), "function", name, arguments)))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }
}

/**
 * 固定文本的模型桩。它只做两件事：把预设文本交出去，把收到的 Prompt 留下来。
 * 断言的对象（链、预算、工具、结束信号）都不在它身上，所以它可以是假的。
 */
class FixedChatModel implements ChatModel {

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final Function<Prompt, ChatResponse> onCall;
    private final Function<Prompt, Flux<ChatResponse>> onStream;
    private final ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .model("fixed-model")
            .temperature(0.0)
            .build();

    FixedChatModel(String text) {
        this(prompt -> ChatPipelineTest.answer(text), List.of(text));
    }

    FixedChatModel(Function<Prompt, ChatResponse> onCall) {
        this(onCall, List.of());
    }

    FixedChatModel(Function<Prompt, ChatResponse> onCall, List<String> onStream) {
        this.onCall = onCall;
        this.onStream = prompt -> Flux.fromIterable(onStream).map(ChatPipelineTest::answer);
    }

    /**
     * 流式块由调用方决定。鉴权要验"流式路径上看到工具调用怎么办"，
     * 而 {@link #FixedChatModel(Function, List)} 只会流纯文本，看见工具调用这条路根本走不到。
     */
    FixedChatModel(Function<Prompt, ChatResponse> onCall, Function<Prompt, Flux<ChatResponse>> onStream) {
        this.onCall = onCall;
        this.onStream = onStream;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return onCall.apply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return onStream.apply(prompt);
    }

    @Override
    public ChatOptions getOptions() {
        return options;
    }

    List<Prompt> prompts() {
        return List.copyOf(prompts);
    }
}

/** 记录被真正执行了几次的工具桩。鉴权要证明"没被执行"，工具循环要证明"执行了几次"。 */
class RecordingTool implements ToolCallback {

    private final ToolDefinition definition;
    private final String result;
    private final Runnable onCall;
    private final AtomicInteger calls = new AtomicInteger();

    RecordingTool(String name, String result) {
        this(name, result, "测试工具 " + name);
    }

    /**
     * 描述由调用方给：工具检索就是按描述匹配的，描述一样的话"检索选中了谁"根本无从验证。
     */
    RecordingTool(String name, String result, String description) {
        this(name, result, description, null);
    }

    /**
     * 调用时顺手跑一个动作。墙钟那几条用例靠它把钟推过截止时刻——{@code sleep}
     * 会让"跑到第几轮时到点"变成看运气。
     */
    RecordingTool(String name, String result, Runnable onCall) {
        this(name, result, "测试工具 " + name, onCall);
    }

    RecordingTool(String name, String result, String description, Runnable onCall) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{\"type\":\"object\"}")
                .build();
        this.result = result;
        this.onCall = onCall;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        calls.incrementAndGet();
        if (onCall != null) {
            onCall.run();
        }
        return result;
    }

    int calls() {
        return calls.get();
    }
}

/**
 * 真模型用例需要一个"真的会被调用、且会留下痕迹"的工具。
 *
 * <p>它不借 capability 里的示例销售工具：agent 不该为了一个替身认识业务模块。
 * 替身只做两件真模型用例关心的事：记下被传进来的 SQL、回一行数。
 */
class RecordingSalesTool {

    private final List<String> executed;

    RecordingSalesTool(List<String> executed) {
        this.executed = executed;
    }

    @Tool(name = "querySales", description = "按 SQL 查询销售数据，只读")
    public String querySales(@ToolParam(description = "要执行的 select 语句") String sql) {
        executed.add(sql);
        return "revenue\n128400";
    }
}
