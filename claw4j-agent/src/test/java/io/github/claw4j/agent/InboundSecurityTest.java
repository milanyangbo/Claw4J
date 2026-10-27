package io.github.claw4j.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactionAdvisor;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入站安全：一次工具调用能不能发生、说出去的内容长什么样。
 *
 * <p>链是测试自己挂的：这里验的是判定本身对不对（放行/拒绝/打码），
 * "装配有没有把 Advisor 挂上"归 app 的验收冒烟。
 */
class InboundSecurityTest {

    private static final String PHONE = "13812345678";
    private static final String ID_CARD = "110101199003071234";

    @Test
    @DisplayName("请求没带身份时工具一次都不执行，回答是明确的拒绝")
    void missingIdentityNeverReachesTheTool() {
        RecordingTool tool = new RecordingTool("lookup", "128400");
        ChatClient chat = client(new FixedChatModel(prompt -> ChatPipelineTest.requestTool("lookup", "{}")),
                List.of(new AuthorizationAdvisor(List.of(
                        ToolPermissionRegistry.of(Map.of("lookup", ToolPermission.read("sales_order")))))),
                List.of(tool));

        String answer = chat.prompt("查一下上周成交额").call().content();

        assertThat(answer).startsWith(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(tool.calls()).isZero();
    }

    @Test
    @DisplayName("没登记权限的工具按越权处理：默认拒绝而不是默认放行")
    void unregisteredToolIsDenied() {
        RecordingTool tool = new RecordingTool("lookup", "128400");
        FixedChatModel model = new FixedChatModel(prompt -> ChatPipelineTest.requestTool("lookup", "{}"));
        ChatClientAgentPort port = port(model, List.of(tool), ToolPermissionRegistry.of(Map.of()));

        String answer = port.reply(message("chat-1", "查成交额")).text();

        assertThat(answer)
                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                .contains("lookup")
                .contains("未登记");
        assertThat(tool.calls()).isZero();
    }

    @Test
    @DisplayName("只被授予 region 的身份调需要 sales_order 的工具：拒绝里点名表，工具不执行")
    void tableOverreachIsDeniedBeforeExecution() {
        RecordingTool tool = new RecordingTool("lookup", "128400");
        FixedChatModel model = new FixedChatModel(prompt -> ChatPipelineTest.requestTool("lookup", "{}"));
        ChatClientAgentPort port = port(model, List.of(tool),
                ToolPermissionRegistry.of(Map.of("lookup", ToolPermission.read("sales_order"))));

        Principal guest = Principal.of("guest-1", "guest", Set.of("region"), Set.of("read"));
        String answer = port.reply(new InboundMessage(guest, "chat-2", "查上周成交额")).text();

        assertThat(answer).contains(AuthorizationAdvisor.DENIAL_PREFIX).contains("sales_order");
        assertThat(tool.calls()).isZero();
    }

    @Test
    @DisplayName("动作不允许时也拒绝，并说清是动作而不是表")
    void actionOverreachExplainsTheAction() {
        RecordingTool tool = new RecordingTool("sendEmail", "已发送");
        FixedChatModel model = new FixedChatModel(prompt -> ChatPipelineTest.requestTool("sendEmail", "{}"));
        ChatClientAgentPort port = port(model, List.of(tool),
                ToolPermissionRegistry.of(Map.of("sendEmail", ToolPermission.of("send_email"))));

        String answer = port.reply(message("chat-3", "发一封邮件")).text();

        assertThat(answer).contains("send_email").contains("不允许执行");
        assertThat(tool.calls()).isZero();
    }

    @Test
    @DisplayName("有权限时工具真的被调到：别把「拒绝」测成永远拒绝")
    void permittedToolRunsAndItsResultReachesTheAnswer() {
        AtomicInteger modelCalls = new AtomicInteger();
        RecordingTool tool = new RecordingTool("lookup", "128400");
        FixedChatModel model = new FixedChatModel(
                prompt -> modelCalls.getAndIncrement() == 0
                        ? ChatPipelineTest.requestTool("lookup", "{}")
                        : ChatPipelineTest.answer("结果 128400"));
        ChatClientAgentPort port = port(model, List.of(tool),
                ToolPermissionRegistry.of(Map.of("lookup", ToolPermission.read("sales_order"))));

        String answer = port.reply(message("chat-4", "查成交额")).text();

        assertThat(tool.calls()).isEqualTo(1);
        assertThat(answer).contains("128400");
    }

    @Test
    @DisplayName("入站先打码：模型看不到用户输入的原始手机号")
    void inboundPhoneIsMaskedBeforeTheModelSeesIt() {
        FixedChatModel model = new FixedChatModel("收到");
        ChatClientAgentPort port = port(model, List.of(), ToolPermissionRegistry.of(Map.of()));

        port.reply(message("chat-5", "我的手机号是 " + PHONE + "，帮我记一下"));

        String sentToModel = textsOf(model.prompts().getLast().getInstructions());
        assertThat(sentToModel).doesNotContain(PHONE).contains("138****5678");
    }

    @Test
    @DisplayName("出站打码：模型真的吐出手机号与身份证号时，回答里也只有掩码")
    void outboundPiiIsMasked() {
        ChatClientAgentPort port = port(new FixedChatModel("联系 " + PHONE + "，证件 " + ID_CARD),
                List.of(), ToolPermissionRegistry.of(Map.of()));

        String answer = port.reply(message("chat-6", "给我留个联系方式")).text();

        assertThat(answer)
                .doesNotContain(PHONE)
                .doesNotContain(ID_CARD)
                .contains("138****5678")
                .contains("110101********1234");
    }

    @Test
    @DisplayName("手机号被流式切在两块中间时也不整号漏出")
    void phoneSplitAcrossStreamChunksIsStillMasked() {
        List<io.github.claw4j.domain.port.OutboundMessage> chunks = new ArrayList<>();
        ChatClientAgentPort port = port(new FixedChatModel(
                prompt -> ChatPipelineTest.answer(""),
                List.of("尾号 138", "12345678 结束")), List.of(), ToolPermissionRegistry.of(Map.of()));

        port.stream(message("chat-7", "报一下尾号"), chunks::add);

        String joined = String.join("", chunks.stream()
                .map(chunk -> chunk.text())
                .toList());
        assertThat(joined).doesNotContain(PHONE).contains("138****5678");
    }

    @Test
    @DisplayName("一整块都是号码前缀时先扣住不发：宁可晚一块，也不先吐半截原文")
    void chunkThatIsOnlyAPhonePrefixIsHeldBack() {
        List<io.github.claw4j.domain.port.OutboundMessage> chunks = new ArrayList<>();
        ChatClientAgentPort port = port(new FixedChatModel(
                prompt -> ChatPipelineTest.answer(""),
                List.of("1381234", "5678 结束")), List.of(), ToolPermissionRegistry.of(Map.of()));

        port.stream(message("chat-8", "报一下号码"), chunks::add);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().text()).isEqualTo("138****5678 结束");
        assertThat(chunks.getFirst().text()).doesNotContain(PHONE);
        assertThat(chunks.getLast().finalChunk()).isTrue();
    }

    @Test
    @DisplayName("出站日志里没有明文：脱敏发生在记录之前而不是事后替换")
    void outboundLogsCarryNoPlaintext() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientAgentPort.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            port(new FixedChatModel("联系 " + PHONE), List.of(), ToolPermissionRegistry.of(Map.of()))
                    .reply(message("chat-8", "留个电话"));

            List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(String.join("\n", logs))
                    .as("日志里应当出现的是掩码后的出站文本")
                    .contains("138****5678")
                    .doesNotContain(PHONE);
        }
        finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    @Test
    @DisplayName("流式路径上越权同样被拦：SSE 不能成为绕过鉴权的旁路")
    void overreachIsDeniedOnTheStreamPathToo() {
        RecordingTool tool = new RecordingTool("lookup", "128400");
        FixedChatModel model = new FixedChatModel(
                prompt -> ChatPipelineTest.requestTool("lookup", "{}"),
                prompt -> Flux.just(ChatPipelineTest.requestTool("lookup", "{}")));
        ChatClientAgentPort port = port(model, List.of(tool),
                ToolPermissionRegistry.of(Map.of("lookup", ToolPermission.read("sales_order"))));

        Principal guest = Principal.of("guest-2", "guest", Set.of("region"), Set.of("read"));
        List<io.github.claw4j.domain.port.OutboundMessage> chunks = new ArrayList<>();
        port.stream(new InboundMessage(guest, "chat-10", "查上周成交额"), chunks::add);

        assertThat(joinText(chunks))
                .contains(AuthorizationAdvisor.DENIAL_PREFIX)
                .contains("sales_order");
        assertThat(tool.calls()).isZero();
    }

    @Test
    @DisplayName("流式路径上没有工具调用时不误判：拒绝只在真要碰工具时发生")
    void streamWithoutToolCallsIsNotDenied() {
        FixedChatModel model = new FixedChatModel(
                prompt -> ChatPipelineTest.answer("上周 99999"),
                prompt -> Flux.just(ChatPipelineTest.answer("上周 99999")));
        ChatClientAgentPort port = port(model, List.of(), ToolPermissionRegistry.of(Map.of()));

        Principal nobody = Principal.of("nobody-1", "guest", Set.of(), Set.of());
        List<io.github.claw4j.domain.port.OutboundMessage> chunks = new ArrayList<>();
        port.stream(new InboundMessage(nobody, "chat-11", "你好"), chunks::add);

        assertThat(joinText(chunks))
                .contains("99999")
                .doesNotContain(AuthorizationAdvisor.DENIAL_PREFIX);
    }

    private static String joinText(List<io.github.claw4j.domain.port.OutboundMessage> chunks) {
        return chunks.stream().map(chunk -> chunk.text()).reduce("", String::concat);
    }

    private static String textsOf(List<Message> messages) {
        return messages.stream().map(Message::getText).reduce("", (left, right) -> left + "\n" + right);
    }

    private static ChatClientAgentPort port(ChatModel model,
                                            List<ToolCallback> tools,
                                            ToolPermissionRegistry registry) {
        return new ChatClientAgentPort(client(model,
                List.of(new AuthorizationAdvisor(List.of(registry)), new PiiRedactionAdvisor()),
                tools));
    }

    private static ChatClient client(ChatModel model, List<Advisor> advisors, List<ToolCallback> tools) {
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder()
                                .toolCallingManager(ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP)))
                .defaultAdvisors(advisors)
                .defaultToolCallbacks(tools)
                .build();
    }

    private static InboundMessage message(String chatId, String text) {
        return new InboundMessage(Principal.readOnly("u-1"), chatId, text);
    }
}
