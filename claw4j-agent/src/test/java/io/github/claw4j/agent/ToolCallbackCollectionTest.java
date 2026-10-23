package io.github.claw4j.agent;

import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具来源的收集点：容器里每种来源的工具都要进模型可见的清单。
 *
 * <p>为什么值得单独立一条测试：M2 要在同一个容器里放三种来源（Java {@code @Tool} /
 * MCP / Script Package），而它们的形态不一样——有的是 {@code ToolCallbackProvider}，
 * 有的只导出单个 {@code ToolCallback}。
 * 只收一种形态，另一种会静默不进清单：不报错、不告警，只是模型看不见那个工具。
 *
 * <p>判据落在**请求侧**：用 {@link RecordingChatModel} 包一层真模型，
 * 从它记录下来的 {@code ToolCallingChatOptions} 上读工具名。
 * 为什么不是从容器里读 bean：容器里有这个 bean 不等于它进了这一轮的清单——
 * 装配漏收一种形态时 bean 还在、模型却看不见那个工具，
 * 而"模型看不见"的唯一症状是它开始编答案。
 *
 * <p>请求仍然发给了真实 DeepSeek（探针只观察、不替答），
 * 所以这几条同时也证明清单里的工具真能被这一轮请求带出去。
 */
class ToolCallbackCollectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
            // SalesTools 需要一个执行器：用替身，这里测的是收集不是 SQL
            .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                    () -> FakeSqlExecutor.returning("amount", "128400.00"))
            .withBean("permissions", ToolPermissionRegistry.class, ToolCallbackCollectionTest::permissions);

    @Test
    void provider形态与单个回调形态都进模型可见的清单() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        runner(model)
                // 第二种来源：单个 ToolCallback，不是一个 provider
                .withBean("probeCallback", ToolCallback.class, ToolCallbackCollectionTest::probeCallback)
                .run(context -> {
                    context.getBean(AgentPort.class).reply(ask());

                    assertThat(model.toolNamesOfLastRequest())
                            .contains("querySales")
                            .contains("probeTool");
                });
    }

    /**
     * 只有一种来源时清单里就只有它的工具。
     * 这条是上一条的对照：没有它，"把整个容器里的工具全塞进去"这种写法也能全绿。
     */
    @Test
    void 只有一种来源时清单里没有第二种来源的工具() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        runner(model).run(context -> {
            context.getBean(AgentPort.class).reply(ask());

            assertThat(model.toolNamesOfLastRequest())
                    .contains("querySales")
                    .doesNotContain("probeTool");
        });
    }

    /** 没有可用执行器时清单是空的，而不是启动失败——"没有工具可调"与"工具被挤掉"不是一回事。 */
    @Test
    void 没有工具来源时清单是空的() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("permissions", ToolPermissionRegistry.class, ToolCallbackCollectionTest::permissions)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(AgentPort.class).reply(ask());

                    assertThat(model.toolNamesOfLastRequest()).isEmpty();
                });
    }

    private ApplicationContextRunner runner(RecordingChatModel model) {
        return contextRunner.withBean("chatModel", ChatModel.class, () -> model);
    }

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:collect", "现在有哪些工具可以用？");
    }

    private static ToolPermissionRegistry permissions() {
        return ToolPermissionRegistry.of(Map.of(
                "querySales", ToolPermission.read("sales_order"),
                "probeTool", ToolPermission.of("probe")));
    }

    private static ToolCallback probeCallback() {
        return MethodToolCallbackProvider.builder().toolObjects(new ProbeTools()).build()
                .getToolCallbacks()[0];
    }

    /** 第二种来源：以单个工具形态进容器。 */
    static final class ProbeTools {

        @Tool(description = "探针工具，只用来证明它进了模型可见的清单")
        public String probeTool(String input) {
            return "probed:" + input;
        }
    }
}
