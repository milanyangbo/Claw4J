package io.github.claw4j.agent.advisor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.ChatClientAgentPort;
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
     * 原始值不落日志。断言分两半才有意义：`不含原始值` 单独看是恒真的
     * （什么都不打日志它也成立），所以同时断言 `含掩码后的值`——
     * 证明这条日志真的打了、且打的是脱敏之后的内容。
     *
     * <p>用挂在 logger 上的 {@code ListAppender} 而不是抓 System.out：
     * 后者抓不到"日志系统在替换 stdout 之前就已经初始化"的那些输出，
     * 而且是否抓得到取决于同 JVM 里有没有别的测试先碰过日志——
     * 这种依赖测试执行顺序的断言，红了也不知道是真漏还是没抓到。
     *
     * <p>这一条走真模型：请求里带着真实号码，模型会在回答里复述它（温度 0 下稳定），
     * 于是出站日志里那个号码必须是掩码形态。带了两次重试，因为"复述号码"这一环
     * 终究是模型的行为，不是我们能强制的。
     */
    @Test
    void 出站日志里也是掩码后的值() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientAgentPort.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);

        try {
            runner().run(context -> RealModelSupport.eventually(2, "出站日志里是掩码", () -> {
                OutboundMessage out = context.getBean(AgentPort.class)
                        .reply(new InboundMessage(Principal.readOnly("u-1"), "http:pii-test", ask()));
                assertThat(out.text()).doesNotContain(PHONE);

                List<String> messages = captured.list.stream()
                        .map(ILoggingEvent::getFormattedMessage).toList();
                assertThat(messages).isNotEmpty();
                assertThat(messages).noneMatch(m -> m.contains(PHONE) || m.contains(ID_CARD));
                assertThat(messages).anyMatch(m -> m.contains(MASKED_PHONE));
                return null;
            }));
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
}
