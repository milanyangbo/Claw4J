package io.github.claw4j.agent.approval;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.ApprovalLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 非 exec 工具的那道闸（票 05）：执行前问一次，没批准就<b>回一条工具结果</b>。
 *
 * <p>判据的重点不是"拦住了"，是"拦的方式"：抛异常会让框架把这一轮打成失败，或者让模型拿到
 * 一句"出错了"；回一条带理由的结果，模型才知道"这件事在等谁批准、不要重试"。所以这里断言
 * 的是返回值的内容，以及 delegate 一次都没被调到。
 */
class ApprovalRequiredToolCallbackTest {

    private static final String TOOL = "sendEmail";

    private static final Principal MAILER = new Principal("u-mailer");

    @Test
    @DisplayName("没有批准：回一条带编号与「不要重试」的结果，delegate 一次都不执行")
    void withoutApprovalItReturnsAResultInsteadOfThrowing() {
        RecordingTool tool = new RecordingTool(TOOL);
        RecordingGate gate = new RecordingGate();
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{\"to\":\"zhang@example.com\"}", context());

        assertThat(tool.calls()).isZero();
        assertThat(result)
                .contains("先审批")
                .contains("ap-1")
                .contains("没有执行")
                .contains("不要重试")
                .contains("没有新的输出");
        assertThat(gate.asked).isEqualTo(1);
        assertThat(gate.lastAsk.owner()).isEqualTo("u-mailer");
        assertThat(gate.lastAsk.toolName()).isEqualTo(TOOL);
        assertThat(gate.lastAsk.origin()).isNotNull();
        assertThat(gate.lastAsk.origin().sessionKey()).isEqualTo("claw4j:user:u-mailer:http:chat-ap");
    }

    @Test
    @DisplayName("已有批准：直接执行，不再问")
    void anExistingGrantRunsTheTool() {
        RecordingTool tool = new RecordingTool(TOOL);
        RecordingGate gate = new RecordingGate();
        gate.granted = true;
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{\"to\":\"zhang@example.com\"}", context());

        assertThat(tool.calls()).isEqualTo(1);
        assertThat(result).isEqualTo("已发送");
        assertThat(gate.asked).isZero();
    }

    @Test
    @DisplayName("没有身份：不登记也不执行——批给谁没有答案")
    void withoutIdentityItRefusesWithoutAsking() {
        RecordingTool tool = new RecordingTool(TOOL);
        RecordingGate gate = new RecordingGate();
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{}", null);

        assertThat(tool.calls()).isZero();
        assertThat(result).startsWith("没有权限");
        assertThat(gate.asked).isZero();
    }

    @Test
    @DisplayName("参数不成 JSON 对象：不执行，也不登记一条看不懂的请求")
    void malformedArgumentsAreRefused() {
        RecordingTool tool = new RecordingTool(TOOL);
        RecordingGate gate = new RecordingGate();
        ApprovalRequiredToolCallback callback = new ApprovalRequiredToolCallback(tool, gate);

        String result = callback.call("{\"to\":", context());

        assertThat(tool.calls()).isZero();
        assertThat(result).contains("不是一段完整的 JSON");
        assertThat(gate.asked).isZero();
    }

    @Test
    @DisplayName("只包名单里那几个：读类工具没有这道闸，不会每读一次弹一次")
    void onlyListedToolsAreGated() {
        List<ToolCallback> tools = List.of(new RecordingTool("readFile"), new RecordingTool(TOOL));

        List<ToolCallback> gated = io.github.claw4j.agent.AgentAutoConfiguration.withApprovalGate(
                tools, new RecordingGate(), new ApprovalRequiredTools(Set.of(TOOL)), null);

        assertThat(gated.get(0)).isSameAs(tools.get(0));
        assertThat(gated.get(1)).isInstanceOf(ApprovalRequiredToolCallback.class);
    }

    @Test
    @DisplayName("名单是空的时候一个都不包：默认部署不变成「每步都要批」")
    void anEmptyListWrapsNothing() {
        List<ToolCallback> tools = List.of(new RecordingTool(TOOL));

        assertThat(io.github.claw4j.agent.AgentAutoConfiguration.withApprovalGate(
                tools, new RecordingGate(), ApprovalRequiredTools.none(), null))
                .containsExactlyElementsOf(tools);
    }

    private static ToolContext context() {
        return new ToolContext(Map.of(
                ContextKeys.PRINCIPAL, MAILER,
                ContextKeys.RUN_ID, "run-1",
                ContextKeys.CHANNEL, "http",
                ContextKeys.CHAT_REF, "chat-ap",
                org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                "claw4j:user:u-mailer:http:chat-ap"));
    }

    /** 只回一句话的工具桩：验的是"有没有被调到"，不是它做了什么。 */
    private static final class RecordingTool implements ToolCallback {

        private final String name;

        private int calls;

        private RecordingTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("用例").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return "已发送";
        }

        private int calls() {
            return calls;
        }
    }

    /** 假审批门：记下被问了什么，按 {@code granted} 决定兑现与否。 */
    private static final class RecordingGate implements ApprovalGate {

        private boolean granted;

        private int asked;

        private ApprovalLedger.Ask lastAsk;

        @Override
        public Optional<ApprovalLedger.Grant> redeem(String owner, String toolName, String argsDigest) {
            return granted
                    ? Optional.of(new ApprovalLedger.Grant("ap-old", java.time.Instant.now()))
                    : Optional.empty();
        }

        @Override
        public Optional<String> ask(ApprovalLedger.Ask ask) {
            asked++;
            lastAsk = ask;
            return Optional.of("ap-1");
        }
    }
}
