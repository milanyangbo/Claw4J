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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派这一层的外部行为：子窗口里有什么、回传给父的是什么、超预算会不会停、以及委派提不提权。
 *
 * <p>模型那一环在这里由 {@link SubagentModelStub} 给出（要验的是编排器，不是模型会不会派），
 * 端到端那一侧另有 {@code SubagentEndToEndTest} 连真模型走一遍。
 *
 * <p><b>每条隔离断言都配了一条正向对照</b>：只断言"回传里没有中间轨迹"是不够的——
 * "子那一趟压根没跑"也能全绿。所以同一条用例里还会断言"工具确实被调用过"
 * 与"轨迹在子窗口里出现过"。
 */
class SubagentRunnerTest {

    private static final Principal ADMIN =
            Principal.of("ethan", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

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
                .as("中间轨迹不该回传给父：它进父上下文就等于委派白做了一次隔离")
                .doesNotContain(trace);
        assertThat(conclusion)
                .as("子 Agent 自己的系统提示词同样不回流")
                .doesNotContain(secret);
        // 正向对照：子那一趟真的跑了，而轨迹确实出现在子窗口里。
        // 少了这两条，"什么都没发生"会让上面两条永远绿。
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
     * 工具返回"这一步失败了"而不是抛异常：抛异常会被框架直接冒泡，
     * 那样测的是"第一次失败就炸"，不是"一直失败时在上限处停下"。
     * 真实链路里工具也是这么做的（{@code SalesTools} catch 住数据库异常、返回原因），
     * 模型才有东西可改——自纠错能成立的前提就是这个。
     */
    @Test
    @DisplayName("子 Agent 也吃 3 次预算：一直失败就在上限处停下")
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

    @Test
    @DisplayName("委派不提权：父看不到的表，子也拿不到，而且是在执行之前被拦下")
    void 委派不提权() {
        Principal guest = Principal.of("guest", "guest", Set.of("region"),
                Set.of(ToolPermission.READ, SubagentDelegation.ACTION));
        StubTool querySales = StubTool.returning("querySales", "amount 128400");
        SubagentModelStub model = SubagentModelStub.requestingThen("querySales", "结论");

        String conclusion = runner(RecordingChatModel.of(model), List.of(querySales),
                        new AuthorizationAdvisor(List.of(ToolPermissionRegistry.of(
                                Map.of("querySales", ToolPermission.read("sales_order"))))))
                .delegate(new SubagentDefinition("analyst", "查数。", "查 sales_order。",
                        List.of("querySales"), List.of()), "查华东区成交额", guest);

        assertThat(conclusion)
                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                .contains("sales_order");
        assertThat(querySales.calls())
                .as("拒绝发生在执行之前：先跑完再过滤结果的话，越权在那一刻已经发生了")
                .isZero();
    }

    @Test
    @DisplayName("子链上用的就是父那个身份，不是一份被抬高的副本")
    void 身份原样透传() {
        PrincipalCapturingAdvisor capturing = new PrincipalCapturingAdvisor();
        SubagentDefinition definition = new SubagentDefinition(
                "analyst", "查数。", "查数。", List.of("traceStep"), List.of());
        StubTool tool = StubTool.returning("traceStep", "trace");

        new SubagentRunner(RecordingChatModel.of(SubagentModelStub.requestingThen("traceStep", "结论")),
                ObservationRegistry.NOOP, () -> List.of(tool), capturing)
                .delegate(definition, "做点什么", ADMIN);

        assertThat(capturing.principals()).isNotEmpty();
        assertThat(capturing.principals())
                .as("子链每一轮看到的都必须是委派时那个 Principal 本身")
                .allSatisfy(principal -> assertThat(principal).isSameAs(ADMIN));
    }

    // ------------------------------------------------------------------ 装配辅助

    private static SubagentRunner runner(RecordingChatModel model, List<ToolCallback> tools) {
        // 替身工具也要登记权限：没登记的一律按拒绝，那样断言就验不到"调用发生了几次"
        return runner(model, tools, new AuthorizationAdvisor(List.of(ToolPermissionRegistry.of(
                Map.of("traceStep", ToolPermission.of("trace"),
                        "flaky", ToolPermission.of("trace"))))));
    }

    private static SubagentRunner runner(RecordingChatModel model,
                                         List<ToolCallback> tools,
                                         AuthorizationAdvisor authorization) {
        return new SubagentRunner(model, ObservationRegistry.NOOP,
                () -> tools, authorization);
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
