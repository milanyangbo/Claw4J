package io.github.claw4j.agent;

import io.github.claw4j.capability.mcp.McpConnection;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.capability.mcp.McpToolProvider;
import io.github.claw4j.capability.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;
import io.github.claw4j.capability.script.ScriptPackage;
import io.github.claw4j.capability.script.ScriptPackageRunner;
import io.github.claw4j.capability.script.ScriptPackageToolCallback;
import io.github.claw4j.domain.principal.ToolPermission;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具循环的三件硬约束：<b>失败要能被模型看见</b>、<b>重试有上限</b>、
 * <b>工具从哪一层进来都不改变前两条</b>（ADR-0008 列为必测项）。
 *
 * <p><b>这里不经过模型（ADR-0011）。</b>自纠错靠的是"模型第一次写错 SQL、拿到数据库报错
 * 后再写一版"，真模型不会配合先写错一次；预算那几条要求连调七次，真模型也不会为撞上限
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
 *
 * <p><b>为什么把"三层来源叠加"也放在这里</b>：预算拦的是 {@code ToolCallingManager}
 * 按<b>名字</b>计数，"名字从哪来"本不该影响它。但每次新增一种工具来源都可能"收工具"时
 * 漏掉一种形态——少收一种，这一轮能调的工具就少几个，第 7 次根本撞不到上限。
 * 所以把一层（Java {@code @Tool}、provider 与单回调两种形态）、二层（MCP，跨进程协议的
 * 那个形状）、三层（脚本包）同时放进清单，看第 7 次还拦不拦得住。
 */
class ToolLoopTest {

    /** 模型第一次写错的 SQL：把 amount 拼成了 amout。这是真实模型最常见的错法。 */
    private static final String BAD_SQL = "select sum(amout) from business.order_item";

    private static final String GOOD_SQL = "select sum(amount) from business.order_item";

    private static final String DB_ERROR = "column \"amout\" does not exist";

    private static final String SQL_RESULT = "128400.00";

    private final AtomicInteger javaCalls = new AtomicInteger();

    private final AtomicInteger singleCalls = new AtomicInteger();

    private final AtomicInteger mcpCalls = new AtomicInteger();

    private final AtomicInteger scriptCalls = new AtomicInteger();

    // ------------------------------------------------------------------ 循环与自纠错

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

    // -------------------------------------------------------------- 三层来源叠加时

    @Test
    @DisplayName("三层来源同时在清单里时，第 7 次调用仍被总数上限拦住")
    void 三层来源叠加时第7次仍被拦(@TempDir Path scriptsRoot) {
        // server 要活到断言结束：MCP 的回调连的就是它，提前关掉等于"连接被关了"
        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("layer-two",
                new LayerTwo(mcpCalls))) {
            List<ToolCallback> callbacks = allLayers(scriptsRoot, server);

            assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                    .contains("layerOneAlpha", "layerOneBeta", "layerOneSingle", "layerTwoMcp");

            assertThatThrownBy(() -> execute(callbacks,
                    "layerOneAlpha", "layerOneAlpha",
                    "layerOneBeta", "layerOneBeta",
                    "layerOneSingle", "layerTwoMcp",
                    "layerThreeScript"))
                    .hasMessageContaining("Total tool call limit")
                    .hasMessageContaining(String.valueOf(ToolCallBudget.MAX_TOTAL_CALLS));

            // 前 6 次真的执行了，第 7 次根本没发生（不是执行了但结果被丢掉）。
            // 一层那个计数器记的是它两个工具的调用总和：alpha 2 次 + beta 2 次。
            assertThat(javaCalls.get()).isEqualTo(4);
            assertThat(singleCalls.get()).isEqualTo(1);
            assertThat(mcpCalls.get()).isEqualTo(1);
            assertThat(scriptCalls.get()).isZero();
        }
    }

    /**
     * 四条来源同时在清单里：一层走 provider 形态、一层走单个回调形态、
     * MCP 那个是跨进程协议的形状、脚本那个是第三层的形状。
     */
    private List<ToolCallback> allLayers(Path scriptsRoot, InProcessStdioMcpServer server) {
        List<ToolCallback> collected = new ArrayList<>();

        // 一层：MethodToolCallbackProvider 导出一组工具（provider 形态）
        collected.addAll(List.of(MethodToolCallbackProvider.builder()
                .toolObjects(new LayerOne(javaCalls))
                .build()
                .getToolCallbacks()));

        // 另一种注册形态：单个 ToolCallback（01 号票那条"两种形态都要收"）
        collected.add(MethodToolCallbackProvider.builder()
                .toolObjects(new LayerOneSingle(singleCalls))
                .build()
                .getToolCallbacks()[0]);

        // 二层：真连接、真协议（进程内 stdio，不依赖 Python 与 Docker）
        McpServerEndpoint endpoint = McpServerEndpoint.of(server.name(),
                ToolPermission.read(), server::openTransport);
        collected.addAll(List.of(
                new McpToolProvider(List.of(new McpConnection(endpoint))).getToolCallbacks()));

        // 三层：脚本包，执行后端是记录器（不真起进程，预算这条不看进程）。
        // 入口文件要真的存在：ScriptPackage 在构造时就检查它，
        // 而"配了个包但入口不在"是配置错误，不是"这个包没有工具"。
        try {
            java.nio.file.Files.writeString(scriptsRoot.resolve("run.sh"), "#!/bin/sh\ntrue\n");
        }
        catch (java.io.IOException failure) {
            throw new IllegalStateException("造不出入口脚本", failure);
        }
        ScriptPackage pkg = ScriptPackage.of("layerThreeScript", scriptsRoot, "第三层的计数包");
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of(pkg),
                ignored -> new RecordingBackend(scriptCalls));
        collected.add(new ScriptPackageToolCallback(pkg, runner));

        return List.copyOf(collected);
    }

    // ------------------------------------------------------------------ 执行辅助

    private static void execute(List<ToolCallback> callbacks, String... wanted) {
        ToolCallingManager manager = ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP);
        manager.executeToolCalls(promptWith(callbacks), askedFor(wanted));
    }

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
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
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

    /** 一层（provider 形态）的两个计数工具。 */
    public static final class LayerOne {

        private final AtomicInteger calls;

        LayerOne(AtomicInteger calls) {
            this.calls = calls;
        }

        @Tool(description = "一层的第一个计数工具")
        public String layerOneAlpha() {
            calls.incrementAndGet();
            return "alpha";
        }

        @Tool(description = "一层的第二个计数工具")
        public String layerOneBeta() {
            calls.incrementAndGet();
            return "beta";
        }
    }

    /**
     * 一层的另一种形态：这个来源只导出<b>一个</b>工具。
     * 只写一个方法是为了让"取第 0 个"是确定的——两个方法时顺序取决于实现，
     * 断言就会时不时地挑到另一个工具上。
     */
    public static final class LayerOneSingle {

        private final AtomicInteger calls;

        LayerOneSingle(AtomicInteger calls) {
            this.calls = calls;
        }

        @Tool(description = "单回调形态里唯一的那一个工具")
        public String layerOneSingle() {
            calls.incrementAndGet();
            return "single";
        }
    }

    /** 二层：server 侧的工具对象，Java 侧只见名字与 Schema。 */
    public static final class LayerTwo {

        private final AtomicInteger calls;

        LayerTwo(AtomicInteger calls) {
            this.calls = calls;
        }

        @McpTool(name = "layerTwoMcp", description = "二层的计数工具")
        public String counted() {
            calls.incrementAndGet();
            return "mcp";
        }
    }

    /** 三层的执行后端：只计数，不起进程（预算这条要验的不是进程）。 */
    private record RecordingBackend(AtomicInteger calls) implements ProcessRunner {

        @Override
        public ProcessOutcome run(ProcessRequest request) {
            calls.incrementAndGet();
            return ProcessOutcome.completed(0, "script", "");
        }
    }
}
