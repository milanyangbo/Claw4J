package io.github.claw4j.capability.mcp.stdio;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.secret.SecretValue;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个<b>真的跨 OS 进程</b>的 MCP 端点：起一个子进程，用它的 stdin/stdout 说 MCP 协议。
 *
 * <p>它与进程内那种替身（测试源码里的 {@code InProcessStdioMcpServer}）的区别只有"server 在哪儿"：
 * 那边是同一个 JVM 里的一个对象，这边是另一个进程——Python 包 {@code gh}、包 {@code himalaya}，
 * {@code claw4j-example-mcp-server} 那个 Java 版，或者任何语言写的 server。
 * 两者对上是同一个接缝
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
 * 就在 {@code inherit-env} 里写明——"给出去了什么"在这份配置里是看得见的，
 * 而不是"继承了一切"这种说不清的状态。
 *
 * <p><b>凭据是句柄，真值在起进程那一刻才换出来</b>（P08）：{@link #openTransport()} 是
 * "给子进程的环境"这条路的唯一出口，句柄在这里才变成值。之后这个值进入子进程的环境，
 * 我们的对象里仍然只有名字——配置、日志、审计里都不会出现它。
 *
 * <p>每次 {@link #openTransport()} 起一个新进程，于是"会话丢失重新初始化"
 * 拿到的是一条干净的连接（{@code McpConnection} 会关掉旧的再要一条新的）。
 */
public final class ProcessStdioMcpEndpoint {

    /** 没有真值的解析器：三参 {@code of} 那条路不配任何 {@code env}，走到它就是配错了。 */
    private static final SecretResolver NO_SECRETS = name -> Optional.empty();

    private final String name;

    private final String command;

    private final List<String> args;

    /** 从父进程透传哪些变量<b>名</b>。值在出口现取，不留在这里。 */
    private final List<String> inheritEnv;

    /** 显式给出的变量：值是句柄，不是真值。 */
    private final Map<String, SecretValue> env;

    private final SecretResolver secrets;

    private ProcessStdioMcpEndpoint(String name, String command, List<String> args, List<String> inheritEnv,
                                    Map<String, SecretValue> env, SecretResolver secrets) {
        this.name = Objects.requireNonNull(name, "name");
        this.command = Objects.requireNonNull(command, "command").strip();
        this.args = List.copyOf(Objects.requireNonNull(args, "args"));
        this.inheritEnv = List.copyOf(Objects.requireNonNull(inheritEnv, "inheritEnv"));
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
        this.secrets = Objects.requireNonNull(secrets, "secrets");
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

    /**
     * @param inheritEnv 从父进程透传哪些变量名（默认只该有 {@code PATH}）
     * @param env        显式给出的变量，值是句柄；不传任何值时子进程没有环境变量（含 PATH）
     * @param secrets    句柄的解析器，只在 {@link #openTransport()} 里被用到
     */
    public static ProcessStdioMcpEndpoint of(String name, String command, List<String> args,
                                             List<String> inheritEnv, Map<String, SecretValue> env,
                                             SecretResolver secrets) {
        return new ProcessStdioMcpEndpoint(name, command, args, inheritEnv, env, secrets);
    }

    /** 不给环境变量的简版：子进程拿不到应用进程的环境，这是默认该有的样子。 */
    public static ProcessStdioMcpEndpoint of(String name, String command, List<String> args) {
        return of(name, command, args, List.of("PATH"), Map.of(), NO_SECRETS);
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
     * 端点 + 按工具覆盖的那张表。一个 server 同时导出"查"与"改"时，
     * 整条只给一个权限只能得到两种坏结果：权限放大，或者整条不可用。
     */
    public McpServerEndpoint endpoint(ToolPermission permission, Map<String, ToolPermission> toolPermissions) {
        return McpServerEndpoint.of(name, permission, toolPermissions, this::openTransport);
    }

    /**
     * 起一个新进程并连上它。
     *
     * <p>进程是<b>懒起</b>的：只有端点被连接（第一次列工具）或重连时才会真的 fork，
     * 于是"配了一个永远不会被连的端点"不会在启动时留下一堆僵尸进程。
     *
     * <p>环境在<b>这一行</b>才成形：句柄换真值、透传按名字现取。这是 P08 三个出口里的第二个。
     */
    public McpClientTransport openTransport() {
        ServerParameters parameters = ServerParameters.builder(command)
                .args(args)
                .env(environment(System.getenv(), inheritEnv, env, secrets))
                .build();
        return new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
    }

    /**
     * 子进程拿到的环境变量：先按名字从 {@code parent} 透传，再用显式给出的覆盖。
     *
     * <p>顺序是有意的。透传解决的是"连启动器都找不到"（{@code npx} / {@code uvx} 要靠
     * {@code PATH}，包缓存要靠 {@code HOME}）；显式给出的那些用来<b>收紧</b>或补一个
     * 父进程没有的键（比如只给子进程一份专用 token）。反过来先显式再透传，会让
     * "我明明写了"的那一份被父进程的同名变量顶掉。
     *
     * <p>{@code parent} 是参数而不是直接读 {@code System.getenv()}：这一条要能测，
     * 而"从本进程真读一遍"是测不了"父进程有 PATH 但没有 HOME"这种形状的。
     *
     * <p>显式那一份的值是句柄，在这里才换成真值（{@link SecretValue#render}）。
     */
    public static Map<String, String> environment(Map<String, String> parent, List<String> inheritEnv,
                                                  Map<String, SecretValue> declared, SecretResolver secrets) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : inheritEnv) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("MCP server 的 inherit-env 里有空项：" + inheritEnv
                        + "。空的名字透传不了任何东西，多半是 YAML 里多了一个破折号。");
            }
            String value = parent.get(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        declared.forEach((key, value) -> env.put(key, value.render(secrets)));
        return env;
    }
}
