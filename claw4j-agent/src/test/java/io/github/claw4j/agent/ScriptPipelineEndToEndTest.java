package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.github.claw4j.capability.generator.GenerationAudit;
import io.github.claw4j.capability.generator.IsolationSpec;
import io.github.claw4j.capability.generator.SkillGenerator;
import io.github.claw4j.capability.generator.SkillGeneratorToolCallback;
import io.github.claw4j.capability.generator.SkillGeneratorToolProvider;
import io.github.claw4j.capability.script.ScriptPackage;
import io.github.claw4j.capability.script.ScriptPackageRegistry;
import io.github.claw4j.capability.script.ScriptPackageRunner;
import io.github.claw4j.capability.script.ScriptPackageToolProvider;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脚本这条流水线接到 Agent 上：<b>生成</b>（五步停在人审）→ <b>发布</b> → <b>执行</b>。
 *
 * <p>两段合一份文件，因为它们本来就是同一条链：生成器产出的东西就是一个脚本包，
 * 而"生成器交出去的那份脚本到底能不能跑"这件事，只有把执行侧接上才看得见。
 * 分开写时最容易被漏掉的是"生成成功但产物跑不起来"——两边各自全绿。
 *
 * <p>判据按 ADR-0011 分两类：
 * <ul>
 *   <li><b>过模型的</b>（清单、同步、流式、未登记被拒、停在人审）：连真实 DeepSeek。
 *       这里要验的正是"模型会不会去调它"，换成组件级就验不到。
 *   <li><b>不经过模型的</b>（只读角色跑不了脚本、未人审不在清单里）：真模型不会为了撞权限
 *       而去调它，判据在权限表与清单本身。
 * </ul>
 *
 * <p>脚本包是建在临时目录里的真脚本：真被沙箱执行、真产生输出。断言盯的是<b>随机 marker</b>
 * 而不是脚本文本：输出什么可以猜（"多半是 hello"），marker 每次都不一样，猜不出来。
 */
class ScriptPipelineEndToEndTest {

    private static final String SCRIPT_TOOL = "runtimeEcho";

    /** 生成器让脚本输出这一句，于是"脚本真的被跑过"可以被审计里那一条记录证明。 */
    private static final String MARKER = "GENERATED-SCRIPT-RAN";

    // ------------------------------------------------------------------ 执行侧

    @Test
    @DisplayName("脚本包与 querySales 同时在模型可见的清单里")
    void 脚本包与querySales都在清单里(@TempDir Path parent) throws IOException {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        ScriptPackage pkg = echoPackage(parent);

        scriptContext(model, pkg).run(context -> {
            context.getBean(AgentPort.class).reply(scriptAsk("现在有哪些工具可以用？"));

            assertThat(model.toolNamesOfLastRequest())
                    .contains("querySales")
                    .contains(SCRIPT_TOOL);
        });
    }

    @Test
    @DisplayName("同步端到端：模型调到脚本包并把输出组织成回答")
    void 同步路径调到脚本并拿到输出(@TempDir Path parent) throws IOException {
        String marker = freshMarker();
        ScriptPackage pkg = echoPackage(parent, marker);

        scriptContext(RealModelSupport.chatModel(), pkg).run(context -> {
            String answer = RealModelSupport.eventually(3, "模型调用 " + SCRIPT_TOOL,
                    () -> context.getBean(AgentPort.class)
                            .reply(scriptAsk("用 " + SCRIPT_TOOL + " 工具取一个标记，把标记原样告诉我，不要解释。"))
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
        String marker = freshMarker();
        ScriptPackage pkg = echoPackage(parent, marker);

        scriptContext(RealModelSupport.chatModel(), pkg).run(context -> {
            String answer = RealModelSupport.eventually(3, "流式路径调用 " + SCRIPT_TOOL, () -> {
                List<String> chunks = new ArrayList<>();
                context.getBean(AgentPort.class)
                        .stream(scriptAsk("用 " + SCRIPT_TOOL + " 工具取一个标记，把标记原样告诉我，不要解释。"),
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
                                    .reply(scriptAsk("用 " + SCRIPT_TOOL + " 工具取一个标记"))
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

    // ------------------------------------------------------------------ 生成侧

    /**
     * 同步路径：模型调到生成器，五步停在人审，脚本在沙箱里真的跑过一次。
     *
     * <p>断言盯的是<b>审计与清单</b>，不是模型的措辞：模型怎么说每一轮都可能变，
     * 而"暂存区里有没有这一版脚本"和"清单里有没有它"是要守住的那两件事。
     */
    @Test
    @DisplayName("生成器：模型为新能力提交脚本，自测真跑过，停在待人工审核")
    void 同步路径走完五步停在人审(@TempDir Path parent) {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        Fixture fixture = fixture(parent);

        generatorContext(model, fixture).run(context -> {
            String answer = RealModelSupport.eventually(3, "模型调用 propose_script",
                    () -> context.getBean(AgentPort.class).reply(generatorAsk()).text());

            // 过模型的那一半：它确实把这件事交给了生成器
            assertThat(model.toolNamesOfLastRequest()).contains(SkillGeneratorToolCallback.TOOL_NAME);

            // 不过模型的那一半：五步的产物
            assertThat(fixture.audit.replay("wordcount"))
                    .extracting(entry -> entry.stage())
                    .contains(GenerationAudit.Stage.GENERATED, GenerationAudit.Stage.SELF_TESTED);
            assertThat(fixture.audit.replay("wordcount").toString()).contains(MARKER);
            assertThat(fixture.registry.packages()).isEmpty();
            assertThat(answer).isNotBlank();
        });
    }

    /** 流式路径同样成立：只实现同步接口的组件在流式上会被静默跳过（ADR-0007 那个坑）。 */
    @Test
    @DisplayName("生成器：流式路径同样能把脚本提交上去")
    void 流式路径也走同一条链路(@TempDir Path parent) {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        Fixture fixture = fixture(parent);

        generatorContext(model, fixture).run(context -> {
            StringBuilder answer = new StringBuilder();
            RealModelSupport.eventually(3, "流式路径调用 propose_script", () -> {
                answer.setLength(0);
                context.getBean(AgentPort.class).stream(generatorAsk(), chunk -> answer.append(chunk.text()));
                return answer.toString();
            });

            assertThat(model.toolNamesOfLastRequest()).contains(SkillGeneratorToolCallback.TOOL_NAME);
            assertThat(fixture.audit.all()).isNotEmpty();
        });
    }

    /** 未人审的包不在工具清单里——01 号票那条清单断言的形状，用在真装配上。 */
    @Test
    @DisplayName("生成器：人审之前它不在工具清单里，模型也调不到它")
    void 未人审不在清单里(@TempDir Path parent) {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        Fixture fixture = fixture(parent);

        generatorContext(model, fixture).run(context -> {
            RealModelSupport.eventually(3, "模型调用 propose_script",
                    () -> context.getBean(AgentPort.class).reply(generatorAsk()).text());

            assertThat(fixture.scriptProvider.toolNames()).doesNotContain("wordcount");
            assertThat(fixture.generatorProvider.toolNames()).contains(SkillGeneratorToolCallback.TOOL_NAME);
        });
    }

    // ------------------------------------------------------------------ 装配辅助

    private static ApplicationContextRunner scriptContext(ChatModel model, ScriptPackage pkg) {
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

    private static ApplicationContextRunner generatorContext(ChatModel model, Fixture fixture) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model)
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class,
                        () -> FakeSqlExecutor.returning("amount", "128400.00"))
                .withBean("scriptTools", ToolCallbackProvider.class, () -> fixture.scriptProvider())
                .withBean("generatorTools", ToolCallbackProvider.class, () -> fixture.generatorProvider())
                .withBean("permissions", ToolPermissionRegistry.class, () -> ToolPermissionRegistry.of(Map.of(
                        "querySales", ToolPermission.read("sales_order"),
                        SkillGeneratorToolCallback.TOOL_NAME,
                        ToolPermission.of(SkillGeneratorToolCallback.GENERATOR_ACTION))));
    }

    private static Fixture fixture(Path parent) {
        ScriptPackageRegistry registry = ScriptPackageRegistry.empty();
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of());
        ScriptPackageToolProvider scriptProvider = new ScriptPackageToolProvider(registry, runner);
        GenerationAudit audit = new GenerationAudit(parent.resolve("audit.tsv"));
        SkillGenerator generator = new SkillGenerator(
                parent.resolve("staging"), parent.resolve("reviews"),
                parent.resolve("published"),
                registry, audit, IsolationSpec.local(), Clock.systemUTC());
        SkillGeneratorToolProvider generatorProvider = new SkillGeneratorToolProvider(generator);
        return new Fixture(registry, scriptProvider, generatorProvider, generator, audit);
    }

    /**
     * 建一个真能跑的脚本包。可执行位是这套测试唯一的平台假设（CI 与本机都是 POSIX），
     * 少了它的症状是 stderr 里一句 {@code Permission denied}，离"忘了 chmod"隔了一层。
     */
    private static ScriptPackage echoPackage(Path parent) throws IOException {
        return echoPackage(parent, freshMarker());
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

    private static String freshMarker() {
        return "mk-" + Long.toHexString(System.nanoTime());
    }

    /** 用 admin 身份：脚本层要的是 {@code run_script} 动作，只读角色拿不到。 */
    private static InboundMessage scriptAsk(String question) {
        return new InboundMessage(
                Principal.of("ethan", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD)),
                "script:" + System.nanoTime(),
                question);
    }

    private static InboundMessage generatorAsk() {
        return new InboundMessage(
                Principal.of("ethan", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD)),
                "generator:" + System.nanoTime(),
                "我要一个新能力：数一数一段文本有多少个字符。请用 propose_script 工具提交一个脚本包，"
                        + "包名用 wordcount，description 写「数一数传入文本有多少个字符」，"
                        + "脚本正文就一行 echo " + MARKER + "（带 #! 开头的那行）。"
                        + "提交完把工具返回里第 5 步那句话原样告诉我，不要自己发挥。");
    }

    private record Fixture(ScriptPackageRegistry registry, ScriptPackageToolProvider scriptProvider,
                           SkillGeneratorToolProvider generatorProvider,
                           SkillGenerator generator,
                           GenerationAudit audit) {
    }
}
