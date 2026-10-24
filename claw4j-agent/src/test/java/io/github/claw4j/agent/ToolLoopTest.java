package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具循环的两件硬约束：<b>失败要能被模型看见</b>、<b>重试有上限</b>（ADR-0008 列为必测项）。
 *
 * <p><b>这里不经过模型（ADR-0011）。</b>自纠错靠的是"模型第一次写错 SQL、拿到数据库报错
 * 后再写一版"，真模型不会配合先写错一次；预算那条则要求连调七次，真模型也不会为撞上限
 * 而这么干。所以"这一轮模型说要调哪些工具"由测试直接构造，
 * <b>执行的是真的 {@code ToolCallingManager}、真的 {@link ToolCallBudget} 配置</b>，
 * 计数由真的工具对象给出。丢掉的只有"这一版是谁写的"。
 *
 * <p>于是判据收缩到仍然确定的三件事：数据库报错的<b>原话</b>会被当成工具结果交回去
 * （没有它模型只能瞎猜改哪儿）；改对之后数能拿到；一直犯错时在上限处<b>停下</b>——
 * 断言"第 N+1 次根本没发生"，而不是"发生了但结果被丢掉"，后者照样烧掉一次往返。
 * "模型真的会据此改对"那一层交给端到端（{@code SalesQueryEndToEndTest}）。
 *
 * <p><b>总数上限为什么不能省</b>：单工具上限 3 比总数 6 小，而 M1 只注册了一个工具，
 * 所以"总数 6"看起来是个没有可观测效果的配置。它守的是"多工具叠加"这个真实风险——
 * 加第二个工具之后，每个工具各调 3 次就是 6 次往返，单工具上限一个都拦不住。
 * 一个上限要么有测试证明它能拦住东西，要么就别写在那儿。
 */
class ToolLoopTest {

    /** 模型第一次写错的 SQL：把 amount 拼成了 amout。这是真实模型最常见的错法。 */
    private static final String BAD_SQL = "select sum(amout) from business.order_item";

    private static final String GOOD_SQL = "select sum(amount) from business.order_item";

    private static final String DB_ERROR = "column \"amout\" does not exist";

    private static final String SQL_RESULT = "128400.00";

    @Test
    @DisplayName("写错的 SQL 拿到的是数据库的原话：没有它模型只能瞎猜改哪儿")
    void 写错的SQL拿到的是数据库的原话() {
        SqlExecutor executor = new SqlExecutor();

        String handedBack = execute(promptWith(callbacksOf(executor)), askedForSql(BAD_SQL));

        assertThat(executor.executed()).containsExactly(BAD_SQL);
        assertThat(handedBack).contains("amout").contains("does not exist");
    }

    @Test
    @DisplayName("第二版改对之后能拿到数")
    void 改好之后能拿到数() {
        SqlExecutor executor = new SqlExecutor();
        List<ToolCallback> callbacks = callbacksOf(executor);

        // 第一轮：错的；第二轮：改好的（真实链路里这两版都由模型写）
        execute(promptWith(callbacks), askedForSql(BAD_SQL));
        String second = execute(promptWith(callbacks), askedForSql(GOOD_SQL));

        assertThat(executor.executed()).containsExactly(BAD_SQL, GOOD_SQL);
        assertThat(second).contains(SQL_RESULT);
    }

    /**
     * 一直写错时循环必须停下来：第四次调用<b>根本没发生</b>。
     * 第 4 次落在同一个工具上，所以拦它的是单工具上限。
     */
    @Test
    @DisplayName("一直写错时在单工具上限处截断，第 4 次根本不发生")
    void 一直写错时在上限处截断() {
        SqlExecutor executor = new SqlExecutor();

        assertThatThrownBy(() -> execute(promptWith(callbacksOf(executor)),
                askedForSql(BAD_SQL, BAD_SQL, BAD_SQL, BAD_SQL)))
                .hasMessageContaining("Tool call limit")
                .hasMessageContaining(String.valueOf(ToolCallBudget.MAX_CALLS_PER_TOOL));

        assertThat(executor.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
    }

    /**
     * 三个工具各调两次 = 六次，第 7 次落在 gamma 上——它才第 3 次，没撞自己的单工具上限，
     * 所以<b>只有总数上限能拦住它</b>。这就是"总数 6"存在的证据。
     *
     * <p>两个工具来源（provider 与单个回调）同时在清单里：工具从哪进来不影响按名字计数，
     * 这一点顺带被钉住。
     */
    @Test
    @DisplayName("多工具叠加时总数上限拦住第 7 次，且拦的是还没撞单工具上限的那个工具")
    void 多工具叠加时总数上限拦住第7次() {
        ThreeTools tools = new ThreeTools();

        assertThatThrownBy(() -> execute(promptWith(twoSourceCallbacks(tools)),
                askedFor("alpha", "alpha", "beta", "beta", "gamma", "gamma", "gamma")))
                .hasMessageContaining("Total tool call limit")
                .hasMessageContaining(String.valueOf(ToolCallBudget.MAX_TOTAL_CALLS));

        // 第 7 次根本没发生：不是"执行了但结果被丢掉"
        assertThat(tools.calls()).isEqualTo(ToolCallBudget.MAX_TOTAL_CALLS);
        assertThat(tools.alphaCalls()).isEqualTo(2);
        assertThat(tools.betaCalls()).isEqualTo(2);
        assertThat(ToolCallBudget.MAX_CALLS_PER_TOOL).isLessThan(ToolCallBudget.MAX_TOTAL_CALLS);
        // gamma 才第 2 次就被拦住：拦它的是总数上限，不是它自己的单工具上限
        assertThat(tools.gammaCalls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL - 1);
    }

    // ------------------------------------------------------------------ 执行辅助

    /** 走真的 {@code ToolCallingManager}：预算是 {@link ToolCallBudget} 那一份，不是重新配的。 */
    private static String execute(Prompt prompt, ChatResponse modelAsked) {
        ToolCallingManager manager = ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP);
        return manager.executeToolCalls(prompt, modelAsked).conversationHistory().stream()
                .map(Object::toString)
                .reduce("", String::concat);
    }

    private static List<ToolCallback> callbacksOf(Object toolObjects) {
        return new ArrayList<>(Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects)
                .build()
                .getToolCallbacks()));
    }

    /** 两个来源：alpha/beta 来自 provider，gamma 是单个回调。 */
    private static List<ToolCallback> twoSourceCallbacks(ThreeTools tools) {
        List<ToolCallback> callbacks = callbacksOf(new ProviderSource(tools));
        callbacks.add(MethodToolCallbackProvider.builder()
                .toolObjects(new SingleSource(tools))
                .build()
                .getToolCallbacks()[0]);
        return callbacks;
    }

    private static Prompt promptWith(List<ToolCallback> callbacks) {
        return new Prompt(List.of(new UserMessage("把工具跑几遍")),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks.toArray(new ToolCallback[0])).build());
    }

    /** 等价于"模型这一轮说要调这些工具"。 */
    private static ChatResponse askedFor(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = Arrays.stream(toolNames)
                .map(name -> new AssistantMessage.ToolCall("call-" + name + "-" + System.nanoTime(),
                        "function", name, "{}"))
                .toList();
        return response(calls);
    }

    private static ChatResponse askedForSql(String... sql) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < sql.length; i++) {
            calls.add(new AssistantMessage.ToolCall("call-" + i, "function", "querySales",
                    "{\"sql\":\"" + sql[i] + "\"}"));
        }
        return response(calls);
    }

    private static ChatResponse response(List<AssistantMessage.ToolCall> calls) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(calls).build())))
                .build();
    }

    // ---------------------------------------------------------------- 测试替身

    /** 数据源替身：写了 amout 就报数据库的原话，写对了就返回那个数。 */
    static final class SqlExecutor {

        private final List<String> executed = new ArrayList<>();

        @Tool(description = "按给定的 SQL 查询业务库（只读）")
        public String querySales(String sql) {
            executed.add(sql);
            return sql.contains("amout") ? "ERROR: " + DB_ERROR : SQL_RESULT;
        }

        List<String> executed() {
            return List.copyOf(executed);
        }

        int calls() {
            return executed.size();
        }
    }

    /** 三个计数工具集在一个对象上，方便读总数。 */
    static final class ThreeTools {

        private final AtomicInteger alpha = new AtomicInteger();

        private final AtomicInteger beta = new AtomicInteger();

        private final AtomicInteger gamma = new AtomicInteger();

        int calls() {
            return alpha.get() + beta.get() + gamma.get();
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
    }

    static final class ProviderSource {

        private final ThreeTools tools;

        ProviderSource(ThreeTools tools) {
            this.tools = tools;
        }

        @Tool(description = "第一个计数工具")
        public String alpha() {
            tools.alpha.incrementAndGet();
            return "alpha";
        }

        @Tool(description = "第二个计数工具")
        public String beta() {
            tools.beta.incrementAndGet();
            return "beta";
        }
    }

    static final class SingleSource {

        private final ThreeTools tools;

        SingleSource(ThreeTools tools) {
            this.tools = tools;
        }

        @Tool(description = "第三个计数工具")
        public String gamma() {
            tools.gamma.incrementAndGet();
            return "gamma";
        }
    }
}
