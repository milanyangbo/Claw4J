package io.github.claw4j.agent;

import io.github.claw4j.agent.turn.ChatClientAgentPort;
import io.github.claw4j.agent.turn.ToolCallBudget;
import io.github.claw4j.agent.approval.ElevatedProperties;
import io.github.claw4j.agent.approval.Elevations;
import io.github.claw4j.agent.approval.JdbcApprovals;
import io.github.claw4j.agent.audit.AuditAction;
import io.github.claw4j.agent.audit.AuditErrorCode;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditSink;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.model.config.ModelConfiguration;
import io.github.claw4j.model.config.ModelProperties;
import io.github.claw4j.agent.channel.DeliveryTarget;
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
     * 撞上上限时回给用户的那句话必须是我们自己的：框架把它变成一代回答时用的是英文原话，
     * 直接吐给用户等于把内部机制漏出去。
     */
    @Test
    @DisplayName("撞上工具调用上限：回我们自己的话，不外泄框架原文")
    void theToolCallCapAnswersWithOurOwnWords() {
        RecordingTool tool = new RecordingTool("lookup", "还是不对");
        FixedChatModel model = new FixedChatModel(prompt -> requestTool("lookup", "{}"), List.of());
        ChatClientAgentPort port = ChatClientAgentPort.builder(client(model, List.of(),
                        List.of(tool)))
                .build();

        String text = port.reply(message("chat-cap", "一直查")).text();

        assertThat(tool.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(text)
                .as("给用户的是「这一步做了太多次」，不是框架的 limit exceeded")
                .isEqualTo(ChatClientAgentPort.TOOL_CALL_CAPPED);
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
        deepseek.setApi(ModelProperties.API_DEEPSEEK);
        // 配的是句柄，真值在测试自己的 Environment 里（P08 之后模型装配只认这一条路）。
        deepseek.setApiKeys(List.of("secret:deepseek_api_key"));
        deepseek.setBaseUrl("https://api.deepseek.com");
        io.github.claw4j.model.catalog.ModelRow row = new io.github.claw4j.model.catalog.ModelRow();
        row.setId("deepseek-chat");
        row.setContextWindow(65536);
        deepseek.setModels(List.of(row));
        ModelProperties properties = new ModelProperties();
        properties.setProviders(Map.of("deepseek", deepseek));
        ChatModel model = new ModelConfiguration()
                .providerChatModels(properties, keyInEnvironment(apiKey),
                        io.github.claw4j.model.failover.RetryBudget.defaults())
                .byRef().get(new io.github.claw4j.model.catalog.ModelRef("deepseek", "deepseek-chat"));

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
    @DisplayName("一次提问两条审计事件：started 与 finished 成对，executionId 跟着回答出去")
    void replyLeavesTwoAuditEvents() {
        RecordingAudit audit = new RecordingAudit();
        ChatClientAgentPort port = port(new FixedChatModel("华东上周成交 128400"), audit.toolCallAudit());

        OutboundMessage answer = port.reply(message("chat-1", "上周华东成交额"));

        assertThat(answer.executionId()).isNotNull();
        assertThat(audit.actions()).containsExactly(
                AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);
        assertThat(audit.events).allSatisfy(event -> {
            assertThat(event.scope().executionId()).as("两条事件属于同一轮").isEqualTo(answer.executionId());
            assertThat(event.scope().sessionKey()).as("会话键进门就算好了").isNotNull();
            assertThat(event.scope().requestedBy()).as("谁发起的").isEqualTo("u-1");
        });
        assertThat(audit.events.getLast().status()).isEqualTo(AuditStatus.SUCCEEDED);

        // 审计写不进去，回答照给（原「aBrokenAuditStillAnswers」，同一件事的另一半）：
        // 合并是因为两者都是"一轮的记账"，分开只是各准备一次模型桩。
        // 库连不上、迁移没跑过时，正确的姿势是"这一行没记上、话照说"，而不是把问题打死——
        // 代价由 claw4j.audit.write_errors 暴露：一个不为 0 的数就说明这些天缺了记录。
        AuditSink broken = event -> {
            throw new IllegalStateException("库连不上");
        };
        ChatClientAgentPort brokenPort = port(new FixedChatModel("华东上周成交 128400"),
                new ToolCallAudit(broken));
        OutboundMessage stillAnswered = brokenPort.reply(message("chat-1", "上周华东成交额"));
        assertThat(stillAnswered.text()).isEqualTo("华东上周成交 128400");
        assertThat(stillAnswered.executionId()).as("编号本地生成，不依赖任何存储").isNotNull();
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

    /** 流式每一块都带同一个 executionId：只收到中间块的调用方也要能认出同一批。 */
    @Test
    @DisplayName("流式：每一块都带同一个 executionId，结束事件只发生一次")
    void streamChunksShareOneTurnId() {
        RecordingAudit audit = new RecordingAudit();
        List<OutboundMessage> chunks = new ArrayList<>();
        ChatClientAgentPort port = port(
                new FixedChatModel(prompt -> answer("整段回答"), List.of("第一块", "第二块")),
                audit.toolCallAudit());

        port.stream(message("chat-1", "讲一下"), chunks::add);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.executionId()).isNotNull());
        assertThat(chunks.stream().map(OutboundMessage::executionId).distinct()).hasSize(1);
        assertThat(audit.actions()).containsExactly(
                AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);

        // 模型一块都不吐时也要发结束信号（原「emptyStreamStillEnds」）：通道不能靠超时猜「说完了」。
        // 与上面同一条流式链路，只是模型没有内容——合并是因为两者共用一个桩，拆开只多一次准备。
        List<OutboundMessage> empty = new ArrayList<>();
        port(new FixedChatModel(prompt -> answer(""), List.of()))
                .stream(message("chat-2", "在吗"), empty::add);
        assertThat(empty).hasSize(1);
        assertThat(empty.getFirst().finalChunk()).isTrue();
        assertThat(empty.getFirst().text()).isEmpty();
    }

    /**
     * 客户端断开时记 {@code cancelled}，不是 {@code failed}（ADR-0037 票 01）。
     *
     * <p>真实的断流长这样：SSE 那一头没了，写回客户端的那一下抛 {@code IOException}（broken pipe），
     * 异常从 {@code onChunk} 冒回来、穿过 {@code blockLast()}。以前它被一律记成
     * {@code failed / run_failed}，于是「这轮是被谁掐掉的」在审计里问不出来——而那正是客户说
     * 「聊到一半就没反应了」时第一个要查的。
     */
    @Test
    @DisplayName("客户端断开：审计里是 cancelled，不是 failed")
    void cancelledStreamIsAuditedAsCancelled() {
        RecordingAudit audit = new RecordingAudit();
        ChatClientAgentPort port = port(
                new FixedChatModel(prompt -> answer("整段"), List.of("第一块", "第二块")),
                audit.toolCallAudit());

        assertThatThrownBy(() -> port.stream(message("chat-1", "讲一下"), chunk -> {
            throw new java.io.UncheckedIOException(new IOException("Broken pipe"));
        })).isInstanceOf(RuntimeException.class);

        assertThat(audit.actions()).containsExactly(
                AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);
        assertThat(audit.events.getLast().status()).isEqualTo(AuditStatus.CANCELLED);
        assertThat(audit.events.getLast().errorCode()).isEqualTo(AuditErrorCode.RUN_CANCELLED);
    }

    /**
     * 超时记 {@code timed_out}：与「失败」「取消」分开。
     *
     * <p>判据只看异常链：上游是 {@code TimeoutException} 还是它包着的运行时异常，都算超时。
     * 合成一档的话，「模型太慢」和「模型挂了」在审计里长得一样，排查要先花半天分这两件事。
     */
    @Test
    @DisplayName("流超时：审计里是 timed_out，与 failed 分开")
    void timedOutStreamIsAuditedAsTimedOut() {
        RecordingAudit audit = new RecordingAudit();
        ChatClientAgentPort port = port(
                new FixedChatModel(prompt -> answer("整段"),
                        prompt -> Flux.error(new java.util.concurrent.TimeoutException("模型没在时限内返回"))),
                audit.toolCallAudit());

        assertThatThrownBy(() -> port.stream(message("chat-1", "讲一下"), chunk -> { }))
                .isInstanceOf(RuntimeException.class);

        assertThat(audit.events.getLast().status()).isEqualTo(AuditStatus.TIMED_OUT);
        assertThat(audit.events.getLast().errorCode()).isEqualTo(AuditErrorCode.RUN_TIMED_OUT);
    }

    /**
     * 命令在两条入口上必须一致：{@code /elevated} 与 {@code /new}、{@code /goal} 同层，
     * 认出来就当场办、不调模型。
     *
     * <p>这条钉的是一个真实的不对称：拆分之前同一套判断在 {@code reply} 与 {@code stream} 里各写
     * 一遍，而流式那一份漏了 {@code /elevated}——用户走 SSE 发这句话，它被当成普通问题送给模型，
     * 模型只能猜。判断现在只有 {@code SlashCommands} 一处，这条用例守的是「两个入口同一条结论」。
     */
    @Test
    @DisplayName("流式入口上的命令也当场办：/elevated 不会被当成问题送给模型")
    void commandsOnTheStreamPathDoNotReachTheModel() {
        FixedChatModel model = new FixedChatModel(prompt -> answer("不该有人问我"));
        Elevations elevations = new Elevations(
                new ElevatedProperties(true, List.of("alice"), null), null);
        ChatClientAgentPort port = ChatClientAgentPort.builder(clientBuilder(model).build())
                .elevations(elevations)
                .build();
        List<OutboundMessage> chunks = new ArrayList<>();

        port.stream(new InboundMessage(new Principal("alice"), "chat-7", "/elevated full"), chunks::add);

        assertThat(model.prompts())
                .as("命令不进模型：它连一次请求都不该发出去")
                .isEmpty();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().text()).contains("跳过审批");
        assertThat(chunks.getFirst().finalChunk())
                .as("正文那一帧不能带 finalChunk：通道看到它就只发 done、正文直接丢掉")
                .isFalse();
        assertThat(chunks.getLast().text()).as("最后一帧空文本是这一路结束的约定").isEmpty();
        assertThat(chunks.getLast().finalChunk())
                .as("结束信号恰好一次，而且只落在最后一帧")
                .isTrue();

        // 当场办的意思是状态真的改了：再走一次流式问「现在是什么档」，回执要读到 full。
        List<OutboundMessage> again = new ArrayList<>();
        port.stream(new InboundMessage(new Principal("alice"), "chat-7", "/elevated"), again::add);
        assertThat(again.getFirst().text()).contains("full");
        assertThat(model.prompts()).as("两条命令都不该碰模型").isEmpty();
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
        return ChatClientAgentPort.builder(client(model, List.of(), List.of()))
                .audit(audit)
                .build();
    }

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
                // 两把会话键分开之后（ADR-0037 第三批），"显式给的那一段"落在 session_id 上；
                // session_key 是不含段的那个键。原来这里断言的是 sessionKey —— 那时两者被合成了一把。
                .allSatisfy(event -> assertThat(event.scope().sessionId())
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
        return ChatClientAgentPort.builder(client(model, List.of(), tools))
                .audit(new RecordingAudit().toolCallAudit())
                .turnTimeout(turnTimeout)
                .clock(clock)
                .build();
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

    /** 带着 executionId 再说一句：客户端把上一轮回答里的 executionId 带回来（A02 的 resume 入口）。 */
    private static InboundMessage continuation(String chatId, String text, String executionId) {
        return new InboundMessage(new Principal("u-1"), chatId, text, Instant.now(), null, executionId);
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
