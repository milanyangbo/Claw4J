package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一轮对话的工具调用预算：每工具 3 次、整轮 6 次（{@link ToolCallBudget}）。
 *
 * <p><b>这一批是不经过模型的组件级测试（ADR-0011）。</b>原来靠脚本化假模型
 * "指挥模型连调 7 次工具"——真模型做不到：它不会为了帮我们撞上限而去调 7 次。
 * 于是"模型这一轮说要调哪些工具"由测试直接构造（带 7 个 {@code toolCalls} 的响应），
 * **执行预算的是真的 {@code ToolCallingManager}、真的 {@link ToolCallBudget} 配置**，
 * 计数由真的工具对象给出。丢掉的只是"模型会自己发起这些调用"这一层。
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

    /**
     * 三个工具分在**两个来源**里：alpha 与 beta 走 provider，gamma 是单个回调。
     * 这样这一条同时钉住票 01 的验收项"预算在多来源下仍生效"——
     * 工具从哪个来源进清单不影响预算，第 7 次照样被拦。
     */
    @Test
    void 多工具叠加时总数上限拦住第7次调用() {
        ThreeTools tools = new ThreeTools();

        assertThatThrownBy(() -> execute(tools, "alpha", "alpha", "beta", "beta", "gamma", "gamma", "gamma"))
                .hasMessageContaining("Total tool call limit")
                .hasMessageContaining(String.valueOf(ToolCallBudget.MAX_TOTAL_CALLS));

        // 第 7 次根本没发生：不是"执行了但结果被丢掉"
        assertThat(tools.calls()).isEqualTo(ToolCallBudget.MAX_TOTAL_CALLS);
        assertThat(tools.alphaCalls()).isEqualTo(2);
        assertThat(tools.betaCalls()).isEqualTo(2);
        assertThat(tools.gammaCalls()).isEqualTo(2);
    }

    /** 这一条是整个设计的支点：第 7 次必须落在还没撞单工具上限的那个工具上。 */
    @Test
    void 第7次调用发生在还没撞单工具上限的那个工具上() {
        ThreeTools tools = new ThreeTools();

        assertThatThrownBy(() -> execute(tools, "alpha", "alpha", "beta", "beta", "gamma", "gamma", "gamma"))
                .isInstanceOf(RuntimeException.class);

        assertThat(ToolCallBudget.MAX_CALLS_PER_TOOL).isLessThan(ToolCallBudget.MAX_TOTAL_CALLS);
        // gamma 才第 2 次就被拦住了：拦它的是总数上限，不是它自己的单工具上限
        assertThat(tools.gammaCalls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL - 1);
    }

    /** 单个工具自己撞上限时也要拦：3 次之后第 4 次不发生。 */
    @Test
    void 同一个工具调满次数后不再执行() {
        ThreeTools tools = new ThreeTools();

        assertThatThrownBy(() -> execute(tools, "alpha", "alpha", "alpha", "alpha"))
                .hasMessageContaining("Tool call limit");

        assertThat(tools.alphaCalls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
    }

    // ------------------------------------------------------------------ 执行辅助

    /** 走真的 {@code ToolCallingManager}：预算是 {@link ToolCallBudget} 那一份，不是重新配的。 */
    private static void execute(ThreeTools tools, String... wanted) {
        ToolCallingManager manager = ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP);
        manager.executeToolCalls(promptWith(tools), modelAskedFor(wanted));
    }

    private static Prompt promptWith(ThreeTools tools) {
        // 两个来源：alpha/beta 来自 provider，gamma 是单个回调
        ToolCallback[] fromProvider = MethodToolCallbackProvider.builder()
                .toolObjects(new ProviderSource(tools))
                .build()
                .getToolCallbacks();
        ToolCallback gamma = MethodToolCallbackProvider.builder()
                .toolObjects(new SingleSource(tools))
                .build()
                .getToolCallbacks()[0];

        List<ToolCallback> callbacks = new ArrayList<>(Arrays.asList(fromProvider));
        callbacks.add(gamma);

        return new Prompt(List.of(new UserMessage("把三个工具各跑几遍")),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
    }

    /** 等价于"模型这一轮说要调这些工具"：真模型不会配合做这件事，所以由测试构造。 */
    private static ChatResponse modelAskedFor(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = Arrays.stream(toolNames)
                .map(name -> new AssistantMessage.ToolCall("call-" + name + "-" + System.nanoTime(),
                        "function", name, "{}"))
                .toList();
        return ChatResponse.builder()
                .generations(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())))
                .build();
    }

    /** 三个计数工具集中在一个对象上，方便读总数。 */
    static final class ThreeTools {

        private final AtomicInteger alphaCalls = new AtomicInteger();

        private final AtomicInteger betaCalls = new AtomicInteger();

        private final AtomicInteger gammaCalls = new AtomicInteger();

        int calls() {
            return alphaCalls.get() + betaCalls.get() + gammaCalls.get();
        }

        int alphaCalls() {
            return alphaCalls.get();
        }

        int betaCalls() {
            return betaCalls.get();
        }

        int gammaCalls() {
            return gammaCalls.get();
        }
    }

    /** provider 形态的来源：出 alpha 与 beta。 */
    static final class ProviderSource {

        private final ThreeTools tools;

        ProviderSource(ThreeTools tools) {
            this.tools = tools;
        }

        @Tool(description = "第一个计数工具")
        public String alpha() {
            tools.alphaCalls.incrementAndGet();
            return "alpha";
        }

        @Tool(description = "第二个计数工具")
        public String beta() {
            tools.betaCalls.incrementAndGet();
            return "beta";
        }
    }

    /** 单个回调形态的来源：只出 gamma。 */
    static final class SingleSource {

        private final ThreeTools tools;

        SingleSource(ThreeTools tools) {
            this.tools = tools;
        }

        @Tool(description = "第三个计数工具")
        public String gamma() {
            tools.gammaCalls.incrementAndGet();
            return "gamma";
        }
    }
}
