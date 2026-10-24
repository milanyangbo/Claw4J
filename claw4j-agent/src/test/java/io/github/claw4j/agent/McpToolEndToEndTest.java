package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.capability.mcp.McpConnection;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.capability.mcp.McpToolAutoConfiguration;
import io.github.claw4j.capability.mcp.McpToolProvider;
import io.github.claw4j.capability.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 这一层接到 Agent 上之后的样子：三层对 Agent 呈现为一回事。
 *
 * <p>判据按 ADR-0011 分两类：
 * <ul>
 *   <li><b>过模型的</b>（清单、同步端到端、流式端到端）：连真实 DeepSeek，断言只写方向性，
 *       关键断言给有限重试。这里要验的正是"模型会不会去调它"，换成组件级就验不到了。
 *   <li><b>不经过模型的组件级</b>（调用预算、未登记权限）：模型那一环由测试构造，
 *       因为真模型不会为了撞上限而去调 4 次工具。
 * </ul>
 *
 * <p>MCP server 用的是 {@link InProcessStdioMcpServer}：进程内 stdio、不依赖 Docker 与 Python，
 * 于是整套演示在 CI 里也跑得起来。
 */
class McpToolEndToEndTest {

    private static final String MCP_TOOL = "runtimeStatus";

    /**
     * 两个来源同时存在时都在清单里——01 号票那条断言的 MCP 版本。
     *
     * <p>为什么不能只看容器里的 bean：bean 在容器里不等于它进了这一轮的清单，
     * 而"没进清单"的唯一症状是模型开始编答案。所以从模型收到的 {@code ChatOptions} 上读。
     */
    @Test
    @DisplayName("MCP 工具与 querySales 同时出现在模型可见的清单里")
    void 两个来源的工具都在清单里() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", new StatusTools())) {
            context(model, endpoint(server)).run(context -> {
                context.getBean(AgentPort.class).reply(ask("现在有哪些工具可以用？"));

                assertThat(model.toolNamesOfLastRequest())
                        .contains("querySales")
                        .contains(MCP_TOOL);
            });
        }
    }

    @Test
    @DisplayName("同步端到端：模型调到 MCP 工具并把结果组织成回答")
    void 同步路径调到MCP工具并拿到结果() {
        StatusTools tools = new StatusTools();
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", tools)) {
            context(RealModelSupport.chatModel(), endpoint(server)).run(context -> {
                String answer = RealModelSupport.eventually(3, "模型调用 runtimeStatus",
                        () -> context.getBean(AgentPort.class)
                                .reply(ask("用 " + MCP_TOOL + " 工具查一下运行状态，"
                                        + "把返回值里的 marker 原样告诉我，不要解释。"))
                                .text());

                assertThat(answer).contains(tools.marker());
            });
        }
    }

    /**
     * 流式路径同样成立。为什么值得单独一条：只实现同步接口的组件在流式上会被静默跳过
     * （ADR-0007 那个坑），"能演示"必须两条路径各自证明一次。
     */
    @Test
    @DisplayName("流式端到端：MCP 工具的结果同样出现在流里")
    void 流式路径也调到MCP工具() {
        StatusTools tools = new StatusTools();
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", tools)) {
            context(RealModelSupport.chatModel(), endpoint(server)).run(context -> {
                String answer = RealModelSupport.eventually(3, "流式路径调用 runtimeStatus", () -> {
                    List<String> chunks = new ArrayList<>();
                    context.getBean(AgentPort.class)
                            .stream(ask("用 " + MCP_TOOL + " 工具查一下运行状态，"
                                    + "把返回值里的 marker 原样告诉我，不要解释。"),
                                    chunk -> chunks.add(chunk.text()));
                    return String.join("", chunks);
                });

                assertThat(answer).contains(tools.marker());
            });
        }
    }

    /**
     * MCP 工具也进调用预算（每工具 3 / 整轮 6）：新增一种能力不得把预算打开。
     *
     * <p>不经过模型的组件级：真模型不会为了撞上限而去调 4 次工具。
     * 执行预算的是真的 {@code ToolCallingManager} 与真的 {@link ToolCallBudget} 配置，
     * "调了几次"由 MCP server 侧的工具对象计数——不是由 Agent 自己声明。
     */
    @Test
    @DisplayName("MCP 工具的调用同样计入预算")
    void MCP工具的调用计入预算() {
        CountingTools tools = new CountingTools();
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", tools)) {
            McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(endpoint(server))));

            assertThatThrownBy(() -> ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP)
                    .executeToolCalls(promptWith(provider), modelAskedFor("counted", "counted", "counted", "counted")))
                    .hasMessageContaining("Tool call limit");

            // 第 4 次根本没发生：不是"执行了但结果被丢掉"
            assertThat(tools.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        }
    }

    /**
     * 没登记权限的 MCP 工具一律按拒绝（ADR-0007）：漏登记的失败方向是拒绝，不是放行。
     *
     * <p>这里刻意<b>不挂</b> MCP 的自动配置：挂上它就一定会登记。
     * 于是容器里有一个 MCP 工具、但没有一张登记表认领它——正是"没人评估过它会碰到什么"的状态。
     */
    @Test
    @DisplayName("没登记权限的 MCP 工具被拒绝")
    void 没登记权限的MCP工具被拒绝() {
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", new StatusTools())) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                    .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel)
                    .withBean("mcpTools", ToolCallbackProvider.class,
                            () -> new McpToolProvider(List.of(new McpConnection(endpoint(server)))))
                    // 只有 querySales 被登记，MCP 工具没人认领
                    .withBean("permissions", ToolPermissionRegistry.class,
                            () -> ToolPermissionRegistry.of(Map.of("querySales", ToolPermission.read("sales_order"))))
                    .run(context -> {
                        String answer = RealModelSupport.eventually(3, "未登记的 MCP 工具被拒绝",
                                () -> context.getBean(AgentPort.class)
                                        .reply(ask("用 " + MCP_TOOL + " 工具查一下当前进程有多少个可用处理器"))
                                        .text());

                        assertThat(answer)
                                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                                .contains(MCP_TOOL)
                                .contains("未登记权限");
                    });
        }
    }

    // ------------------------------------------------------------------ 装配辅助

    private static ApplicationContextRunner context(ChatModel model, McpServerEndpoint endpoint) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class, McpToolAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                // SalesTools 需要一个执行器：用替身，这里测的是 MCP 不是 SQL
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .withBean("mcpEndpoint", McpServerEndpoint.class, () -> endpoint);
    }

    private static McpServerEndpoint endpoint(InProcessStdioMcpServer server) {
        return McpServerEndpoint.of(server.name(), ToolPermission.read(), server::openTransport);
    }

    private static InboundMessage ask(String question) {
        return new InboundMessage(Principal.readOnly("ethan"), "mcp:" + System.nanoTime(), question);
    }

    private static Prompt promptWith(McpToolProvider provider) {
        return new Prompt(List.of(new UserMessage("把这个工具跑几遍")),
                ToolCallingChatOptions.builder().toolCallbacks(provider.getToolCallbacks()).build());
    }

    /** 等价于"模型这一轮说要调这些工具"：真模型不会配合做这件事，所以由测试构造。 */
    private static ChatResponse modelAskedFor(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String name : toolNames) {
            calls.add(new AssistantMessage.ToolCall("call-" + name + "-" + calls.size(), "function", name, "{}"));
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())))
                .build();
    }

    /**
     * server 侧的工具对象：{@code @McpTool} 标注，Java 侧只见名字与参数 Schema。
     *
     * <p>返回值里带一个<b>每次测试都变的 marker</b>，断言盯的是它而不是"处理器有几个"：
     * 处理器数量是模型猜得出来的常识（"多半是 8"），猜对了这条用例就假绿，
     * 而"模型到底调没调工具"正是它要验的事。marker 是随机的，只有真调用了才拿得到。
     */
    static final class StatusTools {

        private final String marker = "mk-" + Long.toHexString(System.nanoTime());

        @McpTool(name = "runtimeStatus",
                description = "读取当前进程的运行状态，返回一行 status / processors / marker")
        public String runtimeStatus() {
            return "status=UP processors=" + Runtime.getRuntime().availableProcessors() + " marker=" + marker;
        }

        String marker() {
            return marker;
        }
    }

    /** 给预算那条用：server 侧自己计数，"调了几次"不由 Agent 声明。 */
    static final class CountingTools {

        private final AtomicInteger calls = new AtomicInteger();

        @McpTool(name = "counted", description = "每次被调用就加一")
        public String counted() {
            return "called:" + calls.incrementAndGet();
        }

        int calls() {
            return calls.get();
        }
    }
}
