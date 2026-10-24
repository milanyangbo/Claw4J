package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.github.claw4j.capability.mcp.McpConnection;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.capability.mcp.McpToolAutoConfiguration;
import io.github.claw4j.capability.mcp.McpToolProvider;
import io.github.claw4j.capability.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.capability.skill.AgentSkills;
import io.github.claw4j.capability.skill.SkillToolAutoConfiguration;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两种工具来源接到 Agent 上之后的样子：<b>技能包</b>（文件系统里的做法）与
 * <b>MCP</b>（跨进程协议）。三层对 Agent 呈现为一回事（ADR-0003）。
 *
 * <p>两份来源放在一份文件里，是因为要过的<b>是同一组判据</b>，且顺序一一对应：
 * 清单里在不在 → 同步能不能真调到 → 流式能不能真调到 → 没登记权限时拒没拒。
 * 分开写时最容易被漏掉的正是"其中一份只验了同步"——过一遍同一个提纲就不会。
 *
 * <p>判据按 ADR-0011 分两类：
 * <ul>
 *   <li><b>过模型的</b>（清单、同步、流式、未登记被拒）：连真实 DeepSeek，
 *       关键断言给有限重试。这里要验的正是"模型会不会去用它"，换成组件级就验不到。
 *   <li><b>不经过模型的</b>（渐进式披露的上下文、MCP 调用预算）：模型那一环由测试构造，
 *       因为真模型不会为了撞上限而去调 4 次工具。
 * </ul>
 *
 * <p>MCP server 用的是 {@link InProcessStdioMcpServer}：进程内 stdio、不依赖 Docker 与
 * Python；技能包是临时目录里的真 SKILL.md。于是整套验收在 CI 里也跑得起来。
 */
class ToolSourcesEndToEndTest {

    private static final String MCP_TOOL = "runtimeStatus";

    private static final String HIT = "sales-brief";

    private static final String MISSED = "expense-audit";

    // ---------------------------------------------------------------- 清单：来源不能被挤掉

    /**
     * 01 号票那条断言：加第二个来源时，第一个不许消失。
     *
     * <p>为什么不能只看容器里的 bean：bean 在容器里不等于它进了这一轮的清单，
     * 而"没进清单"的唯一症状是模型开始编答案。所以从模型收到的 {@code ChatOptions} 上读。
     */
    @Test
    @DisplayName("技能来源与 querySales 同时在模型可见的清单里")
    void 技能来源与querySales都在清单里(@TempDir Path skills) throws IOException {
        SkillFixture.write(skills);
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        skillContext(model, skills).run(context -> {
            context.getBean(AgentPort.class).reply(skillAsk("现在有哪些工具可以用？"));

            assertThat(model.toolNamesOfLastRequest())
                    .contains("querySales")
                    .contains(AgentSkills.TOOL_NAME);
        });
    }

    @Test
    @DisplayName("MCP 来源与 querySales 同时在模型可见的清单里")
    void MCP来源与querySales都在清单里() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", new StatusTools())) {
            mcpContext(model, endpoint(server)).run(context -> {
                context.getBean(AgentPort.class).reply(mcpAsk("现在有哪些工具可以用？"));

                assertThat(model.toolNamesOfLastRequest())
                        .contains("querySales")
                        .contains(MCP_TOOL);
            });
        }
    }

    // -------------------------------------------------------------- 同步：真的用上了

    /**
     * 选中并按它编排：Skill 的正文要求"先调 querySales，再把标记原样带上"。
     * 两条断言合起来才说明它真的读了那份做法——只验标记的话，
     * 模型凭常识也能猜出一段像样的简报；只验调过 querySales 的话，
     * "按流程来"这几个字它就未必照做了。
     */
    @Test
    @DisplayName("同步端到端：模型选中技能并按它编排工具")
    void 同步路径选中技能并按它编排(@TempDir Path skills) throws IOException {
        SkillFixture fixture = SkillFixture.write(skills);
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", "128400.00");

        skillContext(RealModelSupport.chatModel(), skills, executor).run(context ->
                RealModelSupport.eventually(3, "模型选中 sales-brief 并按它编排", () -> {
                    int before = executor.calls();
                    String answer = context.getBean(AgentPort.class)
                            .reply(skillAsk("给我一份华东区上季度的销售简报，按流程来。"))
                            .text();

                    assertThat(answer).contains(fixture.hitMarker());
                    assertThat(executor.calls() - before)
                            .as("Skill 写的是“先查数再输出”：查数这一步没发生，说明它没按那份做法走")
                            .isPositive();
                    return answer;
                }));
    }

    @Test
    @DisplayName("同步端到端：模型调到 MCP 工具并把结果组织成回答")
    void 同步路径调到MCP工具并拿到结果() {
        StatusTools tools = new StatusTools();
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", tools)) {
            mcpContext(RealModelSupport.chatModel(), endpoint(server)).run(context -> {
                String answer = RealModelSupport.eventually(3, "模型调用 runtimeStatus",
                        () -> context.getBean(AgentPort.class)
                                .reply(mcpAsk("用 " + MCP_TOOL + " 工具查一下运行状态，"
                                        + "把返回值里的 marker 原样告诉我，不要解释。"))
                                .text());

                assertThat(answer).contains(tools.marker());
            });
        }
    }

    // -------------------------------------------------------------- 流式：不能被跳过

    /**
     * 流式路径同样成立。只实现同步接口的组件在流式上会被静默跳过（ADR-0007 那个坑），
     * "能演示"必须两条路径各自证明一次——两种来源各一条。
     */
    @Test
    @DisplayName("流式端到端：技能编排的结果同样出现在流里")
    void 流式路径也选中技能(@TempDir Path skills) throws IOException {
        SkillFixture fixture = SkillFixture.write(skills);
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", "128400.00");

        skillContext(RealModelSupport.chatModel(), skills, executor).run(context ->
                RealModelSupport.eventually(3, "流式路径选中 sales-brief", () -> {
                    int before = executor.calls();
                    List<String> chunks = new ArrayList<>();
                    context.getBean(AgentPort.class)
                            .stream(skillAsk("给我一份华东区上季度的销售简报，按流程来。"),
                                    chunk -> chunks.add(chunk.text()));
                    String answer = String.join("", chunks);

                    assertThat(answer).contains(fixture.hitMarker());
                    assertThat(executor.calls() - before)
                            .as("流式路径同样要按 Skill 走一遍：先查数再输出")
                            .isPositive();
                    return answer;
                }));
    }

    @Test
    @DisplayName("流式端到端：MCP 工具的结果同样出现在流里")
    void 流式路径也调到MCP工具() {
        StatusTools tools = new StatusTools();
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("demo", tools)) {
            mcpContext(RealModelSupport.chatModel(), endpoint(server)).run(context -> {
                String answer = RealModelSupport.eventually(3, "流式路径调用 runtimeStatus", () -> {
                    List<String> chunks = new ArrayList<>();
                    context.getBean(AgentPort.class)
                            .stream(mcpAsk("用 " + MCP_TOOL + " 工具查一下运行状态，"
                                    + "把返回值里的 marker 原样告诉我，不要解释。"),
                                    chunk -> chunks.add(chunk.text()));
                    return String.join("", chunks);
                });

                assertThat(answer).contains(tools.marker());
            });
        }
    }

    // ------------------------------------------------------------ 未登记：失败方向是拒绝

    /**
     * 没登记权限的工具一律按拒绝（ADR-0007）：漏登记的失败方向是拒绝，不是放行。
     *
     * <p>这里刻意<b>不挂</b>那一层的自动配置：挂上它就一定会登记。
     * 于是容器里有一个工具、但没有一张登记表认领它——正是"没人评估过它能碰到什么"的状态。
     */
    @Test
    @DisplayName("没登记权限的技能工具被拒绝")
    void 没登记权限的技能工具被拒绝(@TempDir Path skills) throws IOException {
        SkillFixture.write(skills);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .withBean("skillTool", ToolCallback.class, () -> AgentSkills.callback(skills))
                // 只有 querySales 被登记，Skill 工具没人认领
                .withBean("permissions", ToolPermissionRegistry.class,
                        () -> ToolPermissionRegistry.of(Map.of("querySales", ToolPermission.read("sales_order"))))
                .run(context -> {
                    String answer = RealModelSupport.eventually(3, "未登记的 Skill 工具被拒绝",
                            () -> context.getBean(AgentPort.class)
                                    .reply(skillAsk("给我一份华东区上季度的销售简报，按流程来。"))
                                    .text());

                    assertThat(answer)
                            .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                            .contains(AgentSkills.TOOL_NAME);
                });
    }

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
                                        .reply(mcpAsk("用 " + MCP_TOOL + " 工具查一下当前进程有多少个可用处理器"))
                                        .text());

                        assertThat(answer)
                                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                                .contains(MCP_TOOL)
                                .contains("未登记权限");
                    });
        }
    }

    // ------------------------------------------------- 不经过模型的两条：上下文与预算

    /**
     * 渐进式披露在整条链路上的样子：两个 Skill 的元数据都在，只有被选中的那一份正文进来。
     *
     * <p><b>"命中才加载"要两头都钉住</b>：只断言"未命中的正文没进上下文"，
     * 那么"谁的正文都没进上下文"（比如这一层压根没生效）也能全绿。
     * 所以同一份转储上还有一条正向断言——它盯的是这条观测通道本身有没有真的看到拼给模型的内容。
     */
    @Test
    @DisplayName("未命中的技能全文不进上下文")
    void 未命中的全文不进上下文(@TempDir Path skills) throws IOException {
        SkillFixture fixture = SkillFixture.write(skills);
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        skillContext(model, skills).run(context -> {
            context.getBean(AgentPort.class).reply(skillAsk("给我一份华东区上季度的销售简报，按流程来。"));

            String everything = everythingModelSaw(model);

            // 正向对照：这条观测通道真的看到了拼给模型的内容。
            // 少了它，"转储是空的"会让下面那条永远绿。
            assertThat(everything)
                    .contains("<name>" + HIT + "</name>")
                    .contains("<name>" + MISSED + "</name>");
            assertThat(everything)
                    .as("没被选中的那份做法不该进上下文：进了就等于“命中才加载”没生效，"
                            + "只是把二十份正文常驻在提示词里")
                    .doesNotContain(fixture.missedMarker());
        });
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

    // ------------------------------------------------------------------ 装配辅助

    private static ApplicationContextRunner skillContext(ChatModel model, Path skills) {
        return skillContext(model, skills, FakeSqlExecutor.returning("amount", "128400.00"));
    }

    private static ApplicationContextRunner skillContext(ChatModel model, Path skills,
                                                         ReadOnlySqlExecutor executor) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class, SkillToolAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class, () -> executor)
                .withPropertyValues("claw4j.skills.directory=" + skills.toAbsolutePath());
    }

    private static ApplicationContextRunner mcpContext(ChatModel model, McpServerEndpoint endpoint) {
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

    /**
     * 与 application.yml 里 read-only 那个角色同一口径：能读，也能加载做法。
     * Skill 自己不读表，它让模型去读，那一步照样过鉴权。
     */
    private static InboundMessage skillAsk(String question) {
        Principal principal = Principal.of("ethan", "read-only", Set.of(Principal.WILDCARD),
                Set.of(ToolPermission.READ, AgentSkills.ACTION));
        return new InboundMessage(principal, "skill:" + System.nanoTime(), question);
    }

    private static InboundMessage mcpAsk(String question) {
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
     * 把探针记下来的每一轮请求摊平成一个字符串：系统提示、每条消息、
     * 工具调用与工具结果、以及清单里每个工具的名字/描述/Schema。
     *
     * <p>为什么要摊得这么全：渐进式披露的漏洞可能出现在任何一个位置
     * （工具描述里、工具结果里、系统提示里）。只查一处，漏的那处就是假绿。
     */
    private static String everythingModelSaw(RecordingChatModel model) {
        StringBuilder dump = new StringBuilder();
        for (Prompt prompt : model.prompts()) {
            for (Message message : prompt.getInstructions()) {
                dump.append(message.getText());
                if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                    assistant.getToolCalls()
                            .forEach(call -> dump.append(call.name()).append(call.arguments()));
                }
                if (message instanceof ToolResponseMessage responses) {
                    responses.getResponses()
                            .forEach(response -> dump.append(response.name()).append(response.responseData()));
                }
            }
            if (prompt.getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null) {
                for (ToolCallback callback : options.getToolCallbacks()) {
                    dump.append(callback.getToolDefinition().name())
                            .append(callback.getToolDefinition().description())
                            .append(callback.getToolDefinition().inputSchema());
                }
            }
        }
        return dump.toString();
    }

    /** 两份 SKILL.md：一份会被问到（正文带一个随机标记），一份不会。 */
    static final class SkillFixture {

        private final String hitMarker = "mk-hit-" + Long.toHexString(System.nanoTime());

        private final String missedMarker = "mk-miss-" + Long.toHexString(System.nanoTime());

        private SkillFixture() {
        }

        static SkillFixture write(Path root) throws IOException {
            SkillFixture fixture = new SkillFixture();

            skill(root, HIT, "生成区域销售简报。当用户要某个区域的销售额、业绩简报、经营概况时使用。", """
                    # 销售简报流程

                    1. 先调用 querySales 查这个区域的销售额。
                    2. 把结果写成一行简报，并原样带上这个标记：%s

                    不要解释口径，只给数与标记。
                    """.formatted(fixture.hitMarker()));

            skill(root, MISSED, "审计费用报销单据。当用户要审报销单、查差旅是否超标时使用。", """
                    # 费用审计流程

                    这一段是费用审计的做法，只有加载了这份 Skill 才看得到：%s

                    1. 导出单据清单。
                    2. 逐条比对标准。
                    """.formatted(fixture.missedMarker()));

            return fixture;
        }

        String hitMarker() {
            return hitMarker;
        }

        String missedMarker() {
            return missedMarker;
        }

        private static void skill(Path root, String name, String description, String body) throws IOException {
            Path directory = root.resolve(name);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: %s
                    ---
                    %s
                    """.formatted(name, description, body));
        }
    }

    /**
     * MCP server 侧的工具对象：{@code @McpTool} 标注，Java 侧只见名字与参数 Schema。
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
