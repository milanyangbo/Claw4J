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
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.github.claw4j.capability.skill.AgentSkills;
import io.github.claw4j.capability.skill.SkillToolAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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
 * Agent Skills 接到 Agent 上之后的样子：模型选中一个做法，并按它写的顺序编排工具。
 *
 * <p>判据按 ADR-0011 分两类：
 * <ul>
 *   <li><b>过模型的</b>（选中并按它编排、同步与流式两条路径）：连真实 DeepSeek。
 *       这里要验的正是"模型会不会去用它"，换成组件级就验不到。
 *   <li><b>不经过模型的</b>（渐进式披露那条上下文断言、未登记权限）：模型那一环由测试构造
 *       或者根本不需要它配合，因为要盯的是"拼给模型的字符串里有什么"。
 * </ul>
 *
 * <p><b>"命中才加载"要两头都钉住</b>：只断言"未命中的正文没进上下文"，
 * 那么"谁的正文都没进上下文"（比如这一层压根没生效）也能全绿。
 * 所以同一份转储上还有一条正向断言（命中的正文进来了、或至少元数据进来了）——
 * 它盯的是这条观测通道本身有没有真的看到拼给模型的内容。
 */
class AgentSkillsEndToEndTest {

    private static final String HIT = "sales-brief";

    private static final String MISSED = "expense-audit";

    /** 清单里要有它：01 号票那条断言的 Skill 版本。 */
    @Test
    @DisplayName("Skill 工具与 querySales 同时出现在模型可见的清单里")
    void 两个来源的工具都在清单里(@TempDir Path skills) throws IOException {
        SkillFixture.write(skills);
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        context(model, skills).run(context -> {
            context.getBean(AgentPort.class).reply(ask("现在有哪些工具可以用？"));

            assertThat(model.toolNamesOfLastRequest())
                    .contains("querySales")
                    .contains(AgentSkills.TOOL_NAME);
        });
    }

    /**
     * 选中并按它编排：Skill 的正文要求"先调 querySales，再把标记原样带上"。
     * 两条断言合起来才说明它真的读了那份做法——只验标记的话，
     * 模型凭常识也能猜出一段像样的简报；只验调过 querySales 的话，
     * "按流程来"这几个字它就未必照做了。
     */
    @Test
    @DisplayName("同步端到端：模型选中 Skill 并按它编排工具")
    void 同步路径选中Skill并按它编排(@TempDir Path skills) throws IOException {
        SkillFixture fixture = SkillFixture.write(skills);
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", "128400.00");

        context(RealModelSupport.chatModel(), skills, executor).run(context ->
                RealModelSupport.eventually(3, "模型选中 sales-brief 并按它编排", () -> {
                    int before = executor.calls();
                    String answer = context.getBean(AgentPort.class)
                            .reply(ask("给我一份华东区上季度的销售简报，按流程来。"))
                            .text();

                    assertThat(answer).contains(fixture.hitMarker());
                    assertThat(executor.calls() - before)
                            .as("Skill 写的是“先查数再输出”：查数这一步没发生，说明它没按那份做法走")
                            .isPositive();
                    return answer;
                }));
    }

    /**
     * 流式路径同样成立。只实现同步接口的组件在流式上会被静默跳过（ADR-0007 那个坑），
     * "能演示"必须两条路径各自证明一次。
     */
    @Test
    @DisplayName("流式端到端：Skill 编排的结果同样出现在流里")
    void 流式路径也选中Skill(@TempDir Path skills) throws IOException {
        SkillFixture fixture = SkillFixture.write(skills);
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", "128400.00");

        context(RealModelSupport.chatModel(), skills, executor).run(context ->
                RealModelSupport.eventually(3, "流式路径选中 sales-brief", () -> {
                    int before = executor.calls();
                    List<String> chunks = new ArrayList<>();
                    context.getBean(AgentPort.class)
                            .stream(ask("给我一份华东区上季度的销售简报，按流程来。"),
                                    chunk -> chunks.add(chunk.text()));
                    String answer = String.join("", chunks);

                    assertThat(answer).contains(fixture.hitMarker());
                    assertThat(executor.calls() - before)
                            .as("流式路径同样要按 Skill 走一遍：先查数再输出")
                            .isPositive();
                    return answer;
                }));
    }

    /**
     * 渐进式披露在整条链路上的样子：两个 Skill 的元数据都在，只有被选中的那一份正文进来。
     *
     * <p>这条不过模型也成立——要盯的是"拼给模型的字符串里有什么"，不是模型怎么答。
     */
    @Test
    @DisplayName("未命中的 Skill 全文不进上下文")
    void 未命中的全文不进上下文(@TempDir Path skills) throws IOException {
        SkillFixture fixture = SkillFixture.write(skills);
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        context(model, skills).run(context -> {
            context.getBean(AgentPort.class).reply(ask("给我一份华东区上季度的销售简报，按流程来。"));

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
     * 没登记权限的 Skill 工具一律按拒绝（ADR-0007）：漏登记的失败方向是拒绝，不是放行。
     *
     * <p>这里刻意<b>不挂</b> Skill 的自动配置：挂上它就一定会登记。
     * 于是容器里有一个 Skill 工具、但没有一张登记表认领它——正是"没人评估过它能碰到什么"的状态。
     */
    @Test
    @DisplayName("没登记权限的 Skill 工具被拒绝")
    void 没登记权限的Skill工具被拒绝(@TempDir Path skills) throws IOException {
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
                                    .reply(ask("给我一份华东区上季度的销售简报，按流程来。"))
                                    .text());

                    assertThat(answer)
                            .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                            .contains(AgentSkills.TOOL_NAME);
                });
    }

    // ------------------------------------------------------------------ 装配辅助

    private static ApplicationContextRunner context(ChatModel model, Path skills) {
        return context(model, skills, FakeSqlExecutor.returning("amount", "128400.00"));
    }

    private static ApplicationContextRunner context(ChatModel model, Path skills, ReadOnlySqlExecutor executor) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class, SkillToolAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class, () -> executor)
                .withPropertyValues("claw4j.skills.directory=" + skills.toAbsolutePath());
    }

    /**
     * 与 application.yml 里 read-only 那个角色同一口径：能读，也能加载做法。
     * Skill 自己不读表，它让模型去读，那一步照样过鉴权。
     */
    private static InboundMessage ask(String question) {
        Principal principal = Principal.of("ethan", "read-only", Set.of(Principal.WILDCARD),
                Set.of(ToolPermission.READ, AgentSkills.ACTION));
        return new InboundMessage(principal, "skill:" + System.nanoTime(), question);
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
}
