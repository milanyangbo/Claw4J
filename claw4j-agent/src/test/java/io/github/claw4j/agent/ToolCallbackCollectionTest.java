package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
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
 * 工具回调的收集点：容器里所有工具来源都要进模型可见的清单。
 *
 * <p>为什么值得单独立一条测试：M2 要在同一个容器里放三种来源（Java {@code @Tool} /
 * MCP / Script Package），而它们的形态不一样——有的是 {@code ToolCallbackProvider}，
 * 有的是单个 {@code ToolCallback}（社区包的 Skill 工具就是一个回调）。
 * 只收一种形态，另一种会静默不进清单：不报错、不告警，只是模型看不见那个工具。
 *
 * <p>盯的是装配那段：它早先只收 {@code ToolCallbackProvider}。
 */
class ToolCallbackCollectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withBean("permissions", ToolPermissionRegistry.class, ToolCallbackCollectionTest::permissions);

    @Test
    @DisplayName("回调 bean 与 provider bean 两种形态都进模型可见的工具清单")
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

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:collect", "把两个来源都跑一遍");
    }

    /** 以单个 {@code ToolCallback} 形态进容器的来源。 */
    static final class StandaloneTools {

        private final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "独立回调形态的工具")
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
