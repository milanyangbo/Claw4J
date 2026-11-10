package io.github.claw4j.example.mcp.server;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Java 版 MCP server 的验收：它必须是另一个进程，并且走真协议。
 *
 * <p>非要真进程，是因为进程内管道验的是代码能不能被调用，验不了三件真会出事的东西：子进程的
 * classpath 能不能加载到类、stdout 有没有被日志污染、客户端关掉之后子进程会不会变成孤儿。
 * 这三件在进程内一个都看不见。
 *
 * <p>它属于回归基线而不进 {@code real-cli} 组：这里只依赖 JDK 自己，没有 Python、没有 Docker、
 * 没有外部 CLI。要装点什么才能跑的测试最后都会变成没人跑。
 */
class JavaMcpServerProcessTest {

    @Test
    @DisplayName("server 是独立进程：列得出工具、调得动、pid 与本进程不同、关掉之后进程真的没了")
    void serverRunsAsItsOwnProcess() {
        long testJvmPid = ProcessHandle.current().pid();

        // 子进程的 java 用本 JVM 那个，不读 JAVA_HOME：surefire 里那个环境变量可能是空的。
        ServerParameters params = ServerParameters.builder(javaBinary())
                .args("-cp", System.getProperty("java.class.path"), McpServerApplication.class.getName())
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());

        long serverPid;
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {

            client.initialize();

            assertThat(client.getServerInfo().name())
                    .as("连上的必须是我们这个 server，而不是某个同名默认值")
                    .isEqualTo("claw4j-java-demo");

            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .as("工具清单为空也是「连得上」的——ADR-0002 要求把那种状态当失败")
                    .containsExactlyInAnyOrder("echo", "serverTime", "processId");

            assertThat(call(client, "echo", Map.of("text", "跨进程往返没丢字")))
                    .isEqualTo("跨进程往返没丢字");

            serverPid = Long.parseLong(call(client, "processId", Map.of()));
            assertThat(serverPid)
                    .as("同一个 pid 就说明它其实跑在测试进程里，这条用例什么都没验")
                    .isNotEqualTo(testJvmPid);

            assertThat(Instant.parse(call(client, "serverTime", Map.of())))
                    .as("时间来自 server 进程，不是测试里硬编码的")
                    .isBetween(Instant.now().minus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofMinutes(5)));
        }

        // 关掉客户端之后子进程不能留在那里：孤儿进程在 CI 上表现为"那台机器越跑越慢"，
        // 而失败信息里一个字都不会提到它。
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !ProcessHandle.of(serverPid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static String call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("调用 %s 报错了", tool).isNotEqualTo(Boolean.TRUE);
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    /** 用本 JVM 的那个 java，而不是 {@code $JAVA_HOME}（surefire 里可能是空的）。 */
    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
