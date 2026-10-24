package io.github.claw4j.agent.advisor;

import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.capability.builtin.sales.QueryResult;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.github.claw4j.capability.builtin.system.SystemStatusAutoConfiguration;
import io.github.claw4j.capability.mcp.McpConnection;
import io.github.claw4j.capability.mcp.McpServerEndpoint;
import io.github.claw4j.capability.mcp.McpToolAutoConfiguration;
import io.github.claw4j.capability.mcp.McpToolProvider;
import io.github.claw4j.capability.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.capability.script.ScriptPackageAutoConfiguration;
import io.github.claw4j.capability.skill.SkillToolAutoConfiguration;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权 Advisor：一次工具调用能不能发生由 Principal 决定，不由模型决定（ADR-0007）。
 *
 * <p>两个层次合在一份文件里，因为判据是同一句话，只是问法不同：
 * <ul>
 *   <li><b>组件级</b>（前七条）：模型这一环由测试构造，问的是"该拒的拒了没有、
 *       拒了之后工具到底跑没跑"。断言落在计数器上，不是 Advisor 自己声明——
 *       "跑了但结果被丢掉"与"根本没跑"在审计上是两回事。
 *   <li><b>装配级</b>（后三条）：三层来源同时启用时，问的是"每张权限表都在不在"。
 *       这一层的失效姿势是<b>某一张表没被注册</b>——工具在清单里、模型看得见、
 *       但一律按"未登记"被拒，症状是"它忽然不会用某个工具了"。
 * </ul>
 *
 * <p><b>都不经过模型（ADR-0011）。</b>原来这些用例靠脚本化假模型"让模型声明要调某个工具"，
 * 换真模型之后那件事做不到了——真模型不会配合去调一个它没权限的工具，更不会精确地只调一次。
 * 所以模型那一环由测试直接构造：一个带 {@code toolCalls} 的 {@code AssistantMessage}，
 * 等价于"模型这一轮说要调这些工具"。**丢掉的只是"模型会自己发起调用"这一层，
 * 组件全是真的**：真的 {@link AuthorizationAdvisor}、真的 {@link ToolCallingManager}、
 * 真的计数工具。
 */
class AuthorizationTest {

    private static final String QUESTION = "上周华东区成交额多少";

    private static final String SALES_TOOL = "querySales";

    private static final String MAIL_TOOL = "sendMail";

    private static final String EXPORT_TOOL = "exportData";

    /** 只读角色：能读、能用 Skill 与委派、能看运行时状态，但跑不了脚本。 */
    private static final Principal READ_ONLY = Principal.of("u", "read-only",
            Set.of("*"), Set.of("read", "use_skill", "delegate_task", "read_system"));

    private static final Principal ADMIN = Principal.of("u", "admin",
            Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

    // ------------------------------------------------------------ 组件级：判在工具跑没跑

    @Test
    @DisplayName("越权时这一轮被换成拒绝，而且工具一次都没跑")
    void 越权时工具调用被换成拒绝() {
        CountingTools tools = new CountingTools();
        ChatClientResponse out = advise(guest(), tools, SALES_TOOL);

        assertThat(textOf(out)).startsWith(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(out.chatResponse().getResults().get(0).getOutput().getToolCalls()).isEmpty();
        // 真凭实据：替身工具一次都没被调用（不是"调用了但结果被丢掉"）
        assertThat(tools.salesCalls()).isZero();
    }

    @Test
    @DisplayName("有权限时放行，工具真的被执行了一次")
    void 有权限时工具真的跑了() {
        CountingTools tools = new CountingTools();
        ChatClientResponse out = advise(Principal.readOnly("u-readonly"), tools, SALES_TOOL);

        assertThat(textOf(out)).doesNotStartWith(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(out.chatResponse().getResults().get(0).getOutput().getToolCalls()).isNotEmpty();
        assertThat(tools.salesCalls()).isEqualTo(1);
        // 有副作用那个没被带到这一轮里
        assertThat(tools.mailCalls()).isZero();
    }

    /**
     * 拒绝话术要说清是哪一条不满足（动作还是表），否则运营只能直接给个 "*"。
     * guest 能读 region、读不到 sales_order，所以拒绝里必须点名那张表。
     */
    @Test
    @DisplayName("拒绝话术说清是动作还是表不满足")
    void 拒绝话术点名是哪一条不满足() {
        CountingTools tools = new CountingTools();
        ChatClientResponse out = advise(guest(), tools, SALES_TOOL);

        assertThat(textOf(out))
                .contains("guest")
                .contains("sales_order");
        assertThat(tools.salesCalls()).isZero();
    }

    @Test
    @DisplayName("没登记过权限的工具按越权处理")
    void 没登记权限的工具一律拒绝() {
        CountingTools tools = new CountingTools();
        // exportData 从没在 registry 里登记过：没人评估过它会碰到什么
        ChatClientResponse out = advise(Principal.readOnly("u-readonly"), tools, EXPORT_TOOL);

        assertThat(textOf(out))
                .startsWith(AuthorizationAdvisor.DENIAL_PREFIX)
                .contains(EXPORT_TOOL);
        assertThat(tools.exportCalls()).isZero();
    }

    @Test
    @DisplayName("只读角色用不了有副作用的动作")
    void 只读角色发不出邮件() {
        CountingTools tools = new CountingTools();
        ChatClientResponse out = advise(Principal.readOnly("u-readonly"), tools, MAIL_TOOL);

        assertThat(textOf(out))
                .startsWith(AuthorizationAdvisor.DENIAL_PREFIX)
                .contains("sendMail");
        assertThat(tools.mailCalls()).isZero();
    }

    @Test
    @DisplayName("流式路径上越权被当场掐断，且工具没跑")
    void 流式路径同样拦得住() {
        CountingTools tools = new CountingTools();

        ChatClientResponse single = AuthorizationAdvisorHolder.advisor()
                .adviseStream(request(guest()), ChainStubs.streamReturning(
                        List.of(ChainStubs.responseRequesting(SALES_TOOL))))
                .blockLast();

        // 这一步不能省：省了的话计数工具就从来没接进链路，salesCalls() 恒为 0，
        // "工具没跑"这条断言也就永远绿——而它正是这条用例要说的事。
        execute(tools, single);

        assertThat(textOf(single)).startsWith(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(ChainStubs.toolCallsOf(single)).isEmpty();
        assertThat(tools.salesCalls()).isZero();
    }

    @Test
    @DisplayName("摆在工具循环内部：每次迭代都要过一遍")
    void order排在工具循环内部() {
        assertThat(new AuthorizationAdvisor(List.of()).getOrder())
                .isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    // -------------------------------------------------------- 装配级：判在每张表都在不在

    @Test
    @DisplayName("三层都启用时，每个工具都为 admin 放行：没有漏登记的")
    void 每个工具都登记了权限(@TempDir Path root) throws Exception {
        Path scripts = packageDirectory(root.resolve("scripts"));
        Path skills = skillDirectory(root.resolve("skills"));

        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("layer-two",
                new LayerTwo())) {
            context(scripts, skills, server).run(context -> {
                List<String> names = toolNames(context);
                assertThat(names).contains("querySales", "systemStatus", "layerTwoProbe",
                        "demoPackage", "Skill");

                AuthorizationAdvisor advisor = advisor(context);
                for (String name : names) {
                    assertThat(ChainStubs.textOf(advisor.adviseCall(request(ADMIN),
                            ChainStubs.callReturning(ChainStubs.responseRequesting(name)))))
                            .as("%s 必须已登记权限：漏登记的后果是拒绝，"
                                    + "而它会表现为“模型忽然不用它了”", name)
                            .doesNotContain(AuthorizationAdvisor.DENIAL_PREFIX);
                }
            });
        }
    }

    /**
     * 漏登记的后果是拒绝：拿掉脚本层那张表，脚本工具就从"能调"变成"未登记"。
     *
     * <p>这一条是上一条的反向证明——只测"都在"的话，"都在"可能是因为压根没有工具，
     * 也可能因为拒绝话术被改成了空。这里把一张表拿掉，看它会不会红。
     */
    @Test
    @DisplayName("拿掉一层的权限表，那一层的工具立刻按未登记被拒")
    void 漏登记就是拒绝(@TempDir Path root) throws Exception {
        Path scripts = packageDirectory(root.resolve("scripts"));
        Path skills = skillDirectory(root.resolve("skills"));

        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("layer-two",
                new LayerTwo())) {
            context(scripts, skills, server).run(context -> {
                List<ToolPermissionRegistry> withoutScriptLayer = registries(context).stream()
                        .filter(registry -> registry.permissionFor("demoPackage").isEmpty())
                        .toList();
                AuthorizationAdvisor advisor = new AuthorizationAdvisor(withoutScriptLayer);

                assertThat(ChainStubs.textOf(advisor.adviseCall(request(ADMIN),
                        ChainStubs.callReturning(ChainStubs.responseRequesting("demoPackage")))))
                        .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                        .contains("未登记权限");
            });
        }
    }

    /**
     * 动作那一条也要分层：只读角色看得见脚本这个工具（它登记了），但调不动（没有 run_script）。
     *
     * <p>与"未登记"是两种拒绝，话术也不一样：未登记是"没人评估过它"，
     * 没授权是"评估过了，你不够"。分开之后运维同学一眼能看出该改哪一行配置。
     */
    @Test
    @DisplayName("只读角色调不动脚本：登记过，但没授权")
    void 只读角色跑不了脚本(@TempDir Path root) throws Exception {
        Path scripts = packageDirectory(root.resolve("scripts"));
        Path skills = skillDirectory(root.resolve("skills"));

        try (InProcessStdioMcpServer server = InProcessStdioMcpServer.of("layer-two",
                new LayerTwo())) {
            context(scripts, skills, server).run(context -> {
                AuthorizationAdvisor advisor = advisor(context);

                assertThat(ChainStubs.textOf(advisor.adviseCall(request(READ_ONLY),
                        ChainStubs.callReturning(ChainStubs.responseRequesting("demoPackage")))))
                        .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                        .contains("run_script");
                // 登记过的工具，报的是"不允许执行某个动作"，不是"未登记"
                assertThat(ChainStubs.textOf(advisor.adviseCall(request(READ_ONLY),
                        ChainStubs.callReturning(ChainStubs.responseRequesting("demoPackage")))))
                        .doesNotContain("未登记");
            });
        }
    }

    // ------------------------------------------------------------------ 装配辅助

    /**
     * 走一次真实链路：Advisor 判定 → 判定结果交给真的 {@code ToolCallingManager} 去执行。
     *
     * <p>这两步拼起来才是"判据成立"：Advisor 只负责换掉这一轮，
     * 工具跑没跑由 {@code ToolCallingManager} 与计数工具回答。
     */
    private static ChatClientResponse advise(Principal principal, CountingTools tools, String... wanted) {
        ChatClientResponse decided = AuthorizationAdvisorHolder.advisor()
                .adviseCall(request(principal), ChainStubs.callReturning(ChainStubs.responseRequesting(wanted)));
        execute(tools, decided);
        return decided;
    }

    /**
     * 把 Advisor 的判定结果交给真的 {@code ToolCallingManager} 去执行，两条路径共用这一步。
     *
     * <p>共用的理由是怕走偏：同步与流式必须是同一套判据，否则"流式路径也拦得住"
     * 会变成一句只在措辞上成立的保证（工具根本没接进链路，计数恒为 0）。
     */
    private static void execute(CountingTools tools, ChatClientResponse decided) {
        ToolCallingManager manager = ToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        try {
            manager.executeToolCalls(promptWith(tools), decided.chatResponse());
        } catch (IllegalStateException noToolCall) {
            // 被拒绝的那一轮里已经没有工具调用了，ToolCallingManager 自己也这么说
            // ——这正是"越权时工具一次都没跑"的判据：不是"跑了但结果被丢掉"。
            // 放行时不会走到这里。
        }
    }

    private static ChatClientRequest request(Principal principal) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage(QUESTION))))
                .context(Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal))
                .build();
    }

    private static Prompt promptWith(CountingTools tools) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build()
                .getToolCallbacks();
        return new Prompt(List.of(new UserMessage(QUESTION)),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
    }

    private static String textOf(ChatClientResponse response) {
        return ChainStubs.textOf(response);
    }

    /** 一个只能看 region 表的身份：会读，但读不到 sales_order。 */
    private static Principal guest() {
        return Principal.of("u-guest", "guest", Set.of("region"), Set.of(ToolPermission.READ));
    }

    /** 权限登记：一个可查、一个有副作用；{@code exportData} 压根不登记。 */
    private static ToolPermissionRegistry permissions() {
        return ToolPermissionRegistry.of(Map.of(
                SALES_TOOL, ToolPermission.read("sales_order"),
                MAIL_TOOL, ToolPermission.of("sendMail")));
    }

    private static ApplicationContextRunner context(Path scripts, Path skills,
                                                    InProcessStdioMcpServer server) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolsAutoConfiguration.class,
                        SystemStatusAutoConfiguration.class,
                        McpToolAutoConfiguration.class,
                        ScriptPackageAutoConfiguration.class,
                        SkillToolAutoConfiguration.class))
                .withBean(ReadOnlySqlExecutor.class, () -> sql -> QueryResult.empty())
                .withBean("mcpEndpoint", McpServerEndpoint.class,
                        () -> McpServerEndpoint.of(server.name(),
                                io.github.claw4j.domain.principal.ToolPermission.read(),
                                server::openTransport))
                .withPropertyValues("claw4j.scripts.directory=" + scripts,
                        "claw4j.skills.directory=" + skills);
    }

    private static AuthorizationAdvisor advisor(ApplicationContext context) {
        return new AuthorizationAdvisor(registries(context));
    }

    private static List<ToolPermissionRegistry> registries(ApplicationContext context) {
        return new ArrayList<>(context.getBeansOfType(ToolPermissionRegistry.class).values());
    }

    private static List<String> toolNames(ApplicationContext context) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        org.springframework.ai.tool.ToolCallbackProvider[] providers = context
                .getBeansOfType(org.springframework.ai.tool.ToolCallbackProvider.class).values()
                .toArray(new org.springframework.ai.tool.ToolCallbackProvider[0]);
        for (org.springframework.ai.tool.ToolCallbackProvider provider : providers) {
            Arrays.stream(provider.getToolCallbacks())
                    .forEach(callback -> byName.put(callback.getToolDefinition().name(), callback));
        }
        for (ToolCallback callback : context.getBeansOfType(ToolCallback.class).values()) {
            byName.put(callback.getToolDefinition().name(), callback);
        }
        return List.copyOf(byName.keySet());
    }

    /** 一个最小脚本包目录：入口与说明都在，执行不是这条断言要看的。 */
    private static Path packageDirectory(Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("demoPackage"));
        Files.writeString(pkg.resolve("package.properties"),
                "description=演示用的脚本包\nentrypoint=run.sh\n");
        Files.writeString(pkg.resolve("run.sh"), "#!/bin/sh\necho demo\n");
        pkg.resolve("run.sh").toFile().setExecutable(true);
        return root;
    }

    /** 一份最小 SKILL.md：front matter 的 name 与 description 是必填。 */
    private static Path skillDirectory(Path root) throws Exception {
        Path skill = Files.createDirectories(root.resolve("demo-skill"));
        Files.writeString(skill.resolve("SKILL.md"), """
                ---
                name: demo-skill
                description: 演示用的做法，先查数再排版。
                ---

                先用 querySales 查数，再按格式输出。
                """);
        return root;
    }

    /** Advisor 实例按测试共享：它自己是无状态的，权限表也只有一份。 */
    private static final class AuthorizationAdvisorHolder {

        private static final AuthorizationAdvisor ADVISOR =
                new AuthorizationAdvisor(List.of(permissions()));

        private AuthorizationAdvisorHolder() {
        }

        static AuthorizationAdvisor advisor() {
            return ADVISOR;
        }
    }

    /** 三个工具：一个可查、一个有副作用、一个压根没登记权限。 */
    static final class CountingTools {

        private final AtomicInteger salesCalls = new AtomicInteger();

        private final AtomicInteger mailCalls = new AtomicInteger();

        private final AtomicInteger exportCalls = new AtomicInteger();

        @org.springframework.ai.tool.annotation.Tool(description = "查询指定区域与时间窗内的成交额")
        public String querySales(String region, String period) {
            salesCalls.incrementAndGet();
            return "stub:sales-result";
        }

        @org.springframework.ai.tool.annotation.Tool(description = "给指定的人发一封邮件")
        public String sendMail(String to, String body) {
            mailCalls.incrementAndGet();
            return "stub:mail-sent";
        }

        @org.springframework.ai.tool.annotation.Tool(description = "导出数据")
        public String exportData(String target) {
            exportCalls.incrementAndGet();
            return "stub:exported";
        }

        int salesCalls() {
            return salesCalls.get();
        }

        int mailCalls() {
            return mailCalls.get();
        }

        int exportCalls() {
            return exportCalls.get();
        }
    }

    /** 二层：server 侧工具对象，Java 侧只见名字与 Schema。 */
    public static final class LayerTwo {

        @McpTool(name = "layerTwoProbe", description = "二层的探针工具")
        public String probe() {
            return "probe";
        }
    }
}
