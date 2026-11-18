package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.McpToolTest.CalcTools;
import io.github.claw4j.capability.mcp.McpToolTest.InProcessStdioMcpServer;
import io.github.claw4j.domain.principal.ToolFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 存在性过滤（票 07）：{@code tool-filter} 排掉的工具**从来没有变成工具**。
 *
 * <p>它要验的是一条链上四处一起少掉：callbacks、权限登记、画像（{@code knowsTool}）、
 * 以及启动日志里那份清单。只少一处的实现会让模型"看得见却调不动"或者反过来，
 * 而两种现象都不指向配置。
 *
 * <p>单独一个文件而不是并进 {@link McpToolTest}：那个类已经 20 个方法（门禁上限），
 * 而过滤是一整层语义，值得自己一份。复用它的进程内 server 与 {@link CalcTools}，
 * 不另造一个对端——四种配置要对的是同一份"server 导出了哪三个工具"。
 */
@ExtendWith(OutputCaptureExtension.class)
class McpToolFilterTest {

    @Test
    @DisplayName("没配 tool-filter 时清单与改前逐个相同：这条最容易漏，漏了就是所有 MCP 工具静默消失")
    void noFilterKeepsEveryTool() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(endpoint(calc, ToolFilter.none()))))) {
            assertThat(provider.toolNames()).containsExactlyInAnyOrder("add", "echo", "explode", "tick");
        }
    }

    @Test
    @DisplayName("include 两个：第三个的 callback 与权限都不存在，画像也查不到它")
    void includeFiltersCallbacksAndPermissions() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(
                     endpoint(calc, new ToolFilter(List.of("add", "echo"), List.of())))))) {
            assertThat(provider.toolNames()).containsExactlyInAnyOrder("add", "echo");
            assertThat(provider.toolNames())
                    .as("清单里少一个就是少一个：没有 callback 的名字不该还留着一个能问到的登记")
                    .containsExactlyInAnyOrder("add", "echo");
            assertThat(provider.knowsTool("tick"))
                    .as("画像层问的就是这个：答 true 的话，allow 里写 tick 会被当成一个存在的工具")
                    .isFalse();
            assertThat(provider.status()).singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.tools())
                            .as("启动日志那份清单读的是同一份，不是另抄的一份")
                            .containsExactlyInAnyOrder("add", "echo"));
        }
    }

    @Test
    @DisplayName("exclude 一个：等价于把其余写进 include，结果一致")
    void excludeRemovesTool() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(
                     endpoint(calc, new ToolFilter(List.of(), List.of("explode"))))))) {
            assertThat(provider.toolNames()).containsExactlyInAnyOrder("add", "echo", "tick");
            assertThat(provider.toolNames()).doesNotContain("explode");
        }
    }

    @Test
    @DisplayName("通配：runtime* 这类写法按整串命中，只留下名字对得上的那个")
    void globFiltersByName() {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools());
             McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(
                     endpoint(calc, new ToolFilter(List.of("e*"), List.of())))))) {
            assertThat(provider.toolNames())
                    .as("echo 与 explode 都以 e 开头：两个都留，tick 与 add 都走")
                    .containsExactlyInAnyOrder("echo", "explode");
        }
    }

    @Test
    @DisplayName("恢复之后重新筛一遍：拿到的不是第一次列出来的那份快照")
    void filterReappliedWhenTheServerComesBack(CapturedOutput output) {
        try (InProcessStdioMcpServer calc = InProcessStdioMcpServer.of("calc", new CalcTools())) {
            McpServerRetryTest.RecordingScheduler scheduler = new McpServerRetryTest.RecordingScheduler();
            AtomicInteger opens = new AtomicInteger();
            ToolFilter filter = new ToolFilter(List.of("add", "echo"), List.of());
            McpServerEndpoint flaky = McpServerEndpoint.of("calc", filter, () -> {
                if (opens.getAndIncrement() == 0) {
                    throw new IllegalStateException("对端正在重启");
                }
                return calc.openTransport();
            });

            try (McpToolProvider provider = new McpToolProvider(List.of(new McpConnection(flaky)), scheduler)) {
                assertThat(provider.toolNames()).as("第一次没连上：这一轮它不在清单里").isEmpty();

                assertThat(scheduler.runNext()).isTrue();

                assertThat(provider.toolNames())
                        .as("恢复时走的是同一条装上清单的路：过滤在 install 里，所以重新筛，不是沿用快照")
                        .containsExactlyInAnyOrder("add", "echo");
                assertThat(provider.toolNames()).containsExactlyInAnyOrder("add", "echo");
                assertThat(output).contains("第 1 次重试后恢复，工具 4 个");
            }
        }
    }

    private static McpServerEndpoint endpoint(InProcessStdioMcpServer server, ToolFilter filter) {
        return McpServerEndpoint.of("calc", filter, server::openTransport);
    }
}
