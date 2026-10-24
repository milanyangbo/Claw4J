package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.github.claw4j.capability.script.ScriptPackage;
import io.github.claw4j.capability.script.ScriptPackageToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脚本层接到 Agent 上之后的样子：三层对 Agent 呈现为一回事（ADR-0003）。
 *
 * <p>判据按 ADR-0011 分两类：
 * <ul>
 *   <li><b>过模型的</b>（清单、同步端到端、流式端到端、未登记被拒）：连真实 DeepSeek，
 *       关键断言给有限重试。这里要验的正是"模型会不会去调它"，换成组件级就验不到了。
 *   <li><b>不经过模型的</b>：脚本层那几条边界（目录锁、环境、超时、脚本本体不进上下文）
 *       在 tools 侧用真进程验，不需要模型参与。
 * </ul>
 *
 * <p>脚本包是建在临时目录里的真脚本：真被沙箱执行、真产生输出。
 * 沙箱的物理形态是"应用容器内的受限子进程"（不依赖 Docker 与 Python），
 * 于是这几条在 CI 上也跑得起来。
 */
class ScriptPackageEndToEndTest {

    private static final String SCRIPT_TOOL = "runtimeEcho";

    /**
     * 两个来源同时存在时都在清单里：脚本层这一层不能被上一个来源挤掉，
     * 而"没进清单"的唯一症状是模型开始编答案，所以从模型收到的 ChatOptions 上读。
     */
    @Test
    @DisplayName("脚本包与 querySales 同时出现在模型可见的清单里")
    void 两个来源的工具都在清单里(@TempDir Path parent) throws IOException {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        ScriptPackage pkg = echoPackage(parent);

        context(model, pkg).run(context -> {
            context.getBean(AgentPort.class).reply(ask("现在有哪些工具可以用？"));

            assertThat(model.toolNamesOfLastRequest())
                    .contains("querySales")
                    .contains(SCRIPT_TOOL);
        });
    }

    /**
     * 同步端到端：脚本真被跑了，输出真进了回答。
     *
     * <p>断言盯的是随机 marker 而不是脚本的"内容"：
     * 脚本输出什么可以猜（"多半是 hello"），而 marker 每次测试都不一样，
     * 猜不出来——只有真调用过才拿得到。
     */
    @Test
    @DisplayName("同步端到端：模型调到脚本包并把输出组织成回答")
    void 同步路径调到脚本并拿到输出(@TempDir Path parent) throws IOException {
        String marker = "mk-" + Long.toHexString(System.nanoTime());
        ScriptPackage pkg = echoPackage(parent, marker);

        context(RealModelSupport.chatModel(), pkg).run(context -> {
            String answer = RealModelSupport.eventually(3, "模型调用 " + SCRIPT_TOOL,
                    () -> context.getBean(AgentPort.class)
                            .reply(ask("用 " + SCRIPT_TOOL + " 工具取一个标记，把标记原样告诉我，不要解释。"))
                            .text());

            assertThat(answer).contains(marker);
        });
    }

    /**
     * 流式路径同样成立。为什么值得单独一条：只实现同步接口的组件在流式上会被静默跳过
     * （ADR-0007 那个坑），"能演示"必须两条路径各自证明一次。
     */
    @Test
    @DisplayName("流式端到端：脚本的输出同样出现在流里")
    void 流式路径也调到脚本(@TempDir Path parent) throws IOException {
        String marker = "mk-" + Long.toHexString(System.nanoTime());
        ScriptPackage pkg = echoPackage(parent, marker);

        context(RealModelSupport.chatModel(), pkg).run(context -> {
            String answer = RealModelSupport.eventually(3, "流式路径调用 " + SCRIPT_TOOL, () -> {
                List<String> chunks = new ArrayList<>();
                context.getBean(AgentPort.class)
                        .stream(ask("用 " + SCRIPT_TOOL + " 工具取一个标记，把标记原样告诉我，不要解释。"),
                                chunk -> chunks.add(chunk.text()));
                return String.join("", chunks);
            });

            assertThat(answer).contains(marker);
        });
    }

    /**
     * 没登记权限的脚本工具一律按拒绝（ADR-0007）：漏登记的失败方向是拒绝，不是放行。
     *
     * <p>这里刻意<b>不挂</b>脚本层那张权限表：容器里有一个脚本工具、
     * 但没人认领它——正是"没人评估过它会碰到什么"的状态。
     */
    @Test
    @DisplayName("没登记权限的脚本工具被拒绝")
    void 没登记权限的脚本工具被拒绝(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = echoPackage(parent);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .withBean("scriptTools", ToolCallbackProvider.class,
                        () -> new ScriptPackageToolProvider(List.of(pkg)))
                // 只有 querySales 被登记，脚本工具没人认领
                .withBean("permissions", ToolPermissionRegistry.class,
                        () -> ToolPermissionRegistry.of(
                                Map.of("querySales", ToolPermission.read("sales_order"))))
                .run(context -> {
                    String answer = RealModelSupport.eventually(3, "未登记的脚本工具被拒绝",
                            () -> context.getBean(AgentPort.class)
                                    .reply(ask("用 " + SCRIPT_TOOL + " 工具取一个标记"))
                                    .text());

                    assertThat(answer)
                            .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                            .contains(SCRIPT_TOOL);
                });
    }

    /**
     * 脚本层要的是独立动作，不是 {@code read}：只读角色能查数是本意，
     * 能执行脚本就不是了。这条不过模型——真模型不会为了撞权限而去调它。
     */
    @Test
    @DisplayName("只读角色跑不了脚本，登记了 run_script 动作才可以")
    void 只读角色跑不了脚本(@TempDir Path parent) throws IOException {
        ScriptPackage pkg = echoPackage(parent);
        ToolPermission required = new ScriptPackageToolProvider(List.of(pkg)).permissions().get(SCRIPT_TOOL);

        assertThat(Principal.readOnly("u-1").canUse(required)).isFalse();
        assertThat(Principal.of("u-2", "admin", Set.of(Principal.WILDCARD),
                Set.of(ScriptPackageToolProvider.SCRIPT_ACTION)).canUse(required)).isTrue();
    }

    // ------------------------------------------------------------------ 装配辅助

    private static ApplicationContextRunner context(ChatModel model, ScriptPackage pkg) {
        ScriptPackageToolProvider provider = new ScriptPackageToolProvider(List.of(pkg));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                // SalesTools 需要一个执行器：用替身，这里测的是脚本层不是 SQL
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .withBean("scriptTools", ToolCallbackProvider.class, () -> provider)
                .withBean("scriptPermissions", ToolPermissionRegistry.class,
                        () -> ToolPermissionRegistry.of(provider.permissions()));
    }

    /**
     * 建一个真能跑的脚本包。可执行位是这套测试唯一的平台假设（CI 与本机都是 POSIX），
     * 少了它的症状是 stderr 里一句 {@code Permission denied}，离"忘了 chmod"隔了一层。
     */
    private static ScriptPackage echoPackage(Path parent) throws IOException {
        return echoPackage(parent, "mk-" + Long.toHexString(System.nanoTime()));
    }

    private static ScriptPackage echoPackage(Path parent, String marker) throws IOException {
        Path root = Files.createDirectories(parent.resolve(SCRIPT_TOOL));
        Path script = root.resolve(ScriptPackage.DEFAULT_ENTRYPOINT);
        Files.writeString(script, "#!/bin/sh\necho 'marker=" + marker + "'\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE));
        return ScriptPackage.of(SCRIPT_TOOL, root, ScriptPackage.DEFAULT_ENTRYPOINT,
                "取一个随机标记，用于验证脚本能力是否真的被调用",
                ScriptPackage.DEFAULT_TIMEOUT_MILLIS);
    }

    /** 用 admin 身份：脚本层要的是 {@code run_script} 动作，只读角色拿不到。 */
    private static InboundMessage ask(String question) {
        return new InboundMessage(
                Principal.of("ethan", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD)),
                "script:" + System.nanoTime(),
                question);
    }
}
