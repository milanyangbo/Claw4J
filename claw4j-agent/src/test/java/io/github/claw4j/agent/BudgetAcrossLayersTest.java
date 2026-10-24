package io.github.claw4j.agent;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.capability.mcp.McpConnection;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.capability.mcp.McpToolProvider;
import io.github.claw4j.capability.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.capability.script.ScriptPackage;
import io.github.claw4j.capability.script.ScriptPackageRunner;
import io.github.claw4j.capability.script.ScriptPackageToolCallback;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.claw4j.capability.process.ProcessOutcome;
import io.github.claw4j.capability.process.ProcessRequest;
import io.github.claw4j.capability.process.ProcessRunner;
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
 * 复核：M2 加了三层来源之后，调用预算仍然罩得住（票 07 的复核项之一）。
 *
 * <p>M1 那条预算断言（{@code ToolCallBudgetTest}）用的是同一个人造来源里的三个工具。
 * 它能证明预算生效，但证明不了"工具从别的层进来时预算还算不算"——
 * 而预算拦的是 {@code ToolCallingManager} 按<b>名字</b>计数，
 * "名字从哪来"本不该影响它。这条断言就是把这件事钉住：
 * 清单里同时放着一层（Java {@code @Tool}）、二层（MCP，跨进程协议的那个形状）、
 * 三层（脚本包）的工具，第 7 次调用照样被拦。
 *
 * <p>为什么四种形态都要放进清单：这是 01 号票那个坑的形状——
 * 每次新增一种来源都可能"收工具"这件事漏掉一种形态。
 * 预算测试里放进来的形态越多，"某一种来源静默不进清单"就越早变成红的：
 * 少收一种，这一轮能调的工具就少几个，第 7 次根本撞不到上限，断言直接失败。
 *
 * <p>不经过模型：真模型不会为了撞上限而去调 7 次工具（ADR-0011），
 * "模型这一轮说要调哪些"由测试构造，预算、工具对象、MCP 连接全是真的。
 */
class BudgetAcrossLayersTest {

    private final AtomicInteger javaCalls = new AtomicInteger();

    private final AtomicInteger singleCalls = new AtomicInteger();

    private final AtomicInteger mcpCalls = new AtomicInteger();

    private final AtomicInteger scriptCalls = new AtomicInteger();

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

    private static void execute(List<ToolCallback> callbacks, String... wanted) {
        ToolCallingManager manager = ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP);
        manager.executeToolCalls(prompt(callbacks), modelAskedFor(wanted));
    }

    private static Prompt prompt(List<ToolCallback> callbacks) {
        return new Prompt(List.of(new UserMessage("把几个层的工具各跑几遍")),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
    }

    private static ChatResponse modelAskedFor(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = Arrays.stream(toolNames)
                .map(name -> new AssistantMessage.ToolCall("call-" + name + "-" + System.nanoTime(),
                        "function", name, "{}"))
                .toList();
        return ChatResponse.builder()
                .generations(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(calls).build())))
                .build();
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
