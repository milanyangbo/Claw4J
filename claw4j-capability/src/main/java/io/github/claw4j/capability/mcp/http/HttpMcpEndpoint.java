package io.github.claw4j.capability.mcp.http;

import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.secret.SecretValue;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Objects;

/**
 * 一个<b>不需要起进程</b>的 MCP 端点：直接和对方的 HTTP 端点说 JSON-RPC。
 *
 * <p>这是协议的另一半。stdio 那种要求 server 和我们在同一台机器上、并且能被 fork；
 * 公网上的第三方服务（文档检索、SaaS 的开放能力）只能走 HTTP。
 * 两种传输对上是同一个接缝 {@link McpServerEndpoint#openTransport()}——
 * 上面的连接生命周期（会话丢失重开、工具清单 fail-fast）一行都不用改。
 *
 * <p><b>Streamable HTTP 与 SSE 的区别只在传输细节</b>：前者每个请求一个 POST，
 * 会话靠 {@code Mcp-Session-Id} 头（SDK 自己收发，我们不管）；后者是"开一条长连接收，
 * 另一个端点发"。协议版本 2025 起前者是标准，后者留着是为了接那些只给 {@code /sse} 的老 server。
 *
 * <p><b>凭据走 {@code headers}，值是句柄</b>（P08）：配置里写
 * {@code Authorization: "Bearer secret:deepwiki_token"}，真值在<b>每一次请求</b>写头那一刻才换出来
 * （见 {@link #requestCustomizer()}）。不能写成 {@code ${DEEPWIKI_TOKEN}}：Spring 在配置绑定期
 * 就把它解成真值，于是这个对象手里握着的是一把能活到进程结束的钥匙。
 */
public final class HttpMcpEndpoint {

    private final String name;

    private final String baseUri;

    private final String path;

    /** 每次请求都带的头：值是句柄，不是真值。 */
    private final Map<String, SecretValue> headers;

    private final SecretResolver secrets;

    private final boolean sse;

    private HttpMcpEndpoint(String name, String baseUri, String path, Map<String, SecretValue> headers,
                            SecretResolver secrets, boolean sse) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri").strip();
        this.path = path == null ? "" : path.strip();
        this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.sse = sse;
        if (!this.baseUri.startsWith("http://") && !this.baseUri.startsWith("https://")) {
            throw new IllegalArgumentException("MCP server " + name + " 的 url 不是 http(s) 地址：" + this.baseUri
                    + "。要走本地进程请用 transport: stdio；http 这一种连的是已经跑着的端点，不是文件路径。");
        }
        if (!this.path.isEmpty() && !this.path.startsWith("/")) {
            throw new IllegalArgumentException("MCP server " + name + " 的 endpoint 要以 / 开头：" + this.path
                    + "。它是 base url 之后的路径（例如 /mcp），写成绝对 URL 会被拼成两遍域名。");
        }
    }

    /**
     * @param name    server 名，只用于日志与报错
     * @param baseUri base URI，例如 {@code https://mcp.example.com}
     * @param path    路径，留空则用 SDK 的默认值（Streamable HTTP 是 {@code /mcp}，SSE 是 {@code /sse}）
     * @param headers 每次请求都带的头，值是句柄
     * @param secrets 句柄的解析器，只在写 header 时被用到
     * @param sse     true 走旧版 SSE 传输，false 走 Streamable HTTP
     */
    public static HttpMcpEndpoint of(String name, String baseUri, String path, Map<String, SecretValue> headers,
                                     boolean sse, SecretResolver secrets) {
        return new HttpMcpEndpoint(name, baseUri, path, headers, secrets, sse);
    }

    public String name() {
        return name;
    }

    /** 日志与断言用：路径可能带查询参数，凭据不在这里（在 headers 里，不打）。 */
    public String target() {
        return baseUri + path;
    }

    /** 端点本身：带上这一层要什么权限。权限在这里声明，与 stdio 那一路同一个口径。 */
    public McpServerEndpoint endpoint(ToolPermission permission) {
        return McpServerEndpoint.of(name, permission, this::openTransport);
    }

    /**
     * 端点 + 按工具覆盖的那张表。第三方服务常把只读查询与写动作放在同一个端点上，
     * 只按 server 声明时它们只能共用一个权限。
     */
    public McpServerEndpoint endpoint(ToolPermission permission, Map<String, ToolPermission> toolPermissions) {
        return McpServerEndpoint.of(name, permission, toolPermissions, this::openTransport);
    }

    /**
     * 造一条传输。**不在这里连**：连接由 {@code McpConnection} 在第一次要工具清单时发起，
     * 与会话丢失后的重开走同一条路。
     */
    public McpClientTransport openTransport() {
        if (sse) {
            HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(baseUri)
                    .jsonMapper(McpJsonDefaults.getMapper());
            if (!path.isEmpty()) {
                builder.sseEndpoint(path);
            }
            if (!headers.isEmpty()) {
                builder.httpRequestCustomizer(requestCustomizer());
            }
            return builder.build();
        }

        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(baseUri)
                .jsonMapper(McpJsonDefaults.getMapper());
        if (!path.isEmpty()) {
            builder.endpoint(path);
        }
        if (!headers.isEmpty()) {
            builder.httpRequestCustomizer(requestCustomizer());
        }
        return builder.build();
    }

    /**
     * 把配置里的头加进每一次请求。
     *
     * <p>用 SDK 给的 customizer 而不是自造一个 {@link HttpRequest.Builder}：那个 builder
     * 还要带 {@code Accept}、会话 id、协议版本这些由传输自己决定的东西，预置一份等于把
     * SDK 的握手细节抄一份出来，它一升级就对不上。
     *
     * <p><b>这里是 P08 三个出口里的第三个</b>：customizer 每次请求执行一次，句柄就在这个
     * 回调里换真值。于是凭据轮换不必重启进程，而我们的对象里从头到尾只有名字。
     *
     * <p><b>它是 public 的，为的是这条性质能被测到</b>（{@code McpServersConfigTest} 直接调它，
     * 断言请求头里是真值、而端点对象里仍是句柄）。它只读句柄、不吐真值，
     * 拿到它自己拼一条传输也不会多出泄漏面。
     */
    public McpSyncHttpClientRequestCustomizer requestCustomizer() {
        return (builder, method, endpoint, body, context) ->
                headers.forEach((name, value) -> builder.header(name, value.render(secrets)));
    }

    @Override
    public String toString() {
        return "HttpMcpEndpoint[" + name + " " + target() + (sse ? " sse" : " streamable-http") + "]";
    }
}
