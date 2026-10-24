package io.github.claw4j.capability.mcp.stdio;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个<b>真的跨 OS 进程</b>的 MCP 端点：起一个子进程，用它的 stdin/stdout 说 MCP 协议。
 *
 * <p>它与 {@link InProcessStdioMcpServer} 的区别只有"server 在哪儿"：
 * 那边是同一个 JVM 里的一个对象，这边是另一个进程（Python 包 {@code gh}、包 {@code himalaya}，
 * 或者任何语言写的 server）。两者对上是同一个接缝
 * {@link McpServerEndpoint#openTransport()}，连接生命周期代码一行都不用改——
 * 这条接缝是 02 号票刻意留下的，本类只是把它接到真实进程边界上。
 *
 * <p><b>命令是结构化的，不是拼出来的</b>：{@code command} 与 {@code args} 分别给出，
 * 交给 SDK 的 {@link StdioClientTransport}（它内部用 {@code ProcessBuilder} 起进程），
 * <b>不经任何命令解释器</b>。这是它与 ADR-0002 禁止的那种写法的界线所在：
 * ADR-0002 挡的是"Java 侧构造一条命令行交给解释器去解释"（1.x 就是这么做的），
 * 而这里 Java 侧只给出一个 argv 数组，谁都不解释它。
 *
 * <p>更要紧的一条是<b>模型的输出进不了这条命令行</b>：工具的入参走 MCP 的 JSON-RPC 帧，
 * 由 SDK 序列化后写进 stdin，永远不会成为 argv 的一部分。
 * 1.x 把用户自然语言当 argv 传给 {@code .py}（{@code GenericSkill}），
 * 那才是把命令行交给模型的写法；换成 MCP 之后这条路在协议上就不存在了。
 *
 * <p><b>环境变量只给显式列出的那些</b>：不传就是空表，子进程拿不到应用进程的环境，
 * 于是应用的模型 key、数据库口令都不会漏过去。需要 {@code PATH} / {@code HOME}
 * 就在 {@code env} 里写明——"给出去了什么"在这份配置里是看得见的，
 * 而不是"继承了一切"这种说不清的状态。
 *
 * <p>每次 {@link #openTransport()} 起一个新进程，于是"会话丢失重新初始化"
 * 拿到的是一条干净的连接（{@code McpConnection} 会关掉旧的再要一条新的）。
 */
public final class ProcessStdioMcpEndpoint {

    private final String name;

    private final String command;

    private final List<String> args;

    private final Map<String, String> env;

    private ProcessStdioMcpEndpoint(String name, String command, List<String> args, Map<String, String> env) {
        this.name = Objects.requireNonNull(name, "name");
        this.command = Objects.requireNonNull(command, "command").strip();
        this.args = List.copyOf(Objects.requireNonNull(args, "args"));
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
        if (this.command.isEmpty()) {
            throw new IllegalArgumentException("MCP server " + name + " 的 command 是空的："
                    + "没有要起的程序，也就没有 server。命令写错在这里就失败，"
                    + "不要等到连接超时才暴露。");
        }
        for (String arg : this.args) {
            if (arg == null || arg.isBlank()) {
                throw new IllegalArgumentException("MCP server " + name + " 的参数里有空项：" + this.args
                        + "。空参数会被当成“少了一个参数”，而报出来的错在 server 那一侧，"
                        + "离这里隔着一层进程边界。");
            }
        }
    }

    /** @param env 子进程拿到的环境；不传任何值时子进程没有环境变量（含 PATH） */
    public static ProcessStdioMcpEndpoint of(String name, String command, List<String> args,
                                            Map<String, String> env) {
        return new ProcessStdioMcpEndpoint(name, command, args, env);
    }

    /** 不给环境变量的简版：子进程拿不到应用进程的环境，这是默认该有的样子。 */
    public static ProcessStdioMcpEndpoint of(String name, String command, List<String> args) {
        return of(name, command, args, Map.of());
    }

    public String name() {
        return name;
    }

    /** 命令与参数（不含环境）：日志与断言用，环境里可能有值不该被打印的东西。 */
    public String commandLine() {
        return command + " " + String.join(" ", args);
    }

    /**
     * 端点本身：带上这一层要什么权限。
     *
     * <p>权限在这里声明，是因为"这个 server 能干什么"是<b>接它进来的人</b>才知道的事：
     * 同一个 {@code gh} 包，只读地查 issue 与能开 PR 是两种边界。
     * 声明成 {@link ToolPermission#read()} 不带表即"没有业务表权限"。
     */
    public McpServerEndpoint endpoint(ToolPermission permission) {
        return McpServerEndpoint.of(name, permission, this::openTransport);
    }

    /**
     * 起一个新进程并连上它。
     *
     * <p>进程是<b>懒起</b>的：只有端点被连接（第一次列工具）或重连时才会真的 fork，
     * 于是"配了一个永远不会被连的端点"不会在启动时留下一堆僵尸进程。
     */
    public McpClientTransport openTransport() {
        ServerParameters parameters = ServerParameters.builder(command)
                .args(args)
                .env(env)
                .build();
        return new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
    }
}
