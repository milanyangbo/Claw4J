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

/**
 * 一份 Subagent 定义怎么被读出来，以及它在可用工具里挑出哪些。
 *
 * <p>这个类守的是"配置写错"这一类：写错的后果不是报错，而是<b>子 Agent 悄悄少了能力</b>
 * （少给一个工具、白名单点错名字），而它在父那边看起来只是"这次没查出来"。
 * 所以下面每一条失败都断言<b>具体的异常与消息</b>，不是"抛了就行"。
 */
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

    /**
     * 一份定义少了哪一块都不是"这一份没有名字/没有正文"，而是配置错误：
     * 没名字的没法被 {@code Task} 点到，没正文的不知道该怎么做事。
     */
    @Test
    @DisplayName("缺 name、缺 description、没有正文都是配置错误")
    void 定义不完整即失败(@TempDir Path dir) throws IOException {
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
        assertThatThrownBy(() -> new SubagentDefinition("analyst", "查数。", "   ", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文");
    }

    /**
     * 两种自相矛盾：同一个工具既在白名单又在黑名单；白名单里写了委派工具自己。
     * 后者是 GLOSSARY 的"委派不嵌套"——不拦的话子 Agent 可以再派一层，
     * 预算与身份在那个方向上都没人看着。
     */
    @Test
    @DisplayName("白名单与黑名单打架、或白名单里写了委派工具自己，都是定义错误")
    void 自相矛盾的定义即失败() {
        assertThatThrownBy(() -> new SubagentDefinition(
                "analyst", "查数。", BODY, List.of("querySales"), List.of("querySales")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自相矛盾");
        assertThatThrownBy(() -> new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(SubagentDelegation.TOOL_NAME), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("委派不嵌套");
    }

    /**
     * 三条分支合起来才是完整的挑法，任何一条单独看都能被"挑错了"蒙过去：
     * 写了白名单就只留点名的；没写白名单就继承全部减去黑名单；继承来的那一份里
     * 委派工具自己也要减掉。
     */
    @Test
    @DisplayName("白名单点了谁就只给谁；没写白名单就继承全部，减掉黑名单与委派工具")
    void 工具清单怎么挑() {
        List<ToolCallback> available = List.of(
                StubTool.returning("querySales", "amount"),
                StubTool.returning("Skill", "做法"),
                StubTool.returning(SubagentDelegation.TOOL_NAME, "又派了一层"),
                StubTool.returning("run_report", "报告"));

        assertThat(toolNames(new SubagentDefinition(
                "analyst", "查数。", BODY, List.of("querySales", "Skill"), List.of())
                .selectTools(available)))
                .as("委派不等于把全部能力交出去")
                .containsExactly("querySales", "Skill");

        assertThat(toolNames(new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(), List.of("querySales"))
                .selectTools(available)))
                .as("黑名单只减点名的那一个；委派工具在任何继承路径上都要减掉")
                .containsExactly("Skill", "run_report");

        assertThat(toolNames(new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(), List.of())
                .selectTools(available)))
                .as("继承全部时委派工具也要减掉，但别的必须还在——"
                        + "只断言它不在的话，一个把所有工具都减掉的实现也是绿的")
                .containsExactly("querySales", "Skill", "run_report");
    }

    /**
     * 挑完一个工具都不剩时必须失败，不能"就这么跑"：没有工具的 Subagent 只能靠猜，
     * 而它猜出来的结论会被父当成事实转述给用户。
     * 白名单点名了却不存在同样要失败——那说明配置文件写错了名字，少给一个不是意图。
     */
    @Test
    @DisplayName("挑完一个都不剩、或白名单点名了却没装上，都失败")
    void 挑空的与点错名的都失败() {
        List<ToolCallback> available = List.of(StubTool.returning("querySales", "amount"));

        assertThatThrownBy(() -> new SubagentDefinition(
                "analyst", "查数。", BODY, List.of(), List.of("querySales"))
                .selectTools(available))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个可用工具都没有");

        assertThatThrownBy(() -> new SubagentDefinition(
                "analyst", "查数。", BODY, List.of("run_report"), List.of())
                .selectTools(available))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run_report")
                .hasMessageContaining("没有它");
    }

    private static List<String> toolNames(List<ToolCallback> callbacks) {
        return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static Path write(Path dir, String fileName, String content) throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }
}
