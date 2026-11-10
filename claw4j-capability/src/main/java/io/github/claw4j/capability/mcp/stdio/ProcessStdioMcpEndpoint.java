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
 * 一个真的跨 OS 进程的 MCP 端点：起一个子进程，用它的 stdin/stdout 说 MCP 协议。
 *
 * <p>与测试源码里的进程内替身（{@code InProcessStdioMcpServer}）区别只有 server 在哪：
 * 那边是同一个 JVM 里的对象，这边是另一个进程——{@code gh}、{@code himalaya} 这类 CLI，
 * {@code claw4j-example-mcp-server} 那个 Java 版，或者任何语言写的 server。两者对上是同一个
 * 接缝 {@link McpServerEndpoint#openTransport()}，连接生命周期代码一行都不用改。
 *
 * <p>命令是结构化的，不是拼出来的：{@code command} 与 {@code args} 分别给出，交给 SDK 的
 * {@link StdioClientTransport}（内部用 {@code ProcessBuilder} 起进程），不经任何命令解释器。
 * ADR-0002 挡的是"Java 侧构造一条命令行交给解释器去解释"，1.x 就是这么做的
 * （{@code legacy/src/main/java/com/xteammors/openclaw/skills/GenericSkill.java:206}
 * 把参数原样拼进 argv 末尾）；这里只给出一个 argv 数组，谁都不解释它。
 *
 * <p>模型的输出进不了这条命令行：工具入参走 MCP 的 JSON-RPC 帧，由 SDK 序列化后写进 stdin，
 * 永远不会成为 argv 的一部分。
 *
 * <p>环境变量只给显式列出的那些：不传就是空表，子进程拿不到应用进程的环境，应用的模型 key、
 * 数据库口令都不会漏过去。需要 {@code PATH} / {@code HOME} 就在 {@code inherit-env} 里写明——
 * 给了什么在这份配置里看得见，而不是"继承了一切"这种说不清的状态。
 *
 * <p>凭据是句柄，真值在起进程那一刻才换出来（P08）：{@link #openTransport()} 是"给子进程的
 * 环境"这条路的唯一出口，之后真值进入子进程，这个对象里仍然只有名字。
 *
 * <p>每次 {@link #openTransport()} 起一个新进程，于是会话丢失后的重新初始化拿到的是一条
 * 干净的连接（{@code McpConnection} 会关掉旧的再要一条新的）。
 */
public final class ProcessStdioMcpEndpoint {

    /** 没有真值的解析器：三参 {@code of} 那条路不配任何 {@code env}，走到它就是配错了。 */
    private static final SecretResolver NO_SECRETS = name -> Optional.empty();

    private final String name;

    private final String command;

    private final List<String> args;

    /** 从父进程透传哪些变量名。值在出口现取，不留在这里。 */
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
     * 建一个 stdio 端点。命令、参数与透传变量的形状在这里校验。
     *
     * @param name server 名，只用于日志与报错
     * @param command 起哪个程序，argv 的第一个元素
     * @param args 原样交给它的参数，空项会被拒
     * @param inheritEnv 从父进程透传哪些变量名（默认只该有 {@code PATH}）
     * @param env 显式给出的变量，值是句柄；不传任何值时子进程没有环境变量（含 PATH）
     * @param secrets 句柄的解析器，只在 {@link #openTransport()} 里被用到
     * @return 端点
     */
    public static ProcessStdioMcpEndpoint of(String name, String command, List<String> args,
                                             List<String> inheritEnv, Map<String, SecretValue> env,
                                             SecretResolver secrets) {
        return new ProcessStdioMcpEndpoint(name, command, args, inheritEnv, env, secrets);
    }

    /** 不给环境变量的简版：子进程只拿 {@code PATH}，这是默认该有的样子。 */
    public static ProcessStdioMcpEndpoint of(String name, String command, List<String> args) {
        return of(name, command, args, List.of("PATH"), Map.of(), NO_SECRETS);
    }

    /** server 名，只用于日志与报错。 */
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
     * <p>权限在这里声明，因为"这个 server 能干什么"是接它进来的人才知道的事：
     * 同一个 {@code gh} 包，只读地查 issue 与能开 PR 是两种边界。
     * 声明成 {@link ToolPermission#read()} 不带表即"没有业务表权限"。
     *
     * @param permission 整条 server 的权限，没有按工具覆盖时所有工具都用它
     * @return 可交给 {@code McpConnection} 的端点
     */
    public McpServerEndpoint endpoint(ToolPermission permission) {
        return McpServerEndpoint.of(name, permission, this::openTransport);
    }

    /**
     * 端点 + 按工具覆盖的那张表。一个 server 同时导出"查"与"改"时，
     * 整条只给一个权限只能得到两种坏结果：权限放大，或者整条不可用。
     *
     * @param permission 没被逐个点名的工具用的权限
     * @param toolPermissions 键是工具名，值是它自己的权限
     * @return 可交给 {@code McpConnection} 的端点
     */
    public McpServerEndpoint endpoint(ToolPermission permission, Map<String, ToolPermission> toolPermissions) {
        return McpServerEndpoint.of(name, permission, toolPermissions, this::openTransport);
    }

    /**
     * 起一个新进程并连上它。
     *
     * <p>进程是懒起的：只有端点被连接（第一次列工具）或重连时才真的 fork，于是"配了一个
     * 永远不会被连的端点"不会在启动时留下一堆僵尸进程。
     *
     * <p>环境在 {@link #environment} 那一步才成形：句柄换真值、透传按名字现取。
     * 这是 P08 三个出口里的第二个。
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
     * <p>顺序是有意的。透传解决"连启动器都找不到"（{@code npx} / {@code uvx} 要靠 {@code PATH}，
     * 包缓存要靠 {@code HOME}）；显式给出的用来收紧或补一个父进程没有的键（比如只给子进程
     * 一份专用 token）。反过来先显式再透传，"写了"的那一份会被父进程的同名变量顶掉。
     *
     * <p>{@code parent} 是参数而不是直接读 {@code System.getenv()}：这一条要能测，
     * 而"从本进程真读一遍"测不了"父进程有 PATH 但没有 HOME"这种形状。
     *
     * <p>显式那一份的值是句柄，在这里才换成真值（{@link SecretValue#render}）。
     *
     * @param parent 父进程的环境，只在透传时被读
     * @param inheritEnv 要透传的变量名，空项一律拒绝
     * @param declared 显式给出的变量，值是句柄
     * @param secrets 句柄的解析器
     * @return 子进程能看到的全部变量
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
