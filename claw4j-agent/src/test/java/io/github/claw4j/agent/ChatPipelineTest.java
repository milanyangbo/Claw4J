package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.approval.RunApprovalGate;
import io.github.claw4j.agent.audit.AuditAction;
import io.github.claw4j.agent.audit.AuditEvent;
import io.github.claw4j.agent.audit.AuditSink;
import io.github.claw4j.agent.audit.AuditStatus;
import io.github.claw4j.agent.audit.ToolCallAudit;
import io.github.claw4j.agent.model.ModelConfiguration;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.goal.GoalStatus;
import io.github.claw4j.agent.goal.SessionGoal;
import io.github.claw4j.agent.goal.SessionGoalStore;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.ApprovalCards;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.port.ApprovalLedger;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对话主链路：入站消息 → 脱敏 → Advisor 链 → 工具循环 → 出站。
 *
 * <p>模型是桩：这条链路要验的是"我们有没有把模型、工具、结束信号接对"，
 * 不是模型聪不聪明。模型选工具那条另有一条真模型用例。
 */
class ChatPipelineTest {

    private static final ToolPermissionRegistry READS_EVERYTHING =
            ToolPermissionRegistry.of(Map.of("lookup", ToolPermission.read("sales_order")));

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

        ToolPermissionRegistry registry = ToolPermissionRegistry.of(Map.of(
                "first", ToolPermission.read("sales_order"),
                "second", ToolPermission.read("sales_order"),
                "third", ToolPermission.read("sales_order")));

        port(model, List.of(first, second, third), registry).reply(message("chat-5", "轮着查"));

        int executed = first.calls() + second.calls() + third.calls();
        assertThat(executed).isEqualTo(ToolCallBudget.MAX_TOTAL_CALLS);
        assertThat(first.calls()).isLessThanOrEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(second.calls()).isLessThanOrEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(third.calls()).isLessThanOrEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
    }

    /**
     * 熔断与额度的分界（ADR-0027 决定五）。
     *
     * <p>两件事必须分开：<b>这一轮绕了太多次</b>（止损，不改任何持久状态）与
     * <b>这件事花超了</b>（额度，在目标上，进 {@code budget_limited}）。
     * 合成一个数的话，运维看到"停了"分不清该加预算还是该让模型收敛。
     *
     * <p>回给用户的那句话也要是我们自己的：框架把它变成一代回答时用的是英文原话，
     * 直接吐给用户等于把内部机制漏出去。
     */
    @Test
    @DisplayName("撞上工具调用上限：回我们自己的话，目标状态一点都不动")
    void theToolCallCapIsACircuitBreakerNotAQuota() {
        InMemoryGoals goals = new InMemoryGoals();
        RecordingTool tool = new RecordingTool("lookup", "还是不对");
        FixedChatModel model = new FixedChatModel(prompt -> requestTool("lookup", "{}"), List.of());
        ChatClientAgentPort port = new ChatClientAgentPort(
                client(model, List.of(new AuthorizationAdvisor(List.of(READS_EVERYTHING))),
                        List.of(tool)),
                null, null, null, null, null, null, goals);

        port.reply(message("chat-cap", "/goal 把三张表的口径对齐"));
        String text = port.reply(message("chat-cap", "一直查")).text();

        assertThat(tool.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(text)
                .as("给用户的是「这一步做了太多次」，不是框架的 limit exceeded")
                .isEqualTo(ChatClientAgentPort.TOOL_CALL_CAPPED);
        assertThat(goals.of(SessionKeys.sessionIdOf(Principal.readOnly("u-1"), null, "chat-cap"))
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
        String text = port(model, List.of(tool), READS_EVERYTHING)
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

        ModelProperties properties = new ModelProperties();
        // 配的是句柄，真值在测试自己的 Environment 里（P08 之后模型装配只认这一条路）。
        properties.getDeepseek().setApiKey("secret:deepseek_api_key");
        properties.getDeepseek().setBaseUrl("https://api.deepseek.com");
        properties.getDeepseek().setModel("deepseek-chat");
        ChatModel model = new ModelConfiguration().deepSeekChatModel(properties, keyInEnvironment(apiKey));

        List<String> executedSql = new CopyOnWriteArrayList<>();
        ToolCallback querySales = MethodToolCallbackProvider.builder()
                .toolObjects(new RecordingSalesTool(executedSql))
                .build()
                .getToolCallbacks()[0];

        ChatClient chat = clientBuilder(model)
                .defaultSystem("你是业务助手。回答业务问题前必须先调用 querySales 查数，不要凭空作答。")
                .defaultAdvisors(new AuthorizationAdvisor(List.of(
                        ToolPermissionRegistry.of(Map.of("querySales",
                                ToolPermission.read("sales_order", "order_item", "customer", "region"))))))
                .defaultToolCallbacks(querySales)
                .build();

        String answer = new ChatClientAgentPort(chat)
                .reply(new InboundMessage(
                        Principal.of("u-real", "admin", Set.of("*"), Set.of("*")),
                        "chat-real",
                        "上周华东区成交额是多少？请调用 querySales 查一下，按 sum(order_item.amount) 算，"
                                + "只算未取消的订单。"))
                .text();

        assertThat(executedSql)
                .as("模型没有调用 querySales：自然语言没有被翻成工具调用")
                .isNotEmpty();
        // 只断言"翻译发生了"：聚合函数与时间窗都从这句中文里来。
        // 以前这里还断言 sales_order / east 这些**业务 schema 的写法**，那是借了示例销售工具的描述，
        // 而示例已经搬去独立 jar —— 一个验链路装配的用例，不该由某份业务表的结构决定它红不红。
        assertThat(executedSql.getFirst().toLowerCase())
                .as("中文里的‘成交额’没有被翻成聚合、‘上周’没有被翻成时间窗")
                .contains("sum(", "interval");
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
     * 连续性靠会话键。这一条以前叫"开一个 run 并收尾"，而 run 已经不存了。
     */
    @Test
    @DisplayName("一次提问两条审计事件：started 与 finished 成对，turnId 跟着回答出去")
    void replyLeavesTwoAuditEvents() {
        RecordingAudit audit = new RecordingAudit();
        ChatClientAgentPort port = port(new FixedChatModel("华东上周成交 128400"), audit.toolCallAudit());

        OutboundMessage answer = port.reply(message("chat-1", "上周华东成交额"));

        assertThat(answer.turnId()).isNotNull();
        assertThat(audit.actions()).containsExactly(
                AuditAction.AGENT_RUN_STARTED, AuditAction.AGENT_RUN_FINISHED);
        assertThat(audit.events).allSatisfy(event -> {
            assertThat(event.scope().turnId()).as("两条事件属于同一轮").isEqualTo(answer.turnId());
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

    /** 流式每一块都带同一个 turnId：只收到中间块的调用方也要能认出同一批。 */
    @Test
    @DisplayName("流式：每一块都带同一个 turnId，结束事件只发生一次")
    void streamChunksShareOneTurnId() {
        RecordingAudit audit = new RecordingAudit();
        List<OutboundMessage> chunks = new ArrayList<>();
        ChatClientAgentPort port = port(
                new FixedChatModel(prompt -> answer("整段回答"), List.of("第一块", "第二块")),
                audit.toolCallAudit());

        port.stream(message("chat-1", "讲一下"), chunks::add);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.turnId()).isNotNull());
        assertThat(chunks.stream().map(OutboundMessage::turnId).distinct()).hasSize(1);
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
        assertThat(answer.turnId()).as("编号本地生成，不依赖任何存储").isNotNull();
    }

    // ---------------------------------------------------------------- 审批门（P02）

    /**
     * 审批门只做两件事：登记一条请求，把卡片推到它自己那段对话（ADR-0027）。
     *
     * <p><b>它不再挂起 run、也不再写一条 awaiting 步骤</b>：那一轮以 done 收尾
     * （它回给用户的是"我需要你批准"），而"等审批"不是一种能跨请求的状态。
     * 这里同时钉住"推了"（带编号与摘要、按通道匹配）与"推失败不影响登记"。
     */
    @Test
    @DisplayName("审批门只登记与推卡片：不挂起 run、不写 awaiting 步骤，卡片落在请求自己那段对话")
    void theGateRegistersAndAnnouncesWithoutSuspendingAnything() {
        FakeLedger ledger = new FakeLedger("ap-1");
        RecordingCards cards = new RecordingCards("feishu");
        RunApprovalGate gate = new RunApprovalGate(ledger, List.of(cards, new BrokenCards("feishu")));
        String digest = "a".repeat(64);
        ApprovalLedger.Origin origin = new ApprovalLedger.Origin("claw4j:u-1@fp:feishu:oc_1",
                "feishu", "oc_1", Principal.readOnly("u-1"));

        assertThat(gate.redeem("u-1", "sendEmail", digest)).as("没批过就是没有").isEmpty();

        Optional<String> asked = gate.ask(new ApprovalLedger.Ask("run-1", "u-1", "sendEmail",
                digest, "to=张三", origin));

        assertThat(asked).contains("ap-1");
        assertThat(cards.announced)
                .as("卡片落在请求自己那段对话上；推失败的那个只记日志，不影响登记")
                .containsExactly("oc_1|sendEmail|to=张三|ap-1|pending");

        assertThat(gate.ask(new ApprovalLedger.Ask(null, "u-1", "sendEmail", digest, "to=张三", null)))
                .contains("ap-1");
        assertThat(cards.announced).as("没有来处就没有对话可推").hasSize(1);
    }
    /** 没有 run 存储时 runId 只是个没人认领的提示：回答照旧，不报错。 */
    /**
     * 把审计事件收进内存：这一层要验"记了哪几条、带没带编号"，不是"SQL 对不对"。
     *
     * <p>用它而不是 {@code AuditLedger}：账本要库、要迁移，而"什么时候该记一条什么事件"
     * 这条规则本来就不需要库——{@code AuditSink} 存在的理由正是这个。
     */
    static final class RecordingAudit {

        final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        ToolCallAudit toolCallAudit() {
            return new ToolCallAudit(event -> {
                events.add(event);
                return Optional.of((long) events.size());
            });
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
                client(model, List.of(new AuthorizationAdvisor(List.of(READS_EVERYTHING))), List.of()),
                null, null, null, null, null, audit, null);
    }

    private static ChatClientAgentPort port(FixedChatModel model, ToolCallAudit audit,
                                            SessionGoalStore goals) {
        return new ChatClientAgentPort(
                client(model, List.of(new AuthorizationAdvisor(List.of(READS_EVERYTHING))), List.of()),
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
     * <p><b>这一条此前是反的</b>：我把"达成之后不能直接 edit / pause / block"读成了
     * "不能恢复"。原文两处写得很清楚——命令表里 resume 恢复"已暂停、已阻塞、受限<b>或已完成</b>
     * 的目标"；状态那一节说 {@code complete}"保持达成，直到你显式地用 {@code /goal resume}
     * 再次推进同一个目标"。两句挨着，我引了后一句却按前一句的意思实现。
     *
     * <p><b>恢复的是同一个目标</b>：内容、预算、已用 Token 都不动（只有 {@code budget_limited}
     * 才开新窗口）。当成新建一件的话，"再推一次"会带着一份空账从头开始，
     * 而用户以为额度还在。
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
        String key = SessionKeys.sessionIdOf(Principal.readOnly("u-1"), null, "chat-1");

        port.reply(message("chat-1", "/goal 让 CI 变绿"));
        goals.addTokens(key, 1200);
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
        String key = SessionKeys.sessionIdOf(Principal.readOnly("u-1"), null, "chat-1");

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

        port.reply(new InboundMessage(Principal.readOnly("u-1"), "chat-1", "接着说",
                Instant.now(), null, "feishu", NotifyPolicy.DEFAULT, null,
                "claw4j:u-1@fp-1:feishu:chat-1:seg-fixed"));

        assertThat(audit.events)
                .as("按通道+chatId 派生会得到「无段」的那个键：段一丢，上下文与 goal 都不在这段里")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.scope().sessionKey())
                        .isEqualTo("claw4j:u-1@fp-1:feishu:chat-1:seg-fixed"));
    }

    /**
     * 内存版目标存储：这一层只验"哪一行进提示词"，所以只实现被调到的那几个方法。
     *
     * <p><b>为什么不共用一份</b>：{@code claw4j-agent} 的测试文件位已经用满 10 个
     * （门禁按 {@code src/test} 下的 {@code *.java} 数，辅助类也占位），
     * 而 {@code ToolGovernanceTest} 需要的是另一套语义（改写、用量）。两份各自很小，
     * 共用要在两处之间传一个"语义更宽的替身"，反而更容易让一边的断言依赖另一边的实现。
     */
    private static final class InMemoryGoals implements SessionGoalStore {

        private static final Instant NOW = Instant.parse("2026-11-06T02:00:00Z");

        private final Map<String, SessionGoal> rows = new HashMap<>();

        @Override
        public Optional<SessionGoal> of(String sessionKey) {
            return Optional.ofNullable(rows.get(sessionKey));
        }

        @Override
        public Optional<SessionGoal> start(String sessionKey, String objective, Long tokenBudget) {
            if (rows.containsKey(sessionKey)) {
                return Optional.empty();
            }
            return Optional.of(put(new SessionGoal(sessionKey, GoalStatus.ACTIVE, objective, null,
                    tokenBudget, 0, 1, NOW, NOW)));
        }

        /** 改写：状态与用量不动；达成之后不能改（与 JDBC 的 {@code status <> 'complete'} 同规则）。 */
        @Override
        public Optional<SessionGoal> edit(String sessionKey, String objective) {
            SessionGoal current = rows.get(sessionKey);
            if (current == null || current.status() == GoalStatus.COMPLETE) {
                return Optional.empty();
            }
            return Optional.of(put(new SessionGoal(sessionKey, current.status(), objective,
                    current.note(), current.tokenBudget(), current.tokensUsed(),
                    current.revision() + 1, current.createdAt(), NOW)));
        }

        @Override
        public Optional<SessionGoal> setStatus(String sessionKey, GoalStatus status, String note) {
            SessionGoal current = rows.get(sessionKey);
            if (current == null || current.status() == GoalStatus.COMPLETE) {
                return Optional.empty();
            }
            return Optional.of(put(new SessionGoal(sessionKey, status, current.objective(), note,
                    current.tokenBudget(), current.tokensUsed(), current.revision() + 1,
                    current.createdAt(), NOW)));
        }

        /**
         * 记账，并在撞上预算时进 {@code budget_limited}——与 {@code JdbcSessionGoalStore}
         * 的 {@code case when ... then 'budget_limited'} 同一条规则。
         * 这里重写一遍而不是跳过：主链路那份测试要断言"这一轮之后提示词里没有目标了"。
         */
        @Override
        public Optional<SessionGoal> addTokens(String sessionKey, long tokens) {
            SessionGoal current = rows.get(sessionKey);
            if (current == null) {
                return Optional.empty();
            }
            long used = current.tokensUsed() + tokens;
            boolean capped = current.status() != GoalStatus.COMPLETE
                    && current.status() != GoalStatus.BUDGET_LIMITED
                    && current.tokenBudget() != null && used >= current.tokenBudget();
            return Optional.of(put(new SessionGoal(sessionKey,
                    capped ? GoalStatus.BUDGET_LIMITED : current.status(),
                    current.objective(), current.note(), current.tokenBudget(), used,
                    current.revision() + (capped ? 1 : 0), current.createdAt(), NOW)));
        }

        /**
         * 恢复：状态回活跃；**只有 {@code budget_limited} 才清零用量**，与 JDBC 那条语句一致。
         *
         * <p>已达成的不在这里挡——完成的也能 resume（OpenClaw 的命令表把 {@code completed}
         * 列在 resume 的作用对象里）。挡它的是 edit / pause / block（见 {@code setStatus}）。
         */
        @Override
        public Optional<SessionGoal> resume(String sessionKey, String note) {
            SessionGoal current = rows.get(sessionKey);
            if (current == null || current.status() == GoalStatus.ACTIVE) {
                return Optional.empty();
            }
            long used = current.status() == GoalStatus.BUDGET_LIMITED ? 0L : current.tokensUsed();
            return Optional.of(put(new SessionGoal(sessionKey, GoalStatus.ACTIVE, current.objective(),
                    note, current.tokenBudget(), used, current.revision() + 1,
                    current.createdAt(), NOW)));
        }

        @Override
        public boolean clear(String sessionKey) {
            return rows.remove(sessionKey) != null;
        }

        private SessionGoal put(SessionGoal goal) {
            rows.put(goal.sessionKey(), goal);
            return goal;
        }
    }


    /**
     * 审批账本的内存替身：这一层验的是"挂不挂起"，不是 SQL 里那条
     * {@code update ... for update skip locked}（那是 {@code RunSchemaSqlTest} 的事）。
     * 所以它只做两件事：登记时给个编号，兑现时永远说"没有"。
     */
    private static final class FakeLedger implements ApprovalLedger {

        private final String id;

        FakeLedger(String id) {
            this.id = id;
        }

        @Override
        public Optional<Grant> redeem(String owner, String toolName, String argsDigest) {
            return Optional.empty();
        }

        @Override
        public Optional<String> ask(Ask ask) {
            return Optional.of(id);
        }
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
        public void announce(String channelRef, ApprovalDecider.Pending card) {
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
        public void announce(String channelRef, ApprovalDecider.Pending card) {
            throw new IllegalStateException("飞书拒绝发送");
        }
    }

    private static ChatClientAgentPort port(ChatModel model, List<ToolCallback> tools) {
        return port(model, tools, READS_EVERYTHING);
    }

    private static ChatClientAgentPort port(ChatModel model,
                                            List<ToolCallback> tools,
                                            ToolPermissionRegistry registry) {
        return new ChatClientAgentPort(client(model, List.of(new AuthorizationAdvisor(List.of(registry))), tools));
    }

    private static ChatClient client(ChatModel model, List<Advisor> advisors, List<ToolCallback> tools) {
        return clientBuilder(model)
                .defaultAdvisors(advisors)
                .defaultToolCallbacks(tools)
                .build();
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

    private static InboundMessage message(String chatId, String text) {
        return new InboundMessage(Principal.readOnly("u-1"), chatId, text);
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
        return new InboundMessage(Principal.readOnly("u-1"), chatId, text, Instant.now(), null, runId);
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
    private final AtomicInteger calls = new AtomicInteger();

    RecordingTool(String name, String result) {
        this(name, result, "测试工具 " + name);
    }

    /**
     * 描述由调用方给：工具检索就是按描述匹配的，描述一样的话"检索选中了谁"根本无从验证。
     */
    RecordingTool(String name, String result, String description) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{\"type\":\"object\"}")
                .build();
        this.result = result;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        calls.incrementAndGet();
        return result;
    }

    int calls() {
        return calls.get();
    }
}

/**
 * 真模型用例需要一个"真的会被调用、且会留下痕迹"的工具。
 *
 * <p>它以前借的是 capability 里的示例销售工具——那条依赖是错的：agent 不该为了一个替身
 * 认识业务模块（示例业务搬去独立 jar 之后，这里编译不过才暴露出来）。
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
