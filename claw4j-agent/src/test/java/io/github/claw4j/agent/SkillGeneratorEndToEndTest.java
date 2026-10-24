package io.github.claw4j.agent;

import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.capability.ToolsAutoConfiguration;
import io.github.claw4j.capability.generator.GenerationAudit;
import io.github.claw4j.capability.generator.IsolationSpec;
import io.github.claw4j.capability.generator.SkillGeneratorToolProvider;
import io.github.claw4j.capability.builtin.sales.ReadOnlySqlExecutor;
import io.github.claw4j.capability.script.ScriptPackageRegistry;
import io.github.claw4j.capability.script.ScriptPackageRunner;
import io.github.claw4j.capability.script.ScriptPackageToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成器接到 Agent 上之后的样子：模型真的产出了一份脚本，走完五步停在人审那一步。
 *
 * <p>判据按 ADR-0011 分两类：
 * <ul>
 *   <li><b>过模型的</b>（模型会不会去调它、停在哪一步）：连真实 DeepSeek。
 *       这里要验的正是"模型会不会为了一个新能力去调 propose_script"，
 *       换成组件级就验不到了。
 *   <li><b>不经过模型的</b>（静态检查、沙箱、审计、人审）：在 tools 侧用真进程验。
 * </ul>
 *
 * <p>断言盯的是<b>审计与清单</b>，不是模型的措辞：模型怎么说每一轮都可能变，
 * 而"暂存区里有没有这一版脚本"和"清单里有没有它"是要守住的那两件事。
 */
class SkillGeneratorEndToEndTest {

    /** 脚本输出这一句，于是"脚本真的被跑过"可以被审计里那一条记录证明。 */
    private static final String MARKER = "GENERATED-SCRIPT-RAN";

    @TempDir
    Path parent;

    /**
     * 同步路径：模型调到生成器，五步停在人审，脚本在沙箱里真的跑过一次。
     */
    @Test
    @DisplayName("模型为新能力提交脚本：自测真跑过，停在待人工审核")
    void 同步路径走完五步停在人审() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        Fixture fixture = fixture();

        context(model, fixture).run(context -> {
            String answer = RealModelSupport.eventually(3, "模型调用 propose_script",
                    () -> context.getBean(AgentPort.class).reply(ask()).text());

            // 过模型的那一半：它确实把这件事交给了生成器
            assertThat(model.toolNamesOfLastRequest()).contains(SkillGeneratorToolCallbackName.TOOL_NAME);

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
    @DisplayName("流式路径同样能把脚本提交上去")
    void 流式路径也走同一条链路() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        Fixture fixture = fixture();

        context(model, fixture).run(context -> {
            StringBuilder answer = new StringBuilder();
            RealModelSupport.eventually(3, "流式路径调用 propose_script", () -> {
                answer.setLength(0);
                context.getBean(AgentPort.class).stream(ask(), chunk -> answer.append(chunk.text()));
                return answer.toString();
            });

            assertThat(model.toolNamesOfLastRequest()).contains(SkillGeneratorToolCallbackName.TOOL_NAME);
            assertThat(fixture.audit.all()).isNotEmpty();
        });
    }

    /** 未人审的包不在工具清单里——01 号票那条清单断言的形状，用在真装配上。 */
    @Test
    @DisplayName("人审之前它不在工具清单里，模型也调不到它")
    void 未人审不在清单里() {
        RecordingChatModel model = RecordingChatModel.of(RealModelSupport.chatModel());
        Fixture fixture = fixture();

        context(model, fixture).run(context -> {
            RealModelSupport.eventually(3, "模型调用 propose_script",
                    () -> context.getBean(AgentPort.class).reply(ask()).text());

            assertThat(fixture.scriptProvider.toolNames()).doesNotContain("wordcount");
            assertThat(fixture.generatorProvider.toolNames())
                    .contains(SkillGeneratorToolCallbackName.TOOL_NAME);
        });
    }

    // ------------------------------------------------------------------ 装配辅助

    private Fixture fixture() {
        ScriptPackageRegistry registry = ScriptPackageRegistry.empty();
        ScriptPackageRunner runner = new ScriptPackageRunner(List.of());
        ScriptPackageToolProvider scriptProvider = new ScriptPackageToolProvider(registry, runner);
        GenerationAudit audit = new GenerationAudit(parent.resolve("audit.tsv"));
        io.github.claw4j.capability.generator.SkillGenerator generator =
                new io.github.claw4j.capability.generator.SkillGenerator(
                        parent.resolve("staging"), parent.resolve("reviews"),
                        parent.resolve("published"),
                        registry, audit, IsolationSpec.local(), Clock.systemUTC());
        SkillGeneratorToolProvider generatorProvider = new SkillGeneratorToolProvider(generator);
        return new Fixture(registry, scriptProvider, generatorProvider, generator, audit);
    }

    private ApplicationContextRunner context(ChatModel model, Fixture fixture) {
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
                        SkillGeneratorToolCallbackName.TOOL_NAME,
                        ToolPermission.of(SkillGeneratorToolCallbackName.ACTION))));
    }

    private static InboundMessage ask() {
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
                           io.github.claw4j.capability.generator.SkillGenerator generator,
                           GenerationAudit audit) {
    }

    /** 工具名与动作名从主代码取，测试里不抄一份字面量。 */
    private static final class SkillGeneratorToolCallbackName {

        private static final String TOOL_NAME =
                io.github.claw4j.capability.generator.SkillGeneratorToolCallback.TOOL_NAME;

        private static final String ACTION =
                io.github.claw4j.capability.generator.SkillGeneratorToolCallback.GENERATOR_ACTION;
    }
}
