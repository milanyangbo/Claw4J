package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一轮对话里工具调用的<b>总数</b>上限（{@link ToolCallBudget#MAX_TOTAL_CALLS}）。
 *
 * <p>这条测试存在的理由有点绕，但值得写下来：单工具上限
 * {@link ToolCallBudget#MAX_CALLS_PER_TOOL}（3）比总数上限（6）小，
 * 而 M1 只注册了一个工具，所以"总数 6"这辈子都不会被撞到——
 * 它是个没有任何可观测效果的配置。项目里有条原则是"不留测不出作用的代码"
 * （M1-05 里那个 {@code set transaction read only} 就是这么删掉的），
 * 按那条原则它该删。但它守的是"多工具叠加"这个真实存在的风险：
 * 加第二个工具之后，每个工具各调 3 次就是 6 次往返，单工具上限一个都拦不住。
 * 所以这里不删它，而是用三个工具把它逼出来——
 * <b>一个上限要么有测试证明它能拦住东西，要么就别写在那儿</b>。
 *
 * <p>三个工具各调 2 次 = 6 次，第 7 次调用落在 gamma 上（它才第 3 次，
 * 没撞单工具上限），于是<b>只有总数上限能拦住它</b>。
 * 断言"第 7 次根本没发生"，而不是"发生了但结果被丢掉"——后者照样烧掉一次往返。
 */
class ToolCallBudgetTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withBean("chatModel", ChatModel.class, ToolCallBudgetTest::script)
            .withBean("permissions", ToolPermissionRegistry.class, ToolCallBudgetTest::permissions);

    @Test
    void 多工具叠加时总数上限拦住第7次调用() {
        ThreeTools tools = new ThreeTools();

        withTools(tools).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

            // 三个工具各被调 2 次，第 7 次没发生
            assertThat(tools.calls()).isEqualTo(6);
            assertThat(tools.alphaCalls()).isEqualTo(2);
            assertThat(tools.betaCalls()).isEqualTo(2);
            assertThat(tools.gammaCalls()).isEqualTo(2);
            // 撞上限之后不再回头问模型。消息里那个数字就是总数上限——
            // 断言它，等于钉住"拦住这次调用的是总数，不是别的"。
            assertThat(out.text())
                    .contains("Total tool call limit")
                    .contains(String.valueOf(ToolCallBudget.MAX_TOTAL_CALLS))
                    .doesNotContain("这句话不该被用到");
        });
    }

    @Test
    void 第7次调用发生在还没撞单工具上限的那个工具上() {
        // 这条是整个设计的支点：如果第 7 次落在已经调满 3 次的工具上，
        // 拦住它的就是单工具上限，总数上限依旧是个摆设。
        // 所以要把"gamma 在第 7 次时才第 3 次"这件事单独钉住。
        ThreeTools tools = new ThreeTools();

        withTools(tools).run(context -> {
            context.getBean(AgentPort.class).reply(ask());

            assertThat(ToolCallBudget.MAX_CALLS_PER_TOOL).isLessThan(ToolCallBudget.MAX_TOTAL_CALLS);
            assertThat(tools.gammaCalls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL - 1);
        });
    }

    private ApplicationContextRunner withTools(ThreeTools tools) {
        return contextRunner
                .withBean("toolCallbacks", ToolCallbackProvider.class,
                        () -> MethodToolCallbackProvider.builder().toolObjects(tools).build());
    }

    private static ScriptedChatModel script() {
        return ScriptedChatModel.builder()
                .thenToolCall("alpha", "{}")
                .thenToolCall("alpha", "{}")
                .thenToolCall("beta", "{}")
                .thenToolCall("beta", "{}")
                .thenToolCall("gamma", "{}")
                .thenToolCall("gamma", "{}")
                // 第 7 次：gamma 的第 3 次，单工具上限（3）还没到，只有总数上限能拦
                .thenToolCall("gamma", "{}")
                .thenText("这句话不该被用到")
                .build();
    }

    private static ToolPermissionRegistry permissions() {
        return ToolPermissionRegistry.of(Map.of(
                "alpha", ToolPermission.read(),
                "beta", ToolPermission.read(),
                "gamma", ToolPermission.read()));
    }

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:budget", "把三个区的数都查一遍");
    }

    /** 三个只会计数的假工具：这条测试关心的是"调了几次"，不是"查出了什么"。 */
    static final class ThreeTools {

        private final AtomicInteger alpha = new AtomicInteger();

        private final AtomicInteger beta = new AtomicInteger();

        private final AtomicInteger gamma = new AtomicInteger();

        @Tool(description = "第一个假工具")
        public String alpha() {
            alpha.incrementAndGet();
            return "ok";
        }

        @Tool(description = "第二个假工具")
        public String beta() {
            beta.incrementAndGet();
            return "ok";
        }

        @Tool(description = "第三个假工具")
        public String gamma() {
            gamma.incrementAndGet();
            return "ok";
        }

        int alphaCalls() {
            return alpha.get();
        }

        int betaCalls() {
            return beta.get();
        }

        int gammaCalls() {
            return gamma.get();
        }

        int calls() {
            return alpha.get() + beta.get() + gamma.get();
        }
    }
}
