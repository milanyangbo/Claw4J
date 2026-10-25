package io.github.claw4j.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactionAdvisor;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.agent.session.SessionMemoryConfiguration;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springaicommunity.session.autoconfigure.SessionServiceProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个完整功能：入站安全。一次请求从通道进来，能不能调工具、出去的文字有没有原始号码。
 *
 * <p>鉴权失败若放行，越权就已经发生了；脱敏失败若放行，号码就永久留在会话表里。
 * 两者都不会让构建变红，只会让日志看起来一切正常——所以断言都落在"工具执行了几次"
 * 与"最终交出去、落库的那段文字"上。
 */
class InboundSecurityTest {

    private static final String PHONE = "13800138000";
    private static final String MASKED_PHONE = "138****8000";
    private static final String ID_CARD = "110101199003071234";

    @Test
    @DisplayName("未登记权限的工具一律拒绝，且工具一次都不执行")
    void rejectsToolWithoutRegisteredPermission() {
        RecordingTool tool = new RecordingTool("querySales", "42");
        AgentPort port = port(tool, toolCallModel("querySales"), List.of(
                new AuthorizationAdvisor(List.of(ToolPermissionRegistry.of(Map.of())))));

        OutboundMessage reply = port.reply(message(guest(), "本月成交额"));

        assertThat(reply.text()).contains(AuthorizationAdvisor.DENIAL_PREFIX).contains("未登记");
        assertThat(tool.calls.get()).as("被拒的那一轮不能先执行再过滤").isZero();
    }

    @Test
    @DisplayName("越权表拒绝：话术点名哪张表不满足，工具不执行")
    void rejectsToolTouchingForbiddenTable() {
        RecordingTool tool = new RecordingTool("querySales", "42");
        ToolPermissionRegistry registry = ToolPermissionRegistry.of(
                Map.of("querySales", ToolPermission.read("sales_order")));
        AgentPort port = port(tool, toolCallModel("querySales"), List.of(new AuthorizationAdvisor(List.of(registry))));

        OutboundMessage reply = port.reply(message(guest(), "本月成交额"));

        assertThat(reply.text()).contains(AuthorizationAdvisor.DENIAL_PREFIX).contains("sales_order");
        assertThat(tool.calls.get()).isZero();
    }

    @Test
    @DisplayName("越权动作拒绝：只读身份不能发邮件，拒绝话术说清是动作而不是表")
    void rejectsToolRequiringAnotherAction() {
        RecordingTool tool = new RecordingTool("sendEmail", "已发送");
        ToolPermissionRegistry registry = ToolPermissionRegistry.of(
                Map.of("sendEmail", ToolPermission.of("send_email")));
        AgentPort port = port(tool, toolCallModel("sendEmail"), List.of(new AuthorizationAdvisor(List.of(registry))));

        OutboundMessage reply = port.reply(message(Principal.readOnly("u-1"), "发一封邮件"));

        assertThat(reply.text()).contains(AuthorizationAdvisor.DENIAL_PREFIX).contains("send_email");
        assertThat(tool.calls.get()).isZero();
    }

    @Test
    @DisplayName("有权限时工具真的被调到：放行不是一句空话，答案来自工具结果")
    void allowsPermittedToolAndReallyRunsIt() {
        RecordingTool tool = new RecordingTool("querySales", "本月 42 万");
        ToolPermissionRegistry registry = ToolPermissionRegistry.of(
                Map.of("querySales", ToolPermission.read("sales_order")));
        AtomicInteger round = new AtomicInteger();
        FixedChatModel model = model(prompt -> round.getAndIncrement() == 0
                ? toolCall("querySales", "{}")
                : text("本月 42 万"));
        AgentPort port = port(tool, model, List.of(new AuthorizationAdvisor(List.of(registry))));

        OutboundMessage reply = port.reply(message(admin(), "本月成交额"));

        assertThat(reply.text()).isEqualTo("本月 42 万");
        assertThat(tool.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("入站先脱敏：模型与会话表看到的都是掩码，明文不会因为“存下来”而更持久")
    void masksInboundTextBeforeStoreAndModel() {
        Principal principal = Principal.readOnly("u-1");
        InMemorySessionRepository repository = InMemorySessionRepository.builder().build();
        SessionService sessions = new SessionMemoryConfiguration()
                .sessionService(repository, sessionProperties());
        SessionMemoryAdvisor memory = new SessionMemoryConfiguration().sessionMemoryAdvisor(sessions);
        FixedChatModel model = FixedChatModel.replying("收到");

        port(new RecordingTool("unused", ""), model, List.of(memory))
                .reply(new InboundMessage(principal, "feishu:sec-1", "我的手机号是 " + PHONE));

        assertThat(model.prompts().get(0).getContents())
                .contains(MASKED_PHONE)
                .doesNotContain(PHONE);
        List<String> stored = sessions.getMessages(SessionKeys.sessionIdOf(principal)).stream()
                .map(Message::getText)
                .toList();
        assertThat(stored).anySatisfy(text -> assertThat(text).contains(MASKED_PHONE));
        assertThat(stored).noneSatisfy(text -> assertThat(text).contains(PHONE));
    }

    @Test
    @DisplayName("出站脱敏：模型吐出的手机号与身份证号都按保留头尾打码")
    void masksOutboundPhoneAndIdCard() {
        FixedChatModel model = FixedChatModel.replying("手机号 " + PHONE + "，身份证 " + ID_CARD);

        OutboundMessage reply = port(new RecordingTool("unused", ""), model, List.of(new PiiRedactionAdvisor()))
                .reply(message(Principal.readOnly("u-1"), "查一下"));

        assertThat(reply.text())
                .contains(MASKED_PHONE)
                .contains("110101********1234")
                .doesNotContain(PHONE)
                .doesNotContain(ID_CARD);
    }

    @Test
    @DisplayName("日志里不留明文：出站日志是在脱敏之后打的，不是事后替换")
    void outboundLogHasNoPlainText() {
        FixedChatModel model = FixedChatModel.replying("您的手机号 " + PHONE + " 已登记");
        AgentPort port = port(new RecordingTool("unused", ""), model, List.of(new PiiRedactionAdvisor()));

        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientAgentPort.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            port.reply(message(Principal.readOnly("u-1"), "查一下"));
        }
        finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        List<String> logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logged).anySatisfy(line -> assertThat(line).contains("出站回答"));
        assertThat(logged).noneSatisfy(line -> assertThat(line).contains(PHONE));
        assertThat(logged).anySatisfy(line -> assertThat(line).contains(MASKED_PHONE));
    }

    @Test
    @DisplayName("流式里号码被拆在两块之间也不漏：尾巴扣住等下一块，拼起来是掩码")
    void masksPhoneSplitAcrossStreamChunks() {
        FixedChatModel model = new FixedChatModel(
                prompt -> text("不会走到这里"),
                prompt -> Flux.just(text("尾号 138001"), text("38000 已登记")));
        List<OutboundMessage> chunks = new ArrayList<>();

        port(new RecordingTool("unused", ""), model, List.of(new PiiRedactionAdvisor()))
                .stream(message(Principal.readOnly("u-1"), "查一下"), chunks::add);

        String answer = chunks.stream()
                .filter(chunk -> !chunk.finalChunk())
                .map(OutboundMessage::text)
                .reduce("", String::concat);
        assertThat(answer).contains(MASKED_PHONE).doesNotContain(PHONE);
    }

    @Test
    @DisplayName("流式也要挡越权：工具调用块一出现就换成拒绝，工具不执行")
    void rejectsUnauthorizedToolInStreamingPath() {
        RecordingTool tool = new RecordingTool("querySales", "42");
        ToolPermissionRegistry registry = ToolPermissionRegistry.of(
                Map.of("querySales", ToolPermission.read("sales_order")));
        FixedChatModel model = new FixedChatModel(
                prompt -> text("不会走到这里"),
                prompt -> Flux.just(toolCall("querySales", "{}")));
        List<OutboundMessage> chunks = new ArrayList<>();

        port(tool, model, List.of(new AuthorizationAdvisor(List.of(registry))))
                .stream(message(guest(), "本月成交额"), chunks::add);

        String answer = chunks.stream().map(OutboundMessage::text).reduce("", String::concat);
        assertThat(answer).contains(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(tool.calls.get()).isZero();
    }

    private static SessionServiceProperties sessionProperties() {
        SessionServiceProperties properties = new SessionServiceProperties();
        properties.setTimeToLive(Duration.ofHours(1));
        properties.setAllowSystemMessages(true);
        return properties;
    }

    private static Principal guest() {
        return Principal.of("u-1", "guest", Set.of("region"), Set.of(ToolPermission.READ));
    }

    private static Principal admin() {
        return Principal.of("u-1", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));
    }

    private static InboundMessage message(Principal principal, String text) {
        return new InboundMessage(principal, "feishu:sec", text);
    }

    private static FixedChatModel toolCallModel(String toolName) {
        return model(prompt -> toolCall(toolName, "{}"));
    }

    private static AgentPort port(ToolCallback tool, ChatModel model, List<Advisor> advisors) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < advisors.size(); i++) {
            beans.registerSingleton("advisor-" + i, advisors.get(i));
        }
        beans.registerSingleton("tool", tool);
        AgentAutoConfiguration configuration = new AgentAutoConfiguration();
        ChatClient client = configuration.chatClient(model,
                beans.getBeanProvider(Advisor.class),
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(ObservationRegistry.class));
        return configuration.agentPort(client);
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(value))))
                .build();
    }

    private static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments)))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }

    /** 模型只是文本来源：这一层断言的是鉴权与脱敏，不是模型会不会选工具。 */
    private static final class FixedChatModel implements ChatModel {

        private final Function<Prompt, ChatResponse> calls;
        private final Function<Prompt, Flux<ChatResponse>> streams;
        private final ChatOptions options = ToolCallingChatOptions.builder().build();
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        private FixedChatModel(Function<Prompt, ChatResponse> calls,
                               Function<Prompt, Flux<ChatResponse>> streams) {
            this.calls = calls;
            this.streams = streams;
        }

        static FixedChatModel replying(String answer) {
            return new FixedChatModel(prompt -> text(answer), prompt -> Flux.just(text(answer)));
        }

        List<Prompt> prompts() {
            return prompts;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return calls.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return streams.apply(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }
    }

    private static FixedChatModel model(Function<Prompt, ChatResponse> calls) {
        return new FixedChatModel(calls, prompt -> Flux.empty());
    }

    private static final class RecordingTool implements ToolCallback {

        private final String name;
        private final String result;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("测试工具 " + name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return result;
        }
    }
}
