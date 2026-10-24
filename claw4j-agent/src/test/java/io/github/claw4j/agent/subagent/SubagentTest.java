package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.ToolCallBudget;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.model.RecordingChatModel;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子 Agent 这一整条链：<b>一份定义怎么被读出来</b> → <b>它在可用工具里挑哪些</b> →
 * <b>对父呈现成什么</b> → <b>点下去回传什么</b>。
 *
 * <p>四段合一份文件，是因为它们只在连起来时才说得清一件事：
 * <b>隔离与不提权</b>。单看定义，"白名单写错"只是配置问题；单看外壳，
 * "派出去没提权"无从判断；单看里子，"父那边看到的描述里有没有正文"不在视野里。
 *
 * <p>每一段守的失效姿势都不一样，但都不是"抛错"：
 * <ul>
 *   <li><b>配置写错</b> → 子 Agent 悄悄少了能力（少给一个工具、白名单点错名字），
 *       在父那边看起来只是"这次没查出来"。
 *   <li><b>隔离漏了</b> → 中间轨迹与子自己的系统提示词回流，父的上下文被撑大，
 *       隔离等于白做了一次。
 *   <li><b>提权了</b> → 父越不过的界，派个专职 Agent 就过去了。
 * </ul>
 *
 * <p>模型由 {@link SubagentModelStub} 给出：这里要验的是编排器，不是模型会不会派。
 * 模型那一侧另有 {@code SubagentEndToEndTest} 连真模型走一遍。
 *
 * <p><b>每条隔离断言都配一条正向对照</b>：只断言"回传里没有中间轨迹"是不够的，
 * "子那一趟压根没跑"也能全绿。所以同一条用例里还要断言"工具确实被调用过"。
 */
class SubagentTest {

    private static final String BODY = """
            你是销售分析员。
            1. 先用 querySales 查出这个区域的成交额。
            2. 只输出数字，不要复述工具返回的原始内容。

            内部代号 SUBAGENT-SECRET。
            """;

    private static final Principal READER = Principal.of("ethan", "read-only",
            Set.of(Principal.WILDCARD), Set.of(ToolPermission.READ, SubagentDelegation.ACTION));

    private static final Principal ADMIN = Principal.of("ethan", "admin",
            Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

    private static final String ANALYST_BODY = "你是销售分析员：先查数，再给结论。内部代号 SECRET。";

    // -------------------------------------------------------------- 定义怎么读出来

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

    // ---------------------------------------------------------- 对父呈现成什么样子

    /**
     * 渐进式披露在委派上的延展：父那边只看到"有哪几个专职 Agent、各自管什么"，
     * 每份定义的正文（它怎么做）要等它真被派上用场才在它自己的窗口里出现。
     *
     * <p>名字与动作也要一起钉住：{@code Task} 是模型点得到的那把钥匙，
     * {@code delegate_task} 是鉴权表里对应的一行。改任何一个，都是"模型点了没反应"
     * 或者"点了被拒"，而两种表现都不带错误信息。
     */
    @Test
    @DisplayName("对父只露出名字与一句话：没有正文、没有系统提示词")
    void 描述里只有元数据() {
        SubagentDelegation delegation = delegation();

        assertThat(SubagentDelegation.TOOL_NAME).isEqualTo("Task");
        assertThat(SubagentDelegation.permission().tables()).isEmpty();
        assertThat(SubagentDelegation.permission().action()).isEqualTo("delegate_task");

        String description = delegation.getToolDefinition().description();
        assertThat(description)
                .contains("sales-analyst")
                .contains("查区域销售额")
                .contains("expense-auditor");
        assertThat(description)
                .as("正文进了描述就等于把每一份流程说明常驻在父的提示词里")
                .doesNotContain("先查数")
                .doesNotContain("SECRET");
        assertThat(delegation.registrations()).hasSize(2);
    }

    // --------------------------------------------------------------- 派出去的姿势

    @Test
    @DisplayName("委派出去：回传的是子 Agent 的结论")
    void 正常委派() {
        StubTool tool = StubTool.returning("querySales", "amount 128400");
        SubagentDelegation delegation = new SubagentDelegation(
                List.of(analyst(List.of("querySales"))),
                runner(SubagentModelStub.requestingThen("querySales", "结论：128400"), List.of(tool)));

        String conclusion = delegation.call("""
                {"subagent": "sales-analyst", "task": "查华东区上季度成交额"}""",
                contextOf(READER));

        assertThat(conclusion).contains("结论：128400");
        assertThat(tool.calls()).isEqualTo(1);
    }

    /**
     * 没带身份的委派必须拒绝，而不是照常派出去。委派一旦脱离身份，
     * 子链上那道鉴权就成了摆设——于是"父越不过的界，派个专职 Agent 就过去了"。
     */
    @Test
    @DisplayName("没有携带身份的委派一律拒绝")
    void 没有身份即拒绝() {
        SubagentDelegation delegation = delegation();

        assertThat(delegation.call("""
                {"subagent": "sales-analyst", "task": "查数"}""", null))
                .contains(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(delegation.call("""
                {"subagent": "sales-analyst", "task": "查数"}""", new ToolContext(Map.of())))
                .contains(AuthorizationAdvisor.DENIAL_PREFIX);
    }

    /**
     * 参数不对时给回一句模型看得懂的话，而不是抛异常：抛出去就是整轮 500，
     * 而模型本来是可以自己改一版再派的。点错名字时连可用清单一起回，
     * 免得它在那儿猜"到底有哪几个"。
     */
    @Test
    @DisplayName("输入不对时回一句人话：点错名字给清单，缺参数说缺哪个")
    void 输入不对时给一句人话() {
        SubagentDelegation delegation = delegation();

        assertThat(delegation.call("""
                {"subagent": "who-knows", "task": "查数"}""", contextOf(READER)))
                .contains("没有名为 who-knows")
                .contains("sales-analyst")
                .contains("expense-auditor");
        assertThat(delegation.call("""
                {"subagent": "sales-analyst"}""", contextOf(READER)))
                .contains("subagent")
                .contains("task");
    }

    /** 子那一侧炸了要包成工具执行异常：父的循环认得这个异常，才有机会换条路。 */
    @Test
    @DisplayName("子那一侧炸了：包成工具执行异常，让父有机会换条路")
    void 子那一侧炸了包成工具异常() {
        SubagentDelegation delegation = new SubagentDelegation(
                List.of(analyst(List.of("missing"))),
                runner(SubagentModelStub.answering("结论"), List.of()));

        assertThatThrownBy(() -> delegation.call("""
                {"subagent": "sales-analyst", "task": "查数"}""", contextOf(READER)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("missing");
    }

    // -------------------------------------------------- 子窗口里有什么、回传什么

    /**
     * 委派的全部价值在隔离：中间轨迹与子自己的系统提示词都留在子窗口里，
     * 回给父的只有结论。轨迹一旦回流，父的上下文被撑大，隔离等于白做了一次。
     */
    @Test
    @DisplayName("回传给父的只有结论：中间轨迹与系统提示词都不回流")
    void 只回传结论() {
        String trace = "TRACE-" + Long.toHexString(System.nanoTime());
        String secret = "SECRET-" + Long.toHexString(System.nanoTime());
        StubTool tool = StubTool.returning("traceStep", "中间过程 " + trace + "：先取数、再核对、再算合计");
        RecordingChatModel model = RecordingChatModel.of(
                SubagentModelStub.requestingThen("traceStep", "结论：华东 128400"));
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", "你是分析员，内部代号 " + secret + "。", List.of("traceStep"), List.of());

        String conclusion = runner(model, List.of(tool)).delegate(definition, "查华东区上季度成交额", ADMIN);

        assertThat(conclusion).contains("结论：华东 128400");
        assertThat(conclusion)
                .as("中间轨迹不该回传给父")
                .doesNotContain(trace);
        assertThat(conclusion)
                .as("子 Agent 自己的系统提示词同样不回流")
                .doesNotContain(secret);
        // 正向对照：子那一趟真的跑了，而轨迹确实出现在子窗口里
        assertThat(tool.calls()).isEqualTo(1);
        assertThat(everythingModelSaw(model)).contains(trace);
    }

    @Test
    @DisplayName("子 Agent 能看到哪些工具由定义白名单决定")
    void 白名单决定子的工具清单() {
        List<ToolCallback> tools = List.of(
                StubTool.returning("querySales", "amount"),
                StubTool.returning("traceStep", "trace"));
        RecordingChatModel model = RecordingChatModel.of(SubagentModelStub.answering("结论"));
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", "只查数。", List.of("traceStep"), List.of());

        runner(model, tools).delegate(definition, "做点什么", ADMIN);

        assertThat(model.toolNamesOfLastRequest())
                .as("委派不等于把全部能力交出去：白名单之外的工具子在它的窗口里根本看不见")
                .containsExactly("traceStep");
    }

    /**
     * 工具返回"这一步失败了"而不是抛异常：抛异常会被框架直接冒泡，那样测的是
     * "第一次失败就炸"，不是"一直失败时在上限处停下"。真实工具也是这么做的
     * （{@code SalesTools} catch 住数据库异常、返回原因），模型才有东西可改——
     * 自纠错能成立的前提就是这个。
     */
    @Test
    @DisplayName("子 Agent 也吃同一个预算：一直失败就在上限处停下")
    void 子也受调用预算约束() {
        StubTool flaky = StubTool.returning("flaky", "这一步失败了：原因不明");
        SubagentModelStub model = SubagentModelStub.alwaysRequesting("flaky");
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", "一直重试。", List.of("flaky"), List.of());

        runner(RecordingChatModel.of(model), List.of(flaky)).delegate(definition, "做点什么", ADMIN);

        assertThat(flaky.calls())
                .as("委派不能变成绕过预算的口子：子那一侧用的是同一份 3 / 6")
                .isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
        assertThat(model.calls()).isPositive();
    }

    /**
     * 委派不提权：父看不到的表，子也拿不到，而且<b>是在执行之前被拦下</b>——
     * 先跑完再过滤结果的话，越权在那一刻已经发生了。
     */
    @Test
    @DisplayName("委派不提权：父看不到的表，子也拿不到")
    void 委派不提权() {
        Principal guest = Principal.of("guest", "guest", Set.of("region"),
                Set.of(ToolPermission.READ, SubagentDelegation.ACTION));
        StubTool querySales = StubTool.returning("querySales", "amount 128400");

        String conclusion = runner(RecordingChatModel.of(
                        SubagentModelStub.requestingThen("querySales", "结论")),
                        List.of(querySales),
                        new AuthorizationAdvisor(List.of(ToolPermissionRegistry.of(
                                Map.of("querySales", ToolPermission.read("sales_order"))))))
                .delegate(new SubagentDefinition("analyst", "查数。", "查 sales_order。",
                        List.of("querySales"), List.of()), "查华东区成交额", guest);

        assertThat(conclusion)
                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                .contains("sales_order");
        assertThat(querySales.calls())
                .as("拒绝发生在执行之前")
                .isZero();
    }

    /** 子链上用的就是父那个身份，不是一份被抬高的副本。 */
    @Test
    @DisplayName("子链每一轮看到的都是委派时那个 Principal 本身")
    void 身份原样透传() {
        PrincipalCapturingAdvisor capturing = new PrincipalCapturingAdvisor();
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", "查数。", List.of("traceStep"), List.of());
        StubTool tool = StubTool.returning("traceStep", "trace");

        new SubagentRunner(RecordingChatModel.of(SubagentModelStub.requestingThen("traceStep", "结论")),
                ObservationRegistry.NOOP, () -> List.of(tool), capturing)
                .delegate(definition, "做点什么", ADMIN);

        assertThat(capturing.principals()).isNotEmpty();
        assertThat(capturing.principals()).allSatisfy(principal -> assertThat(principal).isSameAs(ADMIN));
    }

    // ------------------------------------------------------------------ 装配辅助

    private static List<String> toolNames(List<ToolCallback> callbacks) {
        return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static Path write(Path dir, String fileName, String content) throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

    private static SubagentDelegation delegation() {
        return new SubagentDelegation(
                List.of(analyst(List.of("querySales")),
                        new SubagentDefinition("expense-auditor", "审报销单。",
                                "逐条比对标准。", List.of(), List.of())),
                runner(SubagentModelStub.answering("结论"),
                        List.of(StubTool.returning("querySales", "amount"))));
    }

    private static SubagentDefinition analyst(List<String> tools) {
        return new SubagentDefinition("sales-analyst", "查区域销售额并给出结论。", ANALYST_BODY, tools, List.of());
    }

    /**
     * 替身工具也要登记权限：没登记的一律按拒绝，那样断言就验不到"调用发生了几次"，
     * 而"零次"正是隔离与提权那几条的反向判据。
     */
    private static SubagentRunner runner(ChatModel model, List<ToolCallback> tools) {
        return runner(model, tools, new AuthorizationAdvisor(List.of(ToolPermissionRegistry.of(
                Map.of("querySales", ToolPermission.read("sales_order"),
                        "traceStep", ToolPermission.of("trace"),
                        "flaky", ToolPermission.of("trace"))))));
    }

    private static SubagentRunner runner(ChatModel model, List<ToolCallback> tools,
                                         AuthorizationAdvisor authorization) {
        return new SubagentRunner(model, ObservationRegistry.NOOP, () -> tools, authorization);
    }

    private static ToolContext contextOf(Principal principal) {
        return new ToolContext(Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal));
    }

    /**
     * 把探针记下来的每一轮请求摊平成一个字符串：消息正文、工具调用、工具结果。
     * 隔离的漏洞可能出现在其中任何一处，只查一处的断言漏的那处就是假绿。
     */
    private static String everythingModelSaw(RecordingChatModel model) {
        StringBuilder dump = new StringBuilder();
        for (Prompt prompt : model.prompts()) {
            for (Message message : prompt.getInstructions()) {
                dump.append(message.getText());
                if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                    assistant.getToolCalls()
                            .forEach(call -> dump.append(call.name()).append(call.arguments()));
                }
                if (message instanceof ToolResponseMessage responses) {
                    responses.getResponses()
                            .forEach(response -> dump.append(response.name()).append(response.responseData()));
                }
            }
        }
        return dump.toString();
    }
}
