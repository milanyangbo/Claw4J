package io.github.claw4j.capability.mcp;

import io.github.claw4j.capability.mcp.McpServerProperties.Permission;
import io.github.claw4j.capability.mcp.McpServerProperties.Server;
import io.github.claw4j.capability.mcp.McpServerProperties.Transport;
import io.github.claw4j.capability.mcp.stdio.ProcessStdioMcpEndpoint;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.secret.MissingSecretException;
import io.github.claw4j.secret.SecretResolver;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置里的 MCP server：一段 YAML 变成什么、配错了在哪一步红。
 *
 * <p>这里<b>不连任何 server</b>，也不起任何进程：断言的是"配置翻译出来的形状"与
 * "配错时的失败"。真连第三方要出网、要装 Node，那是演示（{@code application-local.yml} 里
 * 那段注释着的配置）的活，不是门禁的活——一条每天跑、结果取决于公网状态的用例，红了也说明不了是谁的问题。
 *
 * <p>P08 之后这里多了一类断言：{@code env} 与 {@code headers} 里写的是句柄，配了真值写法
 * （裸写明文）或句柄取不到真值，都在装配期红，且报错点名到具体那一行。
 */
class McpServersConfigTest {

    /** 一份够用的假环境：句柄名 → 真值。真值故意用一眼能认出的串，好断言它没被打印出去。 */
    private static final SecretResolver SECRETS = name -> Optional.ofNullable(
            Map.of("mcp_token", "token-live-1234", "home_dir", "/tmp/isolated").get(name));

    @Test
    @DisplayName("stdio：命令与参数原样成为 argv，不经任何解释器")
    void stdioKeepsTheArgvExactlyAsWritten() {
        Server server = stdio("npx", List.of("-y", "@modelcontextprotocol/server-everything"),
                List.of("PATH"), Map.of());

        assertThat(McpEndpoints.stdio("everything", server, SECRETS).commandLine())
                .isEqualTo("npx -y @modelcontextprotocol/server-everything");
    }

    @Test
    @DisplayName("环境变量：先按名字透传，显式写的那份再覆盖上去")
    void explicitEnvironmentOverridesWhatWasInherited() {
        Map<String, String> parent = Map.of("PATH", "/usr/bin", "HOME", "/Users/someone");
        Server server = stdio("npx", List.of(), List.of("PATH", "HOME"), Map.of("HOME", "secret:home_dir"));

        Map<String, String> env = environment(parent, server);

        assertThat(env)
                .as("PATH 透传、HOME 被显式值顶掉：反过来的顺序会让“我明明写了”的那份失效")
                .containsEntry("PATH", "/usr/bin")
                .containsEntry("HOME", "/tmp/isolated");
    }

    @Test
    @DisplayName("透传一个父进程没有的变量名：它只是不出现，不是空字符串")
    void missingParentVariableIsAbsentRatherThanBlank() {
        Server server = stdio("npx", List.of(), List.of("PATH", "MCP_TOKEN"), Map.of());

        assertThat(environment(Map.of("PATH", "/usr/bin"), server))
                .as("空值会被子进程当成“有这个变量但它是空的”，与“没有这个变量”是两种下游行为")
                .containsOnlyKeys("PATH");
    }

    @Test
    @DisplayName("inherit-env 里出现空项按配置错误失败：多半是 YAML 多了一个破折号")
    void blankInheritEntryIsRejected() {
        Server server = stdio("npx", List.of(), List.of("PATH", " "), Map.of());

        assertThatThrownBy(() -> environment(Map.of("PATH", "/usr/bin"), server))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inherit-env 里有空项");
    }

    @Test
    @DisplayName("env 的值只能是句柄：裸写明文与句柄缺失都在装配期红，报错点名到那一行")
    void envValueMustBeAHandle() {
        Server plain = stdio("npx", List.of(), List.of("PATH"), Map.of("MCP_TOKEN", "token-live-1234"));

        assertThatThrownBy(() -> McpEndpoints.stdio("demo", plain, SECRETS))
                .as("报错里不能有原值：写错位的那一行很可能就是真凭据")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claw4j.capability.mcp.servers.demo.env.MCP_TOKEN")
                .hasMessageNotContaining("token-live-1234");

        Server missing = stdio("npx", List.of(), List.of("PATH"), Map.of("MCP_TOKEN", "secret:absent"));

        assertThatThrownBy(() -> McpEndpoints.stdio("demo", missing, SECRETS))
                .as("句柄写了却没有真值：启动期就失败，别等第一次调用")
                .isInstanceOf(MissingSecretException.class)
                .hasMessageContaining("secret:absent")
                .hasMessageContaining("claw4j.capability.mcp.servers.demo.env.MCP_TOKEN");
    }

    @Test
    @DisplayName("headers 的值是句柄，真值只在出口换：端点手里是名字，请求头上是真值")
    void headersKeepHandlesUntilTheExit() {
        Server server = http("https://mcp.example.com", null,
                Map.of("Authorization", "Bearer secret:mcp_token"));

        var endpoint = McpEndpoints.http("demo", server, false, SECRETS);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.target() + "/mcp"));

        // 出口那一行：每次请求，customizer 现取现换。这条断言走的是生产同一个回调。
        endpoint.requestCustomizer().customize(request, "POST", URI.create(endpoint.target() + "/mcp"),
                "{}", McpTransportContext.EMPTY);

        assertThat(request.build().headers().firstValue("Authorization"))
                .as("配置里只有句柄，到了请求头上才有真值")
                .hasValue("Bearer token-live-1234");
    }

    @Test
    @DisplayName("http：连的是端点地址，走的是 Streamable HTTP，默认路径 /mcp 由 SDK 决定")
    void httpUsesTheStreamableTransport() {
        Server server = http("https://mcp.deepwiki.com", null, Map.of());

        var endpoint = McpEndpoints.http("deepwiki", server, false, SECRETS);

        assertThat(endpoint.target()).isEqualTo("https://mcp.deepwiki.com");
        assertThat(endpoint.openTransport())
                .as("不填 endpoint 就不调 SDK 的 endpoint()，让默认值只有一个来源")
                .isInstanceOf(HttpClientStreamableHttpTransport.class);
    }

    @Test
    @DisplayName("sse：同一条配置换个 transport 值，换成旧版 HTTP 传输")
    void sseSwitchesTheTransport() {
        Server server = http("https://mcp.example.com", "/sse", Map.of());

        assertThat(McpEndpoints.http("legacy", server, true, SECRETS).openTransport())
                .isInstanceOf(HttpClientSseClientTransport.class);
    }

    @Test
    @DisplayName("权限默认只读且不带任何表：外部能力碰不到业务库")
    void defaultPermissionIsReadWithoutTables() {
        McpServerEndpoint endpoint =
                McpEndpoints.of("deepwiki", http("https://mcp.deepwiki.com", null, Map.of()), SECRETS);

        assertThat(endpoint.name()).isEqualTo("deepwiki");
        assertThat(endpoint.permission())
                .as("表清单为空时鉴权只看动作，于是只读身份能调它、但拿不到任何业务表")
                .isEqualTo(ToolPermission.read());
    }

    @Test
    @DisplayName("声明了 send_email 的 server 就按 send_email 鉴权，不会退化成只读")
    void declaredActionReachesTheDomainPermission() {
        Server server = new Server(Transport.STDIO, "python3", List.of("mail.py"), Map.of(),
                List.of("PATH"), null, null, Map.of(), new Permission("send_email", List.of("customer")),
                Map.of(), true);

        assertThat(McpEndpoints.of("mail", server, SECRETS).permission())
                .isEqualTo(ToolPermission.of("send_email", "customer"));
    }

    @Test
    @DisplayName("stdio 没写 command：启动就失败，消息里带 server 名")
    void stdioWithoutCommandFailsOnTheSpot() {
        Server server = stdio("  ", List.of(), List.of("PATH"), Map.of());

        assertThatThrownBy(() -> McpEndpoints.stdio("half-done", server, SECRETS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP server half-done")
                .hasMessageContaining("没有 command");
    }

    @Test
    @DisplayName("http 没写 url：启动就失败，消息里带 server 名")
    void httpWithoutUrlFailsOnTheSpot() {
        Server server = http(null, null, Map.of());

        assertThatThrownBy(() -> McpEndpoints.http("nowhere", server, false, SECRETS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP server nowhere")
                .hasMessageContaining("没有 url");
    }

    @Test
    @DisplayName("url 写成文件路径或少了协议：拒绝，而不是等到连接超时")
    void nonHttpUrlIsRejected() {
        assertThatThrownBy(
                () -> McpEndpoints.http("typo", http("mcp.deepwiki.com", null, Map.of()), false, SECRETS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 http(s) 地址");
    }

    @Test
    @DisplayName("enabled: false 的条目只是不接，不是错误")
    void disabledServerIsSkipped() {
        McpServerProperties properties = new McpServerProperties();
        properties.getServers().put("on", http("https://mcp.deepwiki.com", null, Map.of()));
        properties.getServers().put("off", new Server(Transport.HTTP, null, List.of(), Map.of(), List.of("PATH"),
                "https://mcp.example.com", null, Map.of(), new Permission("read", List.of()), Map.of(), false));

        assertThat(McpEndpoints.from(properties, SECRETS))
                .extracting(McpServerEndpoint::name).containsExactly("on");
    }

    @Test
    @DisplayName("一条配置都没有时不注册 MCP 这一层：条件看配置，不看容器里已有什么")
    void noConfigurationMeansNoMcpBeans() {
        runner().run(context -> assertThat(context)
                .as("没配 server 却注册一堆空 bean，等于让“没这一层”与“配了但没生效”长得一样")
                .doesNotHaveBean(McpToolProvider.class)
                .doesNotHaveBean(McpServerProperties.class));
    }

    @Test
    @DisplayName("配了一个不存在的命令：应用照常起，那个 server 记 DOWN 等重试（票 03）")
    void unreachableServerKeepsTheApplicationUp() {
        runner()
                .withPropertyValues(
                        "claw4j.capability.mcp.servers.broken.command=/nonexistent/claw4j-no-such-binary",
                        // 生产默认是 30 秒 / 60 秒；这里给 2 秒，一条断言不必等满 60 秒才知道它连不上。
                        // 顺带证明这两项真的可配——上面那条断言核对的正是这个能力。
                        "claw4j.capability.mcp.request-timeout=2s",
                        "claw4j.capability.mcp.initialization-timeout=2s")
                .run(context -> {
                    assertThat(context)
                            .as("对端重启 40 秒不该换来一次全站不可用：那在 K8s 上是 CrashLoopBackOff，"
                                    + "连 HTTP 通道一起下线，而重试本来由编排层替我们做，代价是整个进程")
                            .hasNotFailed();
                    assertThat(context).hasSingleBean(McpToolProvider.class);
                    assertThat(context.getBean(McpToolProvider.class).status())
                            .singleElement()
                            .satisfies(snapshot -> {
                                assertThat(snapshot.name()).isEqualTo("broken");
                                assertThat(snapshot.status()).isEqualTo(McpServerStatus.DOWN);
                                assertThat(snapshot.lastError())
                                        .as("日志里要认得出是哪一个 server：配了多个时只报一句 "
                                                + "IOException，等于让人在 yml 里二分")
                                        .contains("broken");
                            });
                });
    }

    @Test
    @DisplayName("按工具覆盖：点名的用覆盖，没点名的仍走 server 那一条")
    void perToolPermissionOverridesOnlyTheNamedTool() {
        Server server = new Server(Transport.HTTP, null, List.of(), Map.of(), List.of("PATH"),
                "https://mcp.example.com", null, Map.of(),
                new Permission("read", List.of()),
                Map.of("send_email", new Permission("send_email", List.of("customer"))), true);

        McpServerEndpoint endpoint = McpEndpoints.of("mail", server, SECRETS);

        assertThat(endpoint.permissionFor("list_inbox"))
                .as("没点名的工具不能被旁边的覆盖带跑")
                .isEqualTo(ToolPermission.read());
        assertThat(endpoint.permissionFor("send_email"))
                .as("混装的 server 只有这一层能让写动作与读动作各用各的权限")
                .isEqualTo(ToolPermission.of("send_email", "customer"));
        assertThat(endpoint.permission()).isEqualTo(ToolPermission.read());
    }

    @Test
    @DisplayName("覆盖表里写了空的工具名：启动就失败，多半是 YAML 缩进错了")
    void blankToolNameInOverridesIsRejected() {
        Server server = new Server(Transport.HTTP, null, List.of(), Map.of(), List.of("PATH"),
                "https://mcp.example.com", null, Map.of(), new Permission("read", List.of()),
                Map.of(" ", new Permission("send_email", List.of())), true);

        assertThatThrownBy(() -> McpEndpoints.of("mail", server, SECRETS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP server mail")
                .hasMessageContaining("空的工具名");
    }

    @Test
    @DisplayName("YAML 里那三行真的绑得进 tools：嵌套的 map-of-record 不是靠“看着对”")
    void perToolPermissionsBindFromProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "claw4j.capability.mcp.servers.mail.permission.action", "read",
                "claw4j.capability.mcp.servers.mail.tools.send_email.action", "send_email",
                "claw4j.capability.mcp.servers.mail.tools.send_email.tables[0]", "customer",
                "claw4j.capability.mcp.initialization-timeout", "90s")));

        McpServerProperties properties = Binder.get(environment)
                .bind("claw4j.capability.mcp", Bindable.of(McpServerProperties.class))
                .get();

        Server mail = properties.getServers().get("mail");
        assertThat(mail.tools()).containsOnlyKeys("send_email");
        assertThat(mail.tools().get("send_email"))
                .isEqualTo(new Permission("send_email", List.of("customer")));
        assertThat(mail.permission())
                .as("没写 permission 时默认只读且不碰表——覆盖表只动被点名的那个工具")
                .isEqualTo(new Permission("read", List.of()));
        assertThat(properties.getInitializationTimeout())
                .as("接了慢启动的 server 时，这一项要能改，不必重新打包")
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("握手超时默认 60 秒：npx 类启动器实测要 50 秒，SDK 的 20 秒会把正常 server 判成连不上")
    void initializationTimeoutIsGenerousByDefault() {
        McpServerProperties properties = new McpServerProperties();

        assertThat(properties.getInitializationTimeout())
                .as("npx -y 在本机缓存已热时仍要 50 秒才走完握手，默认值必须留出余量")
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getRequestTimeout())
                .as("单次调用与握手是两个量级，不能共用一个值")
                .isEqualTo(Duration.ofSeconds(30));
    }

    /** 出口那一行：把配置里的 env 段落翻成"子进程真的能看到的变量"。 */
    private static Map<String, String> environment(Map<String, String> parent, Server server) {
        return ProcessStdioMcpEndpoint.environment(parent, server.inheritEnv(),
                McpEndpoints.handleValues("test", "env", server.env(), SECRETS), SECRETS);
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfiguredMcpServersAutoConfiguration.class));
    }

    private static Server stdio(String command, List<String> args, List<String> inheritEnv, Map<String, String> env) {
        return new Server(Transport.STDIO, command, args, new LinkedHashMap<>(env), inheritEnv,
                null, null, Map.of(), new Permission("read", List.of()), Map.of(), true);
    }

    private static Server http(String url, String path, Map<String, String> headers) {
        return new Server(Transport.HTTP, null, List.of(), Map.of(), List.of("PATH"),
                url, path, headers, new Permission("read", List.of()), Map.of(), true);
    }
}
