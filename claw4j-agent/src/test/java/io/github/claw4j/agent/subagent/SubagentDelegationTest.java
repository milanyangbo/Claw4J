package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 委派动作对父呈现出来的样子：工具名、描述里有什么、以及各种"派不出去"的姿势。
 *
 * <p>它<b>不经过模型</b>——要验的是"拼给模型的描述里有什么"与"回调在各种输入下返回什么"，
 * 那两件事模型帮不上忙。模型那一侧（它会不会派、派给谁）在 {@code SubagentEndToEndTest}。
 */
class SubagentDelegationTest {

    private static final Principal READER = Principal.of("ethan", "read-only",
            Set.of(Principal.WILDCARD), Set.of(ToolPermission.READ, SubagentDelegation.ACTION));

    private static final String ANALYST_BODY = "你是销售分析员：先查数，再给结论。内部代号 SECRET。";

    @Test
    @DisplayName("工具名与动作：委派是它自己的一个动作，零张业务表")
    void 工具名与权限() {
        assertThat(SubagentDelegation.TOOL_NAME).isEqualTo("Task");
        assertThat(SubagentDelegation.permission().tables()).isEmpty();
        assertThat(SubagentDelegation.permission().action()).isEqualTo("delegate_task");
    }

    /**
     * 渐进式披露在委派上的延展：描述里只有名字与一句话它管什么，
     * 每份定义的正文（它怎么做）要等它真被派上用场才在它自己的窗口里出现。
     */
    @Test
    @DisplayName("描述里只有元数据，没有任何一份正文")
    void 描述里不含正文() {
        SubagentDelegation delegation = delegation();

        String description = delegation.getToolDefinition().description();

        assertThat(description)
                .contains("sales-analyst")
                .contains("查区域销售额")
                .contains("expense-auditor");
        assertThat(description)
                .as("正文进了描述就等于把十份流程说明常驻在父的提示词里")
                .doesNotContain("先查数")
                .doesNotContain("SECRET");
        assertThat(delegation.registrations()).hasSize(2);
    }

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
     * 没带身份的委派必须拒绝，而不是照常派出去。
     * 委派一旦脱离身份，子链上那道鉴权就成了摆设——
     * 于是"父越不过的界，派个专职 Agent 就过去了"。
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

    @Test
    @DisplayName("派给一个不存在的 Subagent：把可用清单回给它，而不是让它去猜名字")
    void 派给不存在的Subagent() {
        SubagentDelegation delegation = delegation();

        String answer = delegation.call("""
                {"subagent": "who-knows", "task": "查数"}""", contextOf(READER));

        assertThat(answer)
                .contains("没有名为 who-knows")
                .contains("sales-analyst")
                .contains("expense-auditor");
    }

    @Test
    @DisplayName("参数写错是模型看得懂的一句话，不是异常")
    void 参数写错给一句人话() {
        SubagentDelegation delegation = delegation();

        assertThat(delegation.call("""
                {"subagent": "sales-analyst"}""", contextOf(READER)))
                .contains("subagent")
                .contains("task");
    }

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

    // ------------------------------------------------------------------ 装配辅助

    private static SubagentDelegation delegation() {
        return new SubagentDelegation(
                List.of(analyst(List.of("querySales")),
                        new SubagentDefinition("expense-auditor", "审报销单。",
                                "逐条比对标准。", List.of(), List.of())),
                runner(SubagentModelStub.answering("结论"), List.of(StubTool.returning("querySales", "amount"))));
    }

    private static SubagentDefinition analyst(List<String> tools) {
        return new SubagentDefinition("sales-analyst", "查区域销售额并给出结论。", ANALYST_BODY, tools, List.of());
    }

    private static SubagentRunner runner(SubagentModelStub model, List<ToolCallback> tools) {
        return new SubagentRunner(model, ObservationRegistry.NOOP, () -> tools,
                new AuthorizationAdvisor(List.of(ToolPermissionRegistry.of(
                        Map.of("querySales", ToolPermission.read("sales_order"))))));
    }

    private static ToolContext contextOf(Principal principal) {
        return new ToolContext(Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal));
    }
}
