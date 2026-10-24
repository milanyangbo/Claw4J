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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 委派这整条链：{@link SubagentDelegation} 对父呈现成什么、点下去回传什么，
 * 以及 {@link SubagentRunner} 在子窗口里到底放了什么。
 *
 * <p>同一份文件装两层，是因为它们共用一套装配与断言：委派是外壳，执行器是里子，
 * 而<b>隔离与提权这两件事只在两层接起来时才看得清</b>——单看外壳，"派出去没提权"
 * 无从判断；单看里子，"父那边看到的描述里有没有正文"也不在视野里。
 *
 * <p>模型由 {@link SubagentModelStub} 给出：这里要验的是编排器，不是模型会不会派。
 * 模型那一侧另有 {@code SubagentEndToEndTest} 连真模型走一遍。
 *
 * <p><b>每条隔离断言都配一条正向对照</b>：只断言"回传里没有中间轨迹"是不够的，
 * "子那一趟压根没跑"也能全绿。所以同一条用例里还要断言"工具确实被调用过"。
 */
class SubagentDelegationTest {

    private static final Principal READER = Principal.of("ethan", "read-only",
            Set.of(Principal.WILDCARD), Set.of(ToolPermission.READ, SubagentDelegation.ACTION));

    private static final Principal ADMIN = Principal.of("ethan", "admin",
            Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

    private static final String ANALYST_BODY = "你是销售分析员：先查数，再给结论。内部代号 SECRET。";

    // ------------------------------------------------------- 对父呈现成什么样子

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
