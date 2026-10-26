package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.subagent.SubagentAutoConfiguration;
import io.github.claw4j.agent.subagent.SubagentCatalog;
import io.github.claw4j.agent.subagent.SubagentDefinition;
import io.github.claw4j.agent.subagent.SubagentDelegation;
import io.github.claw4j.agent.subagent.SubagentRunner;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子 Agent 委派：目录里有哪些专职 Agent、派出去时带什么边界、子那边用的还是不是父的身份。
 *
 * <p>配置错误在启动时暴露（目录空、定义缺字段、白名单点名不存在的工具）；
 * 运行期最贵的一条是"委派提权"——它不报错，只是子 Agent 看见了父看不见的表。
 */
class SubagentDelegationTest {

    private static final String BODY = "你是销售分析员。先查数，再给结论。";

    @TempDir
    Path directory;

    @Test
    @DisplayName("目录按文件名排序读，非 md 文件不进清单")
    void catalogReadsSortedMarkdownAndIgnoresOtherFiles() throws IOException {
        write("b.md", definition("beta", "第二个", "lookup", ""));
        write("a.md", definition("alpha", "第一个", "lookup", ""));
        write("notes.txt", "不是定义");

        List<SubagentDefinition> loaded = SubagentCatalog.load(directory);

        assertThat(loaded).extracting(SubagentDefinition::name).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("配了目录却没有可用定义就启动失败：空目录不是「这一层不存在」")
    void configuredDirectoryWithoutDefinitionsFails() throws IOException {
        assertThatThrownBy(() -> SubagentCatalog.load(directory.resolve("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");

        Path empty = Files.createDirectory(directory.resolve("empty"));
        assertThatThrownBy(() -> SubagentCatalog.load(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个 *.md 都没有");
    }

    @Test
    @DisplayName("一份定义写坏就整层起不来，不静默跳过它")
    void brokenDefinitionFailsTheWholeLayer() throws IOException {
        write("good.md", definition("good", "好的", "lookup", ""));
        write("bad.md", """
                ---
                name: bad
                ---

                没有 description
                """);

        assertThatThrownBy(() -> SubagentCatalog.load(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("front matter 的工具名按逗号拆开，正文原样成为系统提示词")
    void parseSplitsToolsAndKeepsBody() throws IOException {
        Path file = write("a.md", definition("alpha", "管销售", "lookup, querySales", "sendEmail"));

        SubagentDefinition definition = SubagentDefinition.parse(file);

        assertThat(definition.name()).isEqualTo("alpha");
        assertThat(definition.description()).isEqualTo("管销售");
        assertThat(definition.tools()).containsExactly("lookup", "querySales");
        assertThat(definition.disallowedTools()).containsExactly("sendEmail");
        assertThat(definition.systemPrompt()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("缺 description 的定义直接拒绝：父 Agent 只靠这一行决定派不派它")
    void definitionWithoutDescriptionIsRejected() {
        assertThatThrownBy(() -> new SubagentDefinition("x", " ", BODY, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("白名单写 Task 按定义错误拒绝：委派不嵌套")
    void whitelistNamingTaskIsRejected() {
        assertThatThrownBy(() -> new SubagentDefinition(
                "nested", "想再派一层", BODY, List.of(SubagentDelegation.TOOL_NAME), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SubagentDelegation.TOOL_NAME)
                .hasMessageContaining("不嵌套");
    }

    @Test
    @DisplayName("白名单点名了工具但当前没有它：拒绝并点名缺哪个，不悄悄少给")
    void whitelistNameMustExistInTheAvailableTools() {
        SubagentDefinition definition = new SubagentDefinition(
                "alpha", "管销售", BODY, List.of("missingTool"), List.of());

        assertThatThrownBy(() -> definition.selectTools(List.of(new RecordingTool("lookup", "1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missingTool");
    }

    @Test
    @DisplayName("没写白名单时继承全部，但委派工具自己一定被减掉")
    void inheritedToolsNeverIncludeDelegationItself() {
        SubagentDefinition definition = new SubagentDefinition(
                "alpha", "管销售", BODY, List.of(), List.of());
        RecordingTool lookup = new RecordingTool("lookup", "1");
        RecordingTool task = new RecordingTool(SubagentDelegation.TOOL_NAME, "递归");

        List<String> names = definition.selectTools(List.of(lookup, task)).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(names).containsExactly("lookup");
    }

    @Test
    @DisplayName("黑白名单算完一个工具都不剩就拒绝：没有工具的子 Agent 只能靠猜")
    void emptySelectionIsRejected() {
        SubagentDefinition definition = new SubagentDefinition(
                "alpha", "管销售", BODY, List.of(), List.of("lookup"));

        assertThatThrownBy(() -> definition.selectTools(List.of(new RecordingTool("lookup", "1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个可用工具都没有");
    }

    @Test
    @DisplayName("委派没带身份一律拒绝，子 Agent 一次都不启动")
    void delegationWithoutPrincipalIsDenied() {
        FixedChatModel model = new FixedChatModel("不该被调用");
        SubagentRunner runner = runner(model, List.of(), emptyRegistry());
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")), runner);

        String answer = delegation.call("{\"subagent\":\"alpha\",\"task\":\"查数\"}");

        assertThat(answer).startsWith(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    @DisplayName("参数对不上时不派出去，并告诉模型「派谁、做什么」两样都要")
    void malformedOrUnknownDelegationIsRejectedWithGuidance() {
        FixedChatModel model = new FixedChatModel("不该被调用");
        SubagentDelegation delegation = delegation(
                List.of(definitionObject("alpha", "lookup")),
                runner(model, List.of(), emptyRegistry()));
        ToolContext identity = new ToolContext(
                Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, Principal.readOnly("u-1")));

        String unknown = delegation.call("{\"subagent\":\"nobody\",\"task\":\"查数\"}", identity);
        String incomplete = delegation.call("{\"subagent\":\"alpha\"}", identity);

        assertThat(unknown).contains("没有名为 nobody").contains("alpha");
        assertThat(incomplete).contains("subagent").contains("task");
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    @DisplayName("成功委派只回传结论：子窗口里的工具明细不回到父，父的问答也不进子")
    void delegationReturnsOnlyTheConclusion() {
        RecordingTool lookup = new RecordingTool("lookup", "原始行 128400");
        AtomicInteger childCalls = new AtomicInteger();
        FixedChatModel child = new FixedChatModel(
                prompt -> childCalls.getAndIncrement() == 0
                        ? ChatPipelineTest.requestTool("lookup", "{}")
                        : ChatPipelineTest.answer("结论：128400"),
                List.of());
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(child, List.of(lookup), readsEverything("lookup")));
        ToolContext identity = new ToolContext(
                Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, Principal.readOnly("u-1")));

        String result = delegation.call("{\"subagent\":\"alpha\",\"task\":\"上周华东成交额\"}", identity);

        assertThat(result).isEqualTo("结论：128400").doesNotContain("原始行");
        assertThat(child.prompts()).hasSize(2);
        String childPrompt = child.prompts().getFirst().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(childPrompt).contains("上周华东成交额").contains(BODY);
        assertThat(delegation.getToolDefinition().description())
                .contains("alpha")
                .contains("管销售")
                .doesNotContain("先查数");
    }

    @Test
    @DisplayName("委派不提权：子 Agent 调的工具仍按父身份鉴权，越权时工具不执行")
    void delegatedToolStillUsesTheParentIdentity() {
        RecordingTool lookup = new RecordingTool("lookup", "128400");
        FixedChatModel child = new FixedChatModel(
                prompt -> ChatPipelineTest.requestTool("lookup", "{}"), List.of());
        Principal guest = Principal.of("guest-1", "guest", Set.of("region"), Set.of("read"));
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(child, List.of(lookup),
                        ToolPermissionRegistry.of(Map.of("lookup", ToolPermission.read("sales_order")))));
        ToolContext identity = new ToolContext(
                Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, guest));

        String result = delegation.call("{\"subagent\":\"alpha\",\"task\":\"查 sales_order\"}", identity);

        assertThat(result).contains(AuthorizationAdvisor.DENIAL_PREFIX).contains("sales_order");
        assertThat(lookup.calls()).isZero();
    }

    @Test
    @DisplayName("子 Agent 什么都没说时回一个明确的占位，不让父以为拿到了空结论")
    void emptyConclusionHasExplicitPlaceholder() {
        FixedChatModel child = new FixedChatModel("");
        SubagentDelegation delegation = delegation(List.of(definitionObject("alpha", "lookup")),
                runner(child, List.of(new RecordingTool("lookup", "1")), readsEverything("lookup")));
        ToolContext identity = new ToolContext(
                Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, Principal.readOnly("u-1")));

        String result = delegation.call("{\"subagent\":\"alpha\",\"task\":\"查数\"}", identity);

        assertThat(result).contains("没有给出结论");
    }

    @Test
    @DisplayName("装配把 Task 交进工具清单，权限登记是「可委派、不碰业务表」")
    void assemblyRegistersTheTaskToolAndItsPermission() throws IOException {
        write("alpha.md", definition("alpha", "管销售", "lookup", ""));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("lookup", new RecordingTool("lookup", "128400"));
        SubagentAutoConfiguration configuration = new SubagentAutoConfiguration();

        ToolCallback task = configuration.subagentToolCallback(
                directory.toString(),
                new FixedChatModel("好"),
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(ObservationRegistry.class),
                new AuthorizationAdvisor(List.of()));

        assertThat(task.getToolDefinition().name()).isEqualTo(SubagentDelegation.TOOL_NAME);
        assertThat(task.getToolDefinition().description()).contains("alpha").doesNotContain("先查数");

        beans.registerSingleton("task", task);
        assertThat(AgentAutoConfiguration.collectToolCallbacks(
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class)))
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("lookup", SubagentDelegation.TOOL_NAME);

        ToolPermission permission = configuration.subagentPermissionRegistry()
                .permissionFor(SubagentDelegation.TOOL_NAME)
                .orElseThrow();
        assertThat(permission.action()).isEqualTo(SubagentDelegation.ACTION);
        assertThat(permission.tables())
                .as("替委派登记业务表，等于给它一张绕过子链鉴权的通行证")
                .isEmpty();
    }

    @Test
    @DisplayName("tools 写成逐行列表、逗号分隔、方括号三种形态，挑出来的工具集一致")
    void toolListFormsParseToTheSameSelection() throws IOException {
        Path asList = writeListDefinition("list.md", "tools:", List.of("- querySales", "- lookup"));
        Path asComma = write("comma.md", definition("alpha", "管销售", "querySales, lookup", ""));
        Path asFlow = write("flow.md", definition("alpha", "管销售", "[querySales, lookup]", ""));

        List<String> expected = List.of("querySales", "lookup");
        assertThat(SubagentDefinition.parse(asList).tools()).isEqualTo(expected);
        assertThat(SubagentDefinition.parse(asComma).tools()).isEqualTo(expected);
        assertThat(SubagentDefinition.parse(asFlow).tools()).isEqualTo(expected);
    }

    @Test
    @DisplayName("写了 tools 却解析不出名字：按定义错误失败，绝不当成「继承全部」")
    void declaredButEmptyToolSectionIsRejected() throws IOException {
        Path emptyWhitelist = write("empty.md",
                "---\nname: alpha\ndescription: 管销售\ntools:\n---\n\n" + BODY + "\n");
        Path emptyBlacklist = write("empty-black.md",
                "---\nname: alpha\ndescription: 管销售\ndisallowedTools:\n---\n\n" + BODY + "\n");

        assertThatThrownBy(() -> SubagentDefinition.parse(emptyWhitelist))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tools")
                .hasMessageContaining("解析不出任何工具名");
        assertThatThrownBy(() -> SubagentDefinition.parse(emptyBlacklist))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disallowedTools")
                .hasMessageContaining("解析不出任何工具名");
    }

    @Test
    @DisplayName("示例里的复核员只拿得到 querySales：发邮件与跑脚本不在它的白名单里")
    void exampleReviewerOnlySeesItsOwnWhitelist() {
        SubagentDefinition definition = SubagentDefinition.parse(
                repositoryFile("examples/subagents/sql-reviewer.md"));
        List<ToolCallback> available = List.of(
                new RecordingTool("querySales", "128400"),
                new RecordingTool("mail_send", "发出去了"),
                new RecordingTool("run_script", "跑了"));

        assertThat(definition.tools()).containsExactly("querySales");
        assertThat(definition.selectTools(available))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("querySales");
    }

    private SubagentRunner runner(ChatModel model, List<ToolCallback> tools, ToolPermissionRegistry registry) {
        return new SubagentRunner(model, ObservationRegistry.NOOP, () -> tools,
                new AuthorizationAdvisor(List.of(registry)));
    }

    private static SubagentDelegation delegation(List<SubagentDefinition> definitions, SubagentRunner runner) {
        return new SubagentDelegation(definitions, runner);
    }

    private static SubagentDefinition definitionObject(String name, String tool) {
        return new SubagentDefinition(name, "管销售", BODY, List.of(tool), List.of());
    }

    private static ToolPermissionRegistry readsEverything(String... tools) {
        Map<String, ToolPermission> permissions = java.util.Arrays.stream(tools)
                .collect(java.util.stream.Collectors.toMap(tool -> tool,
                        tool -> ToolPermission.read("sales_order")));
        return ToolPermissionRegistry.of(permissions);
    }

    private static ToolPermissionRegistry emptyRegistry() {
        return ToolPermissionRegistry.of(Map.of());
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content);
    }

    private static String definition(String name, String description, String tools, String disallowed) {
        StringBuilder front = new StringBuilder("---\n")
                .append("name: ").append(name).append('\n')
                .append("description: ").append(description).append('\n');
        if (!tools.isBlank()) {
            front.append("tools: ").append(tools).append('\n');
        }
        if (!disallowed.isBlank()) {
            front.append("disallowedTools: ").append(disallowed).append('\n');
        }
        return front.append("---\n\n").append(BODY).append('\n').toString();
    }

    private Path writeListDefinition(String fileName, String toolsHeader, List<String> itemLines) throws IOException {
        StringBuilder front = new StringBuilder("---\nname: alpha\ndescription: 管销售\n")
                .append(toolsHeader).append('\n');
        itemLines.forEach(line -> front.append("  ").append(line).append('\n'));
        return write(fileName, front.append("---\n\n").append(BODY).append('\n').toString());
    }

    /** 从模块目录往上找仓库里的文件：surefire 的工作目录是模块，IDEA 里可能是仓库根。 */
    private static Path repositoryFile(String relative) {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("从 " + System.getProperty("user.dir") + " 往上找不到 " + relative);
    }
}
