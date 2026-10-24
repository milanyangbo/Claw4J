package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.FixedChatModel;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.capability.builtin.sales.QueryResult;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派接到 Agent 上之后的样子：一次提问经过委派仍拿到正确结论，
 * 而父的上下文里看不到子那一趟的中间过程。
 *
 * <p>判据按 ADR-0011 分两类：<b>过模型的</b>（会不会派、派了算不算得出数、同步与流式两条路径）
 * 连真实 DeepSeek；<b>不经过模型的</b>（回传的那一段里有什么）也在真链路上读——
 * 因为"回传的那一段"本身就是链路产物，换成组件级就少了"父真的派了"这层保证。
 * 组件级那几条（白名单、预算、身份透传）在 {@code SubagentRunnerTest} 里。
 *
 * <p><b>隔离的两条断言都配了正向对照</b>：只断言"回传里没有 X"，那么"子那一趟压根没跑"
 * 也能全绿。所以同一份转储上还断言"X 确实在子窗口里出现过"。
 */
class SubagentEndToEndTest {

    private static final String QUESTION =
            "把「华东区上季度成交额是多少」这件事委派给 sales-analyst，然后把它的结论原样告诉我。";

    @Test
    @DisplayName("同步端到端：委派给 Subagent 之后仍拿到正确的数")
    void 同步路径委派后拿到结论(@TempDir Path agents) throws IOException {
        Agents.write(agents);
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", "128400.00");

        context(RealModelSupport.chatModel(), agents, executor).run(context ->
                RealModelSupport.eventually(3, "委派给 sales-analyst 并拿到结论", () -> {
                    int before = executor.calls();
                    String answer = context.getBean(AgentPort.class).reply(ask(QUESTION)).text();

                    RealModelSupport.assertContainsAmount(answer, "128400");
                    assertThat(executor.calls() - before)
                            .as("查数这一步必须发生在子那一趟里：父自己查了就不叫委派")
                            .isPositive();
                    return answer;
                }));
    }

    /**
     * 流式路径同样成立：只实现同步接口的组件在流式上会被静默跳过（ADR-0007 那个坑），
     * "能演示"必须两条路径各自证明一次。
     */
    @Test
    @DisplayName("流式端到端：委派的结果同样出现在流里")
    void 流式路径也走委派(@TempDir Path agents) throws IOException {
        Agents.write(agents);
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", "128400.00");

        context(RealModelSupport.chatModel(), agents, executor).run(context ->
                RealModelSupport.eventually(3, "流式路径委派给 sales-analyst", () -> {
                    int before = executor.calls();
                    List<String> chunks = new ArrayList<>();
                    context.getBean(AgentPort.class).stream(ask(QUESTION), chunk -> chunks.add(chunk.text()));
                    String answer = String.join("", chunks);

                    RealModelSupport.assertContainsAmount(answer, "128400");
                    assertThat(executor.calls() - before)
                            .as("流式路径同样要真的派出去跑一趟")
                            .isPositive();
                    return answer;
                }));
    }

    /**
     * 上下文隔离在真链路上的样子：子那一趟的中间轨迹与它自己的系统提示词，
     * 都不出现在回传给父的那一段里。
     */
    @Test
    @DisplayName("回传给父的只有结论：中间轨迹与子系统提示词都不回流")
    void 回传给父的只有结论(@TempDir Path agents) throws IOException {
        String trace = "TRACE-" + Long.toHexString(System.nanoTime());
        String secret = "SECRET-" + Long.toHexString(System.nanoTime());
        Agents.write(agents, secret);
        FakeSqlExecutor executor = FakeSqlExecutor.returning(new QueryResult(
                List.of("amount", "note"),
                List.of(List.of("128400.00", "中间过程 " + trace)),
                false));
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        context(model, agents, executor).run(context ->
                RealModelSupport.eventually(3, "委派发生，且回传里没有子的中间过程", () -> {
                    context.getBean(AgentPort.class).reply(ask(QUESTION));

                    String everything = everythingModelSaw(model);
                    // 正向对照：子那一趟真的跑了——两样东西都在子窗口里出现过。
                    // 少了它，"委派压根没发生"会让下面两条永远绿。
                    assertThat(everything).contains(trace);
                    assertThat(everything).contains(secret);

                    String returned = returnedToParent(model);
                    assertThat(returned)
                            .as("回传给父的那一段必须有内容：空的话下面两条就是假绿")
                            .isNotEmpty();
                    assertThat(returned)
                            .as("中间轨迹进了父上下文，等于委派白做了一次隔离")
                            .doesNotContain(trace);
                    assertThat(returned)
                            .as("子 Agent 自己的系统提示词同样不该回流")
                            .doesNotContain(secret);
                    return returned;
                }));
    }

    /**
     * 没登记权限的委派工具一律按拒绝（ADR-0007）：漏登记的失败方向是拒绝，不是放行。
     *
     * <p>这里刻意<b>不挂</b> Subagent 的自动配置：挂上它就一定会登记。
     * 于是容器里有一个 {@code Task} 工具、但没有一张登记表认领它——
     * 正是"没人评估过它派出去会碰到什么"的状态。
     */
    @Test
    @DisplayName("没登记权限的委派工具被拒绝")
    void 没登记权限的委派工具被拒绝(@TempDir Path agents) throws IOException {
        Agents.write(agents);
        var definitions = SubagentCatalog.load(agents);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .withBean("subagentTool", ToolCallback.class, () -> new SubagentDelegation(definitions,
                        new SubagentRunner(FixedChatModel.replying("结论"), ObservationRegistry.NOOP,
                                List::of, new AuthorizationAdvisor(List.of()))))
                // 只有 querySales 被登记，Task 没人认领
                .withBean("permissions", ToolPermissionRegistry.class,
                        () -> ToolPermissionRegistry.of(Map.of("querySales", ToolPermission.read("sales_order"))))
                .run(context -> {
                    String answer = RealModelSupport.eventually(3, "未登记的委派工具被拒绝",
                            () -> context.getBean(AgentPort.class).reply(ask(QUESTION)).text());

                    assertThat(answer)
                            .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                            .contains(SubagentDelegation.TOOL_NAME);
                });
    }

    // ------------------------------------------------------------------ 装配辅助

    private static ApplicationContextRunner context(ChatModel model, Path agents, ReadOnlySqlExecutor executor) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class, SubagentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class, () -> executor)
                .withPropertyValues("claw4j.subagents.directory=" + agents.toAbsolutePath());
    }

    /** 与 application.yml 里 read-only 那个角色同一口径：能读，也能委派。 */
    private static InboundMessage ask(String question) {
        Principal principal = Principal.of("ethan", "read-only", Set.of(Principal.WILDCARD),
                Set.of(ToolPermission.READ, SubagentDelegation.ACTION));
        return new InboundMessage(principal, "subagent:" + System.nanoTime(), question);
    }

    /**
     * 模型这一侧看到的一切：系统提示、每条消息、工具调用与工具结果。
     * 父子共用同一个模型实例，所以这份转储里既有父的轮次也有子的轮次——
     * 隔离要盯的是"其中回传给父的那一段"，不是"模型总共见过什么"。
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
        }
        return dump.toString();
    }

    /** 委派工具回传给父的那一段（工具结果）。隔离断言就落在它上面。 */
    private static String returnedToParent(RecordingChatModel model) {
        StringBuilder returned = new StringBuilder();
        for (Prompt prompt : model.prompts()) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof ToolResponseMessage responses) {
                    responses.getResponses()
                            .stream()
                            .filter(response -> SubagentDelegation.TOOL_NAME.equals(response.name()))
                            .forEach(response -> returned.append(response.responseData()));
                }
            }
        }
        return returned.toString();
    }

    /** 一份查数的专职 Agent 定义。{@code secret} 写进它的正文，用来盯"正文不回流"。 */
    static final class Agents {

        private Agents() {
        }

        static void write(Path root) throws IOException {
            write(root, "SECRET-default");
        }

        static void write(Path root, String secret) throws IOException {
            Files.createDirectories(root);
            Files.writeString(root.resolve("sales-analyst.md"), """
                    ---
                    name: sales-analyst
                    description: 查区域销售额并给出结论。当要某个区域的成交额、业绩数字时使用。
                    tools: querySales
                    ---
                    你是销售分析员，内部代号 %s。
                    1. 用 querySales 查出这个区域的成交额（口径：sum(amount) 且 status <> 'CANCELLED'）。
                    2. 只回答那个数字，单位元。**不要**复述工具返回的原始内容，
                       也不要提到任何代号、标记或中间步骤。
                    """.formatted(secret));
        }
    }
}
