package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import io.github.claw4j.tools.sales.QueryResult;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具来源的收集点：容器里每种来源的工具都要进模型可见的清单。
 *
 * <p>为什么值得单独立一条测试：M2 要在同一个容器里放三种来源（Java {@code @Tool} /
 * MCP / Script Package），而它们的形态不一样——有的是 {@code ToolCallbackProvider}，
 * 有的只导出单个 {@code ToolCallback}。
 * 只收一种形态，另一种会静默不进清单：不报错、不告警，只是模型看不见那个工具。
 *
 * <p>盯的是装配那段：它早先只收 {@code ToolCallbackProvider}。
 */
class ToolCallbackCollectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withBean("permissions", ToolPermissionRegistry.class, ToolCallbackCollectionTest::permissions);

    @Test
    @DisplayName("单工具 bean 与 provider bean 两种形态都进模型可见的工具清单")
    void 两种形态都在模型可见的清单里() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText("收到").build();
        StandaloneTools standalone = new StandaloneTools();
        ProviderSourceTools fromProvider = new ProviderSourceTools();

        runner(model)
                .withBean("standalone", ToolCallback.class, standalone::asCallback)
                .withBean("providerSource", ToolCallbackProvider.class, fromProvider::asProvider)
                .run(context -> {
                    context.getBean(AgentPort.class).reply(ask());

                    assertThat(seenToolNames(model)).contains("standaloneTool", "providerTool");
                });
    }

    @Test
    @DisplayName("两个来源的工具都能被真的调到")
    void 两个来源的工具都能被调用() {
        StandaloneTools standalone = new StandaloneTools();
        ProviderSourceTools fromProvider = new ProviderSourceTools();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("standaloneTool", "{}")
                .thenToolCall("providerTool", "{}")
                .thenText("两个都跑过了")
                .build();

        runner(model)
                .withBean("standalone", ToolCallback.class, standalone::asCallback)
                .withBean("providerSource", ToolCallbackProvider.class, fromProvider::asProvider)
                .run(context -> {
                    OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

                    assertThat(standalone.calls()).isEqualTo(1);
                    assertThat(fromProvider.calls()).isEqualTo(1);
                    assertThat(out.text()).contains("两个都跑过了");
                });
    }

    /**
     * 票面那条 Demo 的直接落点：**现有工具与第二来源的工具同时出现在模型可见的清单里。**
     *
     * <p>为什么不能只测一半：只查容器里的 bean，测不到"装配有没有把它挂上链"；
     * 只用假工具，又测不到"第二个来源进来时第一个还在不在"。
     * 这条是唯一把两件事钉在一起的断言。
     */
    @Test
    @DisplayName("第二个来源进来时 querySales 仍在模型可见的清单里")
    void 第二个来源不会把现有工具挤出清单() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText("收到").build();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolsAutoConfiguration.class, AgentAutoConfiguration.class))
                .withBean("executor", ReadOnlySqlExecutor.class, () -> sql -> QueryResult.empty())
                .withBean("permissions", ToolPermissionRegistry.class, ToolCallbackCollectionTest::permissions)
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("secondSource", ToolCallbackProvider.class,
                        () -> MethodToolCallbackProvider.builder().toolObjects(new ProviderSourceTools()).build())
                .run(context -> {
                    context.getBean(AgentPort.class).reply(ask());

                    assertThat(seenToolNames(model)).contains("querySales", "providerTool");
                });
    }

    /**
     * 预算是**全局**的：来源怎么切分都不重置。
     *
     * <p>为什么值得一条：预算挂在工具循环的 manager 上，与"工具从哪个来源来"无关。
     * 但"新增一种来源"是改装配的入口，正是最容易顺手把它绕过去的地方——
     * 三个工具分在两个来源里跑同样的脚本，撞的必须还是那个 6。
     */
    @Test
    @DisplayName("三个工具分在两个来源里，第 7 次调用照样被总数预算拦住")
    void 预算跨来源计数() {
        TwoToolSource fromProvider = new TwoToolSource();
        OneToolSource standalone = new OneToolSource();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("alpha", "{}")
                .thenToolCall("alpha", "{}")
                .thenToolCall("beta", "{}")
                .thenToolCall("beta", "{}")
                .thenToolCall("gamma", "{}")
                .thenToolCall("gamma", "{}")
                // 第 7 次：gamma 才第 3 次，单工具上限（3）还没到，只有总数上限能拦
                .thenToolCall("gamma", "{}")
                .thenText("这句话不该被用到")
                .build();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withBean("permissions", ToolPermissionRegistry.class, ToolCallbackCollectionTest::budgetPermissions)
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("providerSource", ToolCallbackProvider.class, fromProvider::asProvider)
                .withBean("standalone", ToolCallback.class, standalone::asCallback)
                .run(context -> {
                    OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

                    assertThat(fromProvider.calls() + standalone.calls()).isEqualTo(ToolCallBudget.MAX_TOTAL_CALLS);
                    assertThat(standalone.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL - 1);
                    assertThat(out.text())
                            .contains("Total tool call limit")
                            .contains(String.valueOf(ToolCallBudget.MAX_TOTAL_CALLS))
                            .doesNotContain("这句话不该被用到");
                });
    }

    private ApplicationContextRunner runner(ScriptedChatModel model) {
        return contextRunner.withBean("chatModel", ChatModel.class, () -> model);
    }

    /** 模型第一轮看见的工具名：清单里没有的工具，模型根本不会去调。 */
    private static List<String> seenToolNames(ScriptedChatModel model) {
        Prompt first = model.prompts().get(0);
        assertThat(first.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        return ((ToolCallingChatOptions) first.getOptions()).getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static ToolPermissionRegistry permissions() {
        return ToolPermissionRegistry.of(Map.of(
                "standaloneTool", ToolPermission.read(),
                "providerTool", ToolPermission.read()));
    }

    private static ToolPermissionRegistry budgetPermissions() {
        return ToolPermissionRegistry.of(Map.of(
                "alpha", ToolPermission.read(),
                "beta", ToolPermission.read(),
                "gamma", ToolPermission.read()));
    }

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:collect", "把两个来源都跑一遍");
    }

    /** 以单个 {@code ToolCallback} 形态进容器的来源。 */
    static final class StandaloneTools {

        private final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "以单个工具形态进容器的工具")
        String standaloneTool() {
            calls.incrementAndGet();
            return "ok";
        }

        ToolCallback asCallback() {
            return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks()[0];
        }

        int calls() {
            return calls.get();
        }
    }

    /** 一个来源导出两个工具：用来证明预算不会按来源重置。 */
    static final class TwoToolSource {

        private final AtomicInteger alpha = new AtomicInteger();

        private final AtomicInteger beta = new AtomicInteger();

        @Tool(description = "第一个来源的工具甲")
        String alpha() {
            alpha.incrementAndGet();
            return "ok";
        }

        @Tool(description = "第一个来源的工具乙")
        String beta() {
            beta.incrementAndGet();
            return "ok";
        }

        ToolCallbackProvider asProvider() {
            return MethodToolCallbackProvider.builder().toolObjects(this).build();
        }

        int calls() {
            return alpha.get() + beta.get();
        }
    }

    /** 另一个来源只有一个工具，以单个 {@code ToolCallback} 形态进容器。 */
    static final class OneToolSource {

        private final AtomicInteger gamma = new AtomicInteger();

        @Tool(description = "第二个来源的工具丙")
        String gamma() {
            gamma.incrementAndGet();
            return "ok";
        }

        ToolCallback asCallback() {
            return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks()[0];
        }

        int calls() {
            return gamma.get();
        }
    }

    /** 以 {@code ToolCallbackProvider} 形态进容器的来源。 */
    static final class ProviderSourceTools {

        private final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "provider 形态的工具")
        String providerTool() {
            calls.incrementAndGet();
            return "ok";
        }

        ToolCallbackProvider asProvider() {
            return MethodToolCallbackProvider.builder().toolObjects(this).build();
        }

        int calls() {
            return calls.get();
        }
    }
}
