package io.github.claw4j.app;

import io.github.claw4j.agent.subagent.SubagentCatalog;
import io.github.claw4j.agent.subagent.SubagentDefinition;
import io.github.claw4j.agent.subagent.SubagentDelegation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓库里那一组子 Agent 示例，真的被加载器读一遍。
 *
 * <p>为什么要专门有一条用例：{@link SubagentCatalog#load} 的判据是"配了就必须读得出来"，
 * 目录不存在、目录空、某一份定义解析不出，<b>一律启动失败</b>。所以
 * {@code examples/subagents} 里任何一个字写错，整套示例配置就起不来——
 * 而示例是给人照抄的，它写错比代码写错更贵。
 *
 * <p>放在 app 模块而不是 agent：这条用例验的是"<b>示例素材</b>能不能被装配进来"，
 * 而 app 是唯一同时看见 agent 与示例插件的模块。agent 的测试目录另有约束
 * （文件数上限 10），也不想让"委派机制"那一组用例里混进一堆文件系统断言。
 *
 * <p>读的是<b>仓库里那一份</b>，不是临时目录里造出来的副本：造副本永远是对的，
 * 也就永远发现不了那一份写坏了。目录从 {@code user.dir} 往上找，
 * 于是 Maven（工作目录是模块）与 IDE（工作目录是仓库根）两种跑法都成立。
 *
 * <p>它抓得住两类真实错误：白名单里的工具名拼错、文件名的 {@code name} 与文件名不一致
 * （委派按名字找定义，找错等于答案指向另一份文件）。它抓不住的是"示例应用的装配真的少了那个工具"——
 * 那一层由 {@link ApplicationAssemblyTest} 用真容器覆盖，这里能看到的只有下面这份手抄的工具名清单。
 */
class ExamplesSubagentsTest {

    /**
     * 示例装配能提供的工具名，<b>手抄的一份</b>。
     *
     * <p>为什么不从容器里取：取一次要起 Postgres（{@code ApplicationAssemblyTest} 已经在做那件事），
     * 而这条用例的判据只是"定义里有没有写错的名字"。代价是它会与示例应用漂移：
     * 改了示例工具的清单，这里要同步。
     */
    private static final Set<String> SAMPLE_TOOLS = Set.of(
            "systemStatus",      // capability/builtin/system，不挂开关
            "querySales",        // claw4j-example-sales 的 @Tool
            "summarize",         // examples/scripts/summarize 的包名就是工具名
            "weather-forecast",  // 同上
            "xurl",              // 同上
            "Skill",             // capability/skill：AgentSkills.TOOL_NAME
            SubagentDelegation.TOOL_NAME);

    @Test
    @DisplayName("五份定义全部解析得出来，name 与文件名一致")
    void everyExampleDefinitionParses() throws IOException {
        Path directory = examplesSubagents();
        List<SubagentDefinition> definitions = SubagentCatalog.load(directory);

        assertThat(definitions)
                .as("一份定义写坏就会让配了 subagents 的应用起不来，所以这里必须全部读得出来")
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(definitions).allSatisfy(definition -> assertThat(definition.name()).isNotBlank());
        assertThat(definitions.stream().map(SubagentDefinition::name).toList())
                .as("重名的话派谁取决于遍历顺序")
                .doesNotHaveDuplicates();

        Set<String> fileNames;
        try (var files = Files.list(directory)) {
            fileNames = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .map(name -> name.substring(0, name.length() - ".md".length()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        assertThat(definitions.stream().map(SubagentDefinition::name).toList())
                .as("委派按名字找定义；name 与文件名不一致时，日志里那一行会指向另一份文件")
                .containsExactlyInAnyOrderElementsOf(fileNames);
        assertThat(fileNames).contains("capacity-triage", "incident-triage", "weekly-digest",
                "metric-glossary", "sql-reviewer");
    }

    @Test
    @DisplayName("每份都是能用的：描述够父 Agent 决定派不派，正文够它照着做")
    void everyDefinitionIsUsable() {
        List<SubagentDefinition> definitions = SubagentCatalog.load(examplesSubagents());

        assertThat(definitions).allSatisfy(definition -> {
            assertThat(definition.description())
                    .as("%s 的 description 是父 Agent 唯一的判断依据，太短等于没写", definition.name())
                    .hasSizeGreaterThan(20);
            assertThat(definition.systemPrompt())
                    .as("%s 的正文是它做事的方式，占位式的几行等于让它自由发挥", definition.name())
                    .hasSizeGreaterThan(200);
        });
    }

    @Test
    @DisplayName("定义里点名的每个工具，都在示例装配能提供的工具里")
    void everyNamedToolExistsInTheSampleAssembly() {
        List<SubagentDefinition> definitions = SubagentCatalog.load(examplesSubagents());

        for (SubagentDefinition definition : definitions) {
            Set<String> named = new LinkedHashSet<>(definition.tools());
            named.addAll(definition.disallowedTools());
            assertThat(SAMPLE_TOOLS)
                    .as("%s 点名的工具必须能在示例里找到，否则它一被派出去就失败在“没有这个工具”",
                            definition.name())
                    .containsAll(named);
        }
    }

    @Test
    @DisplayName("每份定义在示例工具集上算出来的工具都非空，且都不含委派自己")
    void everyDefinitionSelectsToolsWithoutNesting() {
        List<ToolCallback> available = SAMPLE_TOOLS.stream()
                .map(name -> (ToolCallback) new StubTool(name))
                .toList();

        for (SubagentDefinition definition : SubagentCatalog.load(examplesSubagents())) {
            List<String> selected = definition.selectTools(available).stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
            assertThat(selected)
                    .as("%s 一个工具都选不出来时，它只能靠猜作答", definition.name())
                    .isNotEmpty();
            assertThat(selected)
                    .as("委派不嵌套：子 Agent 在任何一份定义里都拿不到 %s", SubagentDelegation.TOOL_NAME)
                    .doesNotContain(SubagentDelegation.TOOL_NAME);
        }
    }

    @Test
    @DisplayName("示例覆盖三种委派形状：只用白名单 / 白名单跨层 / 继承全部再减")
    void examplesCoverTheDifferentShapes() {
        List<SubagentDefinition> definitions = SubagentCatalog.load(examplesSubagents());

        assertThat(definitions)
                .as("“继承全部”这条路没有任何示例的话，抄出来的都是白名单一种形状")
                .anySatisfy(definition -> assertThat(definition.tools()).isEmpty());
        assertThat(definitions)
                .as("完全没有黑名单，就没人示范“继承全部时怎么减掉危险的那些”")
                .anySatisfy(definition -> assertThat(definition.disallowedTools()).isNotEmpty());
        assertThat(definitions)
                .as("白名单只有一个工具，演示不了“一个子 Agent 同时用业务工具与脚本包”")
                .anySatisfy(definition -> assertThat(definition.tools()).hasSizeGreaterThan(1));
    }

    /** 从工作目录往上找 {@code examples/subagents}：Maven 下工作目录是模块，IDE 下是仓库根。 */
    private static Path examplesSubagents() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("examples").resolve("subagents");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 examples/subagents，当前工作目录："
                + System.getProperty("user.dir") + "。这条用例要读仓库里那一份定义。");
    }

    /** 只提供一个名字的替身：这一组用例判的是"选得出哪些名字"，不是工具真的被调用。 */
    private record StubTool(String name) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("示例工具 " + name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return "";
        }
    }
}
