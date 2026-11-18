package io.github.claw4j.capability.mcp;

import io.github.claw4j.domain.principal.ToolFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 清单：{@code claw4j.capability.mcp.servers.<名字>} 一段就是一个 server。
 *
 * <p>这一层做成配置而不是 bean：别人的 server 装在别人的机器上，接进来不该要求改源码再重新
 * 打包。配置化之后"接一个第三方 MCP server"就是写四行 yml，与 {@code claw4j.skills.directory}
 * 同一个取向——能力来源是运行时才知道的事。接哪几种传输仍然是编译期的事，见 {@link Transport}。
 *
 * <p>能看见哪些工具由工具清单那一层决定（{@code claw4j.tools.*}，票 03 / 07），
 * 这里只回答"接哪一个 server"：权限不再按 server 声明，也不按工具逐个覆盖——
 * 那是两套判定并存的老写法，ADR-0030 阶段 4 整块删掉了（{@code Server.tools} 随之删除）。
 * 现在要收窄就用清单里的 {@code deny} 点名工具名，要过滤 server 自己导出的那批就用
 * {@link Server#toolFilter}（存在性过滤，票 07）。
 *
 * <p>用 {@code @ConfigurationProperties} 而不是 {@code @Value}：这里是一串结构化条目，
 * 不是几个标量。{@code @Value} 表达不了"任意多个 server、每个都有自己的 argv 与环境变量"。
 */
@ConfigurationProperties("claw4j.capability.mcp")
public class McpServerProperties {

    /** 总开关。关掉它等于整层不存在，用来在排障时一次性排除 MCP。 */
    private boolean enabled = true;

    /**
     * 一次 MCP 请求（工具调用、列清单）的等待上限。
     *
     * <p>与 {@link #initializationTimeout} 分开：一次调用通常是几百毫秒，
     * 而握手要等启动器把包解析出来，量级差得远，用同一个值只能二选一——要么调用等太久，
     * 要么正常的 server 被判成连不上。
     */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /**
     * 握手（{@code initialize}）的等待上限。
     *
     * <p>默认给到 60 秒，是因为 {@code npx} / {@code uvx} 这类启动器要先解析包、
     * 可能需要访问 registry：本机实测 {@code npx -y @modelcontextprotocol/server-everything}
     * 在缓存已热时仍然要 50 秒才走完握手。SDK 的默认值是 20 秒，
     * 于是一个完全正常的 server 会以 {@code Client failed to initialize} 的形式被判成"配错了"。
     */
    private Duration initializationTimeout = Duration.ofSeconds(60);

    /**
     * 有序：启动日志与工具注册顺序都跟着它走。{@code LinkedHashMap} 是为了让
     * "配的顺序"＝"装配的顺序"，出问题时不至于每次重启报错顺序都不同。
     */
    private final Map<String, Server> servers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }

    public Map<String, Server> getServers() {
        return servers;
    }

    /**
     * 一个 MCP server 怎么被连上。
     *
     * @param transport 传输方式。写错一个不存在的值在绑定期就失败，不会悄悄退化成 stdio
     * @param command   stdio：起哪个程序。argv 数组的第一个元素，不经任何命令解释器（ADR-0002）
     * @param args      stdio：原样交给它的参数。空项会在起进程之前被拒绝
     * @param env       stdio：显式给出的环境变量，值是句柄不是真值（{@code secret:<名字>}；
     *                  非凭据常量写 {@code plain:<文本>}，裸写会被拒并点名到具体一行）；
     *                  同名键覆盖 {@code inheritEnv} 透传进来的值
     * @param inheritEnv stdio：从本进程按名字补进去哪些变量。默认只给 {@code PATH}——
     *                  不给它连启动器都找不到。注意这<b>不是白名单</b>：子进程仍会继承父进程的
     *                  其余变量（SDK 的传输不清空环境，见 {@code ProcessStdioMcpEndpoint} 类注释），
     *                  要隔离得靠部署层的容器策略
     * @param url       http / sse：base URI，例如 {@code https://mcp.example.com}
     * @param endpoint  http / sse：base URI 之后的路径。不填走 SDK 默认（{@code /mcp}、{@code /sse}）
     * @param headers   http / sse：每次请求都带的头，值是句柄，可以带前后缀
     *                  （{@code Authorization: "Bearer secret:deepwiki_token"}）；
     *                  真值在每次写头那一刻才换出来（P08）
     * @param toolFilter 这个 server 的哪些工具<b>存在</b>（ADR-0030 裁决二）：{@code include} 非空
     *                   则必须命中，{@code exclude} 命中即排除，条目支持 {@code *}。过滤发生在
     *                   "工具变成工具"之前，所以被排掉的工具不进清单、不进画像登记，
     *                   也不在 {@code toolNames()} 里——{@code allow} 加不回来
     * @param enabled   单个 server 的开关。默认开——写了配置就是要接它，关掉是例外
     */
    public record Server(@DefaultValue("stdio") Transport transport,
                         String command,
                         @DefaultValue List<String> args,
                         @DefaultValue Map<String, String> env,
                         @DefaultValue("PATH") List<String> inheritEnv,
                         String url,
                         String endpoint,
                         @DefaultValue Map<String, String> headers,
                         @DefaultValue ToolFilter toolFilter,
                         @DefaultValue("true") boolean enabled) {
    }

    /**
     * 支持的传输。<b>这是一个封闭集合，不是插件点</b>：加一种传输＝在
     * {@link McpEndpoints} 与 {@code http} 包里加一个实现，不是加一段配置。
     *
     * <p>{@code SSE} 是 MCP 早期那版 HTTP 传输（长连接推、另一个端点发），
     * 2025 版协议用 {@code HTTP}（Streamable HTTP）取代了它。留着 {@code SSE} 是因为
     * 现网的第三方 server 还有不少只给 {@code /sse}，接得上比"协议先进"更实用。
     */
    public enum Transport {
        STDIO,
        HTTP,
        SSE
    }
}
