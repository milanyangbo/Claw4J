package io.github.claw4j.agent.advisor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.claw4j.agent.AgentAutoConfiguration;
import io.github.claw4j.agent.ChatClientAgentPort;
import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站脱敏的行为断言，从 {@code AgentPort} 接缝进、从 {@code AgentPort} 接缝出。
 *
 * <p>三条要分开看：回答是掩码的、流式下分块边界没把掩码切坏、日志里也是掩码的。
 * 第三条才是"脱敏发生在出站之前"的证明——只在响应里脱敏、日志里仍是明文，等于没脱。
 */
class PiiRedactionAdvisorTest {

    private static final String PHONE = "13812345678";

    private static final String MASKED_PHONE = "138****5678";

    private static final String ID_CARD = "110101199003071234";

    private static final String MASKED_ID_CARD = "110101********1234";

    /** 脚本里的原文：两个号码都在中间，流式按 8 字分块时**必然**被切在号码上。 */
    private static final String ANSWER = "联系人张三，手机 " + PHONE + "，证件 " + ID_CARD + "。";

    @Test
    void 同步路径输出里是掩码且原始值不出现() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText(ANSWER).build();

        runner(model).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

            assertThat(out.text())
                    .contains(MASKED_PHONE)
                    .contains(MASKED_ID_CARD)
                    .doesNotContain(PHONE)
                    .doesNotContain(ID_CARD);
        });
    }

    /**
     * 流式：分块边界会落在号码中间（假模型按 8 字切），逐一整块脱敏会漏。
     * 这里断言的是"拼回来之后仍然完整脱敏"，而不是"某一块里没有号码"。
     */
    @Test
    void 流式路径分块边界不会漏掉号码() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText(ANSWER).build();

        runner(model).run(context -> {
            List<OutboundMessage> chunks = new ArrayList<>();
            context.getBean(AgentPort.class).stream(ask(), chunks::add);

            String streamed = chunks.stream().map(OutboundMessage::text).reduce("", String::concat);
            assertThat(chunks.size()).isGreaterThan(1);
            assertThat(streamed)
                    .contains(MASKED_PHONE)
                    .contains(MASKED_ID_CARD)
                    .doesNotContain(PHONE)
                    .doesNotContain(ID_CARD);
        });
    }

    /** 对照：没有这个 Advisor 时原始值原样出来。没有这条，"模型本来就没吐号码"也能全绿。 */
    @Test
    void 没有脱敏Advisor时原始值原样出站() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText(ANSWER).build();
        String viaRawClient = ChatClient.builder(model).build().prompt("查一下张三").call().content();

        assertThat(viaRawClient).contains(PHONE);
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
     */
    @Test
    void 出站日志里也是掩码后的值() {
        ScriptedChatModel model = ScriptedChatModel.builder().thenText(ANSWER).build();
        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientAgentPort.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);

        try {
            runner(model).run(context -> context.getBean(AgentPort.class).reply(ask()));
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(originalLevel);
        }

        List<String> messages = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(message -> message.contains(PHONE) || message.contains(ID_CARD));
        assertThat(messages).anyMatch(message -> message.contains(MASKED_PHONE));
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

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("u-1"), "http:pii-test", "张三的手机号是多少");
    }

    private static ApplicationContextRunner runner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withBean("chatModel", ChatModel.class, () -> model);
    }
}
