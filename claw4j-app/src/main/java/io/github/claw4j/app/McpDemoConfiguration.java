package io.github.claw4j.app;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 跨进程 MCP 的<b>演示接线</b>：把 {@code examples/mcp/} 下那几个 Python server 接成端点。
 *
 * <p>它只在 {@code mcp-demo} profile 下生效，三个 server 还各自有一个开关。
 * 为什么要这样收口：MCP 这一层是<b>连接建立时就去连对方</b>的（工具清单为空即启动失败，
 * ADR-0002），于是"配了一个 server"等于"启动时必须有一个真的 server 在那儿"。
 * 演示用的 server 依赖本机装了 python3（{@code gh} / {@code himalaya} 那两个还依赖 CLI 本体），
 * 把这种依赖写进默认配置，等于让"没装 gh 的机器启动不了应用"。
 *
 * <p><b>权限写在这里而不是写在 server 里</b>：同一个 {@code gh} 包，
 * 只读地查 issue 与能开 PR 是两种边界，而"我们接的是哪一种"只有接线的人知道。
 * {@code himalaya} 那个导出 {@code mail_send}，所以它登记的是
 * {@code send_email} 而不是只读——一个会发邮件的 server 不能被声明成只读能力。
 *
 * <p>环境变量只给 {@code PATH}：子进程的环境中没有模型的 key、没有数据库口令。
 * 需要别的（比如 {@code GH_TOKEN}）就在这里显式加一行，
 * 于是"给出去了什么"在这份文件里是看得见的，而不是"继承了一切"。
 */
@Configuration
@Profile("mcp-demo")
public class McpDemoConfiguration {

    /** 演示脚本的目录。默认指向仓库里的 {@code examples/mcp}。 */
    static final String DIRECTORY_PROPERTY = "claw4j.mcp.demo.directory";

    static final String DEFAULT_DIRECTORY = "examples/mcp";

    @Bean
    @ConditionalOnProperty(prefix = "claw4j.mcp.demo", name = "demo-server", havingValue = "true")
    public McpServerEndpoint demoMcpEndpoint(
            @org.springframework.beans.factory.annotation.Value(
                    "${" + DIRECTORY_PROPERTY + ":" + DEFAULT_DIRECTORY + "}") String directory) {
        return endpoint("claw4j-demo", directory, "demo_mcp_server.py", ToolPermission.read());
    }

    @Bean
    @ConditionalOnProperty(prefix = "claw4j.mcp.demo", name = "github", havingValue = "true")
    public McpServerEndpoint githubMcpEndpoint(
            @org.springframework.beans.factory.annotation.Value(
                    "${" + DIRECTORY_PROPERTY + ":" + DEFAULT_DIRECTORY + "}") String directory) {
        return endpoint("claw4j-github", directory, "gh_mcp_server.py", ToolPermission.read());
    }

    /**
     * {@code himalaya} 那个：它导出 {@code mail_send}，所以登记的不是只读。
     *
     * <p>把这一条写错（写成 {@code ToolPermission.read()}）的后果是"能发邮件的工具
     * 按只读来鉴权"，而只读角色能调它——正是 ADR-0007 里那种"看着是读，其实能写"的口子。
     */
    @Bean
    @ConditionalOnProperty(prefix = "claw4j.mcp.demo", name = "mail", havingValue = "true")
    public McpServerEndpoint mailMcpEndpoint(
            @org.springframework.beans.factory.annotation.Value(
                    "${" + DIRECTORY_PROPERTY + ":" + DEFAULT_DIRECTORY + "}") String directory) {
        return endpoint("claw4j-mail", directory, "himalaya_mcp_server.py",
                ToolPermission.of("send_email"));
    }

    private static McpServerEndpoint endpoint(String name, String directory, String script,
                                              ToolPermission permission) {
        Path file = Path.of(directory).resolve(script).toAbsolutePath().normalize();
        return ProcessStdioMcpEndpoint.of(name, "python3", List.of(file.toString()), pathOnly())
                .endpoint(permission);
    }

    /** 只给 PATH：不给它，子进程连 python3 都找不到；给多了，应用的凭据就漏过去了。 */
    private static Map<String, String> pathOnly() {
        String path = System.getenv("PATH");
        return path == null ? Map.of() : Map.of("PATH", path);
    }
}
