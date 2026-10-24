package io.github.claw4j.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 一份 Subagent 定义怎么被读出来、以及它在可用工具里挑出哪些。 */
class SubagentDefinitionTest {

    private static final String BODY = """
            你是销售分析员。
            1. 先用 querySales 查出这个区域的成交额。
            2. 只输出数字，不要复述工具返回的原始内容。

            内部代号 SUBAGENT-SECRET。
            """;

    @Test
    @DisplayName("front matter 是配置，正文是它做事时用的系统提示词")
    void 解析出名字描述正文与工具名单(@TempDir Path dir) throws IOException {
        SubagentDefinition definition = SubagentDefinition.parse(write(dir, "analyst.md", """
                ---
                name: sales-analyst
                description: 查区域销售额并给出结论。
                tools: querySales, Skill
                disallowedTools: Task
                ---
                """ + BODY));

        assertThat(definition.name()).isEqualTo("sales-analyst");
        assertThat(definition.description()).isEqualTo("查区域销售额并给出结论。");
        assertThat(definition.systemPrompt()).contains("先用 querySales");
        assertThat(definition.tools()).containsExactly("querySales", "Skill");
        assertThat(definition.disallowedTools()).containsExactly("Task");
    }

    @Test
    @DisplayName("缺 name 或 description 是配置错误，不是“这一份没有名字”")
    void 缺少元数据即失败(@TempDir Path dir) throws IOException {
        Path noName = write(dir, "no-name.md", """
                ---
                description: 没有名字。
                ---
                正文
                """);
        Path noDescription = write(dir, "no-description.md", """
                ---
                name: analyst
                ---
                正文
                """);

        assertThatThrownBy(() -> SubagentDefinition.parse(noName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> SubagentDefinition.parse(noDescription))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("没有正文的 Subagent 不知道该怎么做事：同样是失败")
    void 没有正文即失败() {
        assertThatThrownBy(() -> new SubagentDefinition("analyst", "查数。", "   ", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文");
    }

    @Test
    @DisplayName("白名单与黑名单点名同一个工具时，定义本身就是错的")
    void 白名单与黑名单打架即失败() {
        assertThatThrownBy(() -> new SubagentDefinition(
                "analyst", "查数。", BODY, List.of("querySales"), List.of("querySales")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自相矛盾");
    }

    @Test
    @DisplayName("白名单里写 Task：委派不嵌套")
    void 白名单里不能写委派工具自己() {
        assertThatThrownBy(() -> new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(SubagentDelegation.TOOL_NAME), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("委派不嵌套");
    }

    @Test
    @DisplayName("白名单决定它能用哪些工具，别的它拿不到")
    void 白名单挑出可用工具() {
        List<ToolCallback> available = List.of(
                StubTool.returning("querySales", "amount"),
                StubTool.returning("Skill", "做法"),
                StubTool.returning("run_report", "报告"));
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", BODY, List.of("querySales", "Skill"), List.of());

        assertThat(definition.selectTools(available))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("querySales", "Skill");
    }

    @Test
    @DisplayName("没写白名单就继承全部，但黑名单照样生效")
    void 黑名单从继承来的那一份里减掉() {
        List<ToolCallback> available = List.of(
                StubTool.returning("querySales", "amount"),
                StubTool.returning("run_report", "报告"));
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(), List.of("querySales"));

        assertThat(definition.selectTools(available))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("run_report");
    }

    /**
     * "继承全部"这条路上，委派工具自己也要被减掉（GLOSSARY 的"委派不嵌套"）。
     *
     * <p>两个方向都要断言：只断言"委派工具不在里面"的话，一份把所有工具都减掉的实现
     * 也是绿的；所以同一条用例里还要看到继承来的那些仍在。
     */
    @Test
    @DisplayName("没写白名单时，委派工具自己不在继承来的那一份里")
    void 继承全部时也没有委派工具() {
        List<ToolCallback> available = List.of(
                StubTool.returning("querySales", "amount"),
                StubTool.returning(SubagentDelegation.TOOL_NAME, "又派了一层"),
                StubTool.returning("run_report", "报告"));
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(), List.of());

        assertThat(definition.selectTools(available))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("querySales", "run_report");
    }

    @Test
    @DisplayName("白名单点名了却没装上的工具：失败，而不是“少给一个”")
    void 白名单点名了没有的工具即失败() {
        List<ToolCallback> available = List.of(StubTool.returning("run_report", "报告"));
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", BODY, List.of("querySales"), List.of());

        assertThatThrownBy(() -> definition.selectTools(available))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("querySales")
                .hasMessageContaining("没有它");
    }

    @Test
    @DisplayName("挑完之后一个工具都不剩：失败，没有工具的 Subagent 只能靠猜")
    void 挑完为空即失败() {
        List<ToolCallback> available = List.of(StubTool.returning("querySales", "amount"));
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(), List.of("querySales"));

        assertThatThrownBy(() -> definition.selectTools(available))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个可用工具都没有");
    }

    private static Path write(Path dir, String fileName, String content) throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }
}
