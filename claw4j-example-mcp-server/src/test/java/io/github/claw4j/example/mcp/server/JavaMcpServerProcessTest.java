package io.github.claw4j.example.mcp.server;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 示例 MCP server 的验收：它必须是另一个进程，并且走真的 HTTP。
 *
 * <p>非要真进程，是因为进程内管道验的是"方法能不能被调用"，验不了三件真会出事的东西：
 * 子进程的 classpath 能不能加载到类、端口有没有真的监听、客户端断开之后子进程会不会变成孤儿。
 * 这三件在进程内一个都看不见。
 *
 * <p>它属于回归基线：只依赖 JDK 自己，没有 Python、没有 Docker、没有外部 CLI。
 * 要装点什么才能跑的测试最后都会变成没人跑。
 *
 * <p>端口是临时挑的空闲端口，不是固定的 8082：并行构建或者本机正好开着一份示例 server，
 * 都不该让这条用例红。
 */
class JavaMcpServerProcessTest {

    @Test
    @DisplayName("HTTP 上握手成功、工具清单是那三个；连着调两次是同一个进程；关掉之后进程真的没了")
    void serverRunsAsItsOwnProcess() throws Exception {
        int port = freePort();
        Path log = Files.createTempFile("claw4j-example-mcp-server", ".log");
        Process server = new ProcessBuilder(javaBinary(), "-cp", System.getProperty("java.class.path"),
                McpServerApplication.class.getName(), "--server.port=" + port)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        try {
            awaitListening(port, server, log);

            var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .build();
            try (McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build()) {

                client.initialize();

                assertThat(client.getServerInfo().name())
                        .as("连上的必须是我们这个 server，而不是某个同名默认值")
                        .isEqualTo("claw4j-http-demo");

                assertThat(client.listTools().tools())
                        .extracting(McpSchema.Tool::name)
                        .as("工具清单为空也是「连得上」的——ADR-0002 要求把那种状态当失败")
                        .containsExactlyInAnyOrder("runtimeSnapshot", "cronNext", "simulateSlowCall");

                String first = call(client, "runtimeSnapshot", Map.of());
                String second = call(client, "runtimeSnapshot", Map.of());

                long serverPid = field(first, "pid");
                assertThat(field(second, "pid"))
                        .as("两次快照的 pid 相同：这中间没有换过进程")
                        .isEqualTo(serverPid);
                assertThat(serverPid)
                        .as("pid 与测试进程相同就说明它其实跑在测试进程里，这条用例什么都没验")
                        .isNotEqualTo(ProcessHandle.current().pid());
                assertThat(field(second, "handledCalls"))
                        .as("两次调用落在同一个实例上，计数就该涨；每次新建一个 server 会从头开始")
                        .isGreaterThan(field(first, "handledCalls"));

                assertThat(call(client, "cronNext", Map.of("expression", "0 9 * * 1-5", "count", 2)))
                        .as("算出来的要是真的触发时间，不是回显")
                        .contains("未来 2 次")
                        .contains("09:00");

                assertThat(call(client, "simulateSlowCall", Map.of("millis", 50)))
                        .contains("实际");
            }
        }
        finally {
            server.destroy();
            assertThat(server.waitFor(20, TimeUnit.SECONDS))
                    .as("关掉之后子进程必须退出：孤儿进程在 CI 上表现为「那台机器越跑越慢」，"
                            + "而失败信息里一个字都不会提到它")
                    .isTrue();
        }
    }

    /**
     * 等到端口真的在监听。
     *
     * <p>不能拿"进程还活着"当就绪：Spring Boot 起容器要一两秒，这期间端口是拒连的。
     *
     * @param port   监听端口
     * @param server 子进程，用它判断是不是已经挂了
     * @param log    子进程的输出，起不来时原样带进失败信息
     */
    private static void awaitListening(int port, Process server, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                throw new IllegalStateException("server 进程退出了，输出：\n" + Files.readString(log));
            }
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return;
            }
            catch (IOException notYet) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("server 在 90 秒内没有监听 " + port + "，输出：\n" + Files.readString(log));
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static String call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("调用 %s 报错了", tool).isNotEqualTo(Boolean.TRUE);
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    /** 从 {@code runtimeSnapshot} 的 {@code key=value} 行里取一个数。 */
    private static long field(String snapshot, String key) {
        return snapshot.lines()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> Long.parseLong(line.substring(key.length() + 1).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("快照里没有 " + key + "：\n" + snapshot));
    }

    /** 用本 JVM 的那个 java，而不是 {@code $JAVA_HOME}（surefire 里可能是空的）。 */
    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
