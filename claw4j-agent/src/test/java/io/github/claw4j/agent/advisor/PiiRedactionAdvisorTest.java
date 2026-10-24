package io.github.claw4j.agent.advisor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.ChatClientAgentPort;
import io.github.claw4j.agent.model.FixedChatModel;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站脱敏的行为断言。
 *
 * <p><b>这一批是不经过模型的组件级测试（ADR-0011）。</b>原来靠脚本化假模型"让模型吐出
 * 这个带号码的字符串"——换真模型之后做不到：真模型不会复述一个指定的号码，
 * 更不会精确地把它放在句子中间好让分块切在号码上。
 * 于是"模型这一轮说了什么"由测试直接构造（等价于模型输出了这句话），
 * **Advisor 本身是真的**，脱敏逻辑、分块拼装、日志落点都照旧被测到。
 *
 * <p>三条要分开看：回答是掩码的、流式下分块边界没把掩码切坏、日志里也是掩码的。
 * 第三条才是"脱敏发生在出站之前"的证明——只在响应里脱敏、日志里仍是明文，等于没脱。
 * 它仍然走一次真模型（{@link #出站日志里也是掩码后的值}），因为要验的是
 * {@code AgentPort} 出站那一行日志，不进那条链就看不到那行日志。
 */
class PiiRedactionAdvisorTest {

    private static final String PHONE = "13812345678";

    private static final String MASKED_PHONE = "138****5678";

    private static final String ID_CARD = "110101199003071234";

    private static final String MASKED_ID_CARD = "110101********1234";

    /** 两个号码都在句子中间，流式按小块切时**必然**被切在号码上。 */
    private static final String ANSWER = "联系人张三，手机 " + PHONE + "，证件 " + ID_CARD + "。";

    @Test
    void 同步路径输出里是掩码且原始值不出现() {
        ChatClientResponse out = new PiiRedactionAdvisor()
                .adviseCall(request(), ChainStubs.callReturning(ChainStubs.responseWithText(ANSWER)));

        assertThat(textOf(out))
                .contains(MASKED_PHONE)
                .contains(MASKED_ID_CARD)
                .doesNotContain(PHONE)
                .doesNotContain(ID_CARD);
    }

    @Test
    void 流式路径分块边界不会漏掉号码() {
        // 按 8 字切：号码一定被切在块边界上，正是最容易漏的那种形状
        Flux<ChatClientResponse> stream = new PiiRedactionAdvisor()
                .adviseStream(request(), ChainStubs.streamReturning(
                        chunks(ANSWER, 8).stream().map(ChainStubs::responseWithText).toList()));

        List<String> pieces = new ArrayList<>();
        stream.doOnNext(chunk -> pieces.add(textOf(chunk)))
                .blockLast();

        assertThat(pieces.size()).isGreaterThan(1);
        String streamed = String.join("", pieces);
        assertThat(streamed)
                .contains(MASKED_PHONE)
                .contains(MASKED_ID_CARD)
                .doesNotContain(PHONE)
                .doesNotContain(ID_CARD);
    }

    /** 对照：没有这个 Advisor 时原始值原样出来。没有这条，"Advisor 什么都没做"也能全绿。 */
    @Test
    void 没有脱敏Advisor时原始值原样出站() {
        ChatClientResponse raw = ChainStubs.callReturning(ChainStubs.responseWithText(ANSWER))
                .nextCall(request());

        assertThat(textOf(raw)).contains(PHONE).contains(ID_CARD);
    }

    /**
     * 请求里带着真实号码时，整条链路上都不该出现明文。
     *
     * <p><b>它验的是入站脱敏，不是出站脱敏</b>——这点必须说清楚，否则这条用例是骗人的：
     * 号码由用户消息提供，而 {@code ChatClientAgentPort} 在交给链之前就已经把它掩码了，
     * 模型拿到的是 {@code 138****5678}，它"复述"出来的本来就是掩码串。
     * 于是下面三条断言全部由入站那一层满足，出站脱敏做没做都一样绿。
     * 出站脱敏由 {@link #出站脱敏在完整链路上生效} 单独验。
     *
     * <p>它仍然值得跑真模型：这是唯一一条"号码真的走了一遍真实模型与真实链路"的用例，
     * 验的是入站脱敏之后没有任何一环把明文带回来（会话、工具结果、日志）。
     *
     * <p>断言里"含掩码后的值"这一半不能省：`不含原始值` 单独看是恒真的
     * （什么都不打日志它也成立）。用挂在 logger 上的 {@code ListAppender}
     * 而不是抓 System.out：后者抓不到"日志系统在替换 stdout 之前就已经初始化"的那些输出，
     * 而且是否抓得到取决于同 JVM 里有没有别的测试先碰过日志——
     * 这种依赖测试执行顺序的断言，红了也不知道是真漏还是没抓到。
     */
    @Test
    void 入站脱敏之后整条链路上都不落明文() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientAgentPort.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);

        try {
            runner().run(context -> RealModelSupport.eventually(2, "入站脱敏后不落明文", () -> {
                OutboundMessage out = context.getBean(AgentPort.class)
                        .reply(new InboundMessage(Principal.readOnly("u-1"), "http:pii-test", ask()));
                assertThat(out.text()).doesNotContain(PHONE);

                assertLogsAreMasked(captured);
                return null;
            }));
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(originalLevel);
        }
    }

    /**
     * 出站脱敏在完整 {@code AgentPort} 链路上生效，且出站日志打在脱敏**之后**。
     *
     * <p>号码不经过用户消息，而是由"模型这一轮说了什么"提供（{@link FixedChatModel}），
     * 这样入站脱敏对它无事可做，能把它变成掩码的只有出站那一次。
     * 反过来，若号码由用户消息提供（上一条就是），断言会被入站那一层满足，
     * 这条用例就退化成"什么都不做也绿"——曾经正是这样。
     *
     * <p>它抓的是两类真实故障：脱敏 Advisor 没挂上链（回答与日志都是明文），
     * 以及有人在链路之外打日志（回答是掩码但日志是明文）。
     * 后者尤其值得盯：{@code ChatClientAgentPort} 的出站日志敢打全文，
     * 唯一的理由就是它打的是脱敏之后的内容。
     */
    @Test
    void 出站脱敏在完整链路上生效() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientAgentPort.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);

        try {
            runnerReplying(ANSWER).run(context -> {
                OutboundMessage out = context.getBean(AgentPort.class)
                        .reply(new InboundMessage(Principal.readOnly("u-pii-out"), "http:pii-out",
                                "张三的手机号是多少"));

                assertThat(out.text())
                        .as("出站回答里必须是掩码，明文号码出现说明脱敏没挂上链")
                        .contains(MASKED_PHONE)
                        .doesNotContain(PHONE)
                        .doesNotContain(ID_CARD);
                assertLogsAreMasked(captured);
            });
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(originalLevel);
        }
    }

    /**
     * 脱敏在工具循环外面：整通对话只做一次，工具结果与模型中间轮次都还是原值。
     *
     * <p>注意别写 {@code HIGHEST_PRECEDENCE + 200 - 1000} 这种式子：
     * {@code HIGHEST_PRECEDENCE} 是 {@code Integer.MIN_VALUE}，减下去会绕回正数。
     */
    @Test
    void order排在工具循环外面() {
        assertThat(new PiiRedactionAdvisor().getOrder())
                .isLessThan(ToolCallingAdvisor.DEFAULT_ORDER)
                .isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
    }

    private static String ask() {
        return "我的手机号是 " + PHONE + "，请把它原样写进你的回答。";
    }

    private static ChatClientRequest request() {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("张三的手机号是多少"))))
                .build();
    }

    private static List<String> chunks(String text, int size) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            pieces.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return pieces;
    }

    private static String textOf(ChatClientResponse response) {
        return ChainStubs.textOf(response);
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel);
    }

    /**
     * 与 {@link #runner()} 唯一的区别是模型换成"按指定文本作答"的桩。
     * 只在要验的东西不在模型身上时用（{@link FixedChatModel} 的注释写了边界）。
     */
    private static ApplicationContextRunner runnerReplying(String modelReply) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> FixedChatModel.replying(modelReply));
    }

    /**
     * 日志里没有明文、且确实打过掩码值。两半都要：`不含明文` 单独看是恒真的
     * （不打日志它也成立），`含掩码` 才证明这条日志真的打了。
     */
    private static void assertLogsAreMasked(ListAppender<ILoggingEvent> captured) {
        List<String> messages = captured.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(m -> m.contains(PHONE) || m.contains(ID_CARD));
        assertThat(messages).anyMatch(m -> m.contains(MASKED_PHONE));
    }
}
