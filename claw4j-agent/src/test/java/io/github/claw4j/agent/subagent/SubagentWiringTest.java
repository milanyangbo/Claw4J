package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.FixedChatModel;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 这一层的装配：配了目录才有 {@code Task} 工具，没配就没有，配错了就起不来。
 *
 * <p>模型这一环用 {@link FixedChatModel}（这里要验的是"容器里有什么"，不是模型会不会派）；
 * 清单断言从**模型真的收到的那一轮**上读——容器里有这个 bean 不等于它进了工具清单，
 * 装配漏收时 bean 还在，模型却看不见那个工具。
 */
class SubagentWiringTest {

    @Test
    @DisplayName("配了目录：Task 与 querySales 同时出现在模型可见的清单里")
    void 两个来源的工具都在清单里(@TempDir Path agents) throws IOException {
        AgentsFixture.write(agents);
        RecordingChatModel model = RecordingChatModel.of(FixedChatModel.replying("好的"));

        context(model, agents).run(context -> {
            context.getBean(AgentPort.class).reply(ask("现在有哪些工具可以用？"));

            assertThat(model.toolNamesOfLastRequest())
                    .contains("querySales")
                    .contains(SubagentDelegation.TOOL_NAME);
        });
    }

    @Test
    @DisplayName("这一层自己登记了权限：动作是委派，业务表一张都没有")
    void 登记了委派权限(@TempDir Path agents) throws IOException {
        AgentsFixture.write(agents);

        context(FixedChatModel.replying("好的"), agents).run(context -> {
            var registries = context.getBeansOfType(ToolPermissionRegistry.class).values();

            assertThat(registries)
                    .as("多个来源各登记各的：每一张表都要在容器里，否则它的工具按未登记被拒")
                    .hasSizeGreaterThan(1);
            assertThat(registries.stream()
                    .map(registry -> registry.permissionFor(SubagentDelegation.TOOL_NAME))
                    .flatMap(Optional::stream)
                    .toList())
                    .anySatisfy(permission -> {
                        assertThat(permission.action()).isEqualTo(SubagentDelegation.ACTION);
                        assertThat(permission.tables()).isEmpty();
                    });
        });
    }

    @Test
    @DisplayName("没配目录就没有这一层：清单里没有 Task")
    void 没配目录就没有委派工具(@TempDir Path agents) throws IOException {
        AgentsFixture.write(agents);
        RecordingChatModel model = RecordingChatModel.of(FixedChatModel.replying("好的"));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class, SubagentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .run(context -> {
                    context.getBean(AgentPort.class).reply(ask("现在有哪些工具可以用？"));

                    assertThat(model.toolNamesOfLastRequest())
                            .contains("querySales")
                            .doesNotContain(SubagentDelegation.TOOL_NAME);
                });
    }

    @Test
    @DisplayName("配了目录却不存在：启动失败，不是“这一层没启用”")
    void 目录不存在即启动失败(@TempDir Path agents) {
        Path missing = agents.resolve("not-there");

        context(FixedChatModel.replying("好的"), missing)
                .run(context -> assertThat(context).hasFailed());
    }

    // ------------------------------------------------------------------ 装配辅助

    private static ApplicationContextRunner context(ChatModel model, Path agents) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class, SubagentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .withPropertyValues("claw4j.subagents.directory=" + agents.toAbsolutePath());
    }

    /** 与 application.yml 里 read-only 那个角色同一口径：能读，也能委派。 */
    private static InboundMessage ask(String question) {
        Principal principal = Principal.of("ethan", "read-only", Set.of(Principal.WILDCARD),
                Set.of(ToolPermission.READ, SubagentDelegation.ACTION));
        return new InboundMessage(principal, "subagent:" + System.nanoTime(), question);
    }

    /** 两份 Subagent 定义：一份查数，一份审报销。 */
    static final class AgentsFixture {

        private AgentsFixture() {
        }

        static void write(Path root) throws IOException {
            Files.createDirectories(root);
            Files.writeString(root.resolve("sales-analyst.md"), """
                    ---
                    name: sales-analyst
                    description: 查区域销售额并给出结论。当要某个区域的成交额、业绩数字时使用。
                    tools: querySales
                    ---
                    你是销售分析员。
                    1. 用 querySales 查出这个区域的成交额。
                    2. 只回答一个数字，不要解释口径，也不要复述工具返回的原始内容。
                    """);
            Files.writeString(root.resolve("expense-auditor.md"), """
                    ---
                    name: expense-auditor
                    description: 审计费用报销单据。当要审报销单、查差旅是否超标时使用。
                    tools: querySales
                    ---
                    你是费用审计员：只给结论，不要复述过程。
                    """);
        }
    }
}
