package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.ChainStubs;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import io.github.claw4j.tools.mcp.McpConnection;
import io.github.claw4j.tools.mcp.McpServerEndpoint;
import io.github.claw4j.tools.mcp.McpToolAutoConfiguration;
import io.github.claw4j.tools.mcp.McpToolProvider;
import io.github.claw4j.tools.mcp.stdio.InProcessStdioMcpServer;
import io.github.claw4j.tools.sales.QueryResult;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import io.github.claw4j.tools.script.ScriptPackageAutoConfiguration;
import io.github.claw4j.tools.skill.SkillToolAutoConfiguration;
import io.github.claw4j.tools.system.SystemStatusAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.ToolCallback;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复核：三层都启用时，**每个工具都登记了权限**，漏登记的后果是拒绝（票 07 的复核项之一）。
 *
 * <p>为什么要在装配出来的容器上验，而不是逐个工具去看代码：这一层的失效姿势是
 * **某一层那张权限表没被注册**——工具在清单里、模型看得见、但一律按"未登记"被拒，
 * 于是症状是"它忽然不会用某个工具了"，离"多了一个来源"隔着十万八千里。
 * 01 号票在工具来源上踩过一次，02/03/06 各在权限表上重演了一次，
 * 这一条是把"三层同时在容器里时，每张表都在"这句话钉成断言。
 *
 * <p>判据用的是 {@link AuthorizationAdvisor} 的<b>行为</b>而不是"表里有这个名字"：
 * 后者重复了实现（表里有就算过），而前者问的是"模型要调它，会不会被拦"。
 */
class PermissionsAcrossLayersTest {

    /** 只读角色：能读、能用 Skill 与委派、能看运行时状态，但跑不了脚本。 */
    private static final Principal READ_ONLY = Principal.of("u", "read-only",
            Set.of("*"), Set.of("read", "use_skill", "delegate_task", "read_system"));

    private static final Principal ADMIN = Principal.of("u", "admin",
            Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

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
                            .as("%s 必须已登记权限：漏登记的后果是拒绝，而它会表现为“模型忽然不用它了”", name)
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

    // ------------------------------------------------------------------ 装配

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

    private static ChatClientRequest request(Principal principal) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("把各层的工具各调一次"))))
                .context(Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal))
                .build();
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

    /** 二层：server 侧工具对象，Java 侧只见名字与 Schema。 */
    public static final class LayerTwo {

        @McpTool(name = "layerTwoProbe", description = "二层的探针工具")
        public String probe() {
            return "probe";
        }
    }
}
