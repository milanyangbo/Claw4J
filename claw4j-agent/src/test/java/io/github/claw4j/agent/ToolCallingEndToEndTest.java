package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ScriptedChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.tools.SalesTools;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用闭环，从 {@code AgentPort} 接缝进、从 {@code AgentPort} 接缝出。
 *
 * <p>证明的不是"模型说了什么"，而是三件事：工具**真的被调用了**、
 * 工具的返回值**回到了模型**（进了第二轮的提示词）、循环**走完了第二轮**。
 * 断言钉在这三点上，而不是钉在桩工具返回的具体内容上——05 号票把桩换成真实
 * Text-to-SQL 时，这三条都不需要改。
 */
class ToolCallingEndToEndTest {

    private static final String FINAL_ANSWER = "上周华东区成交额是 128,400 元。";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class, ToolsAutoConfiguration.class));

    /** 工具调用那一轮：正文为空 + 一次 querySales 调用请求；第二轮才是最终回答。 */
    private static ScriptedChatModel scriptedModel() {
        return ScriptedChatModel.builder()
                .thenToolCall("querySales", "{\"region\":\"华东\",\"period\":\"上周\"}")
                .thenText(FINAL_ANSWER)
                .build();
    }

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:t1", "上周华东区成交额多少");
    }

    @Test
    void 同步路径_工具被调用_结果回到模型_最终回答是第二轮那句() {
        ScriptedChatModel model = scriptedModel();

        contextRunner.withBean("chatModel", ChatModel.class, () -> model).run(context -> {
            AgentPort port = context.getBean(AgentPort.class);

            OutboundMessage out = port.reply(ask());

            // 循环走了两轮：第一轮决定调工具，第二轮才作答
            assertThat(model.taken()).isEqualTo(2);
            // 工具的返回值进了第二轮的提示词 —— 这是"工具真的执行过"的外部证据
            assertThat(model.promptTexts().get(1)).contains(SalesTools.STUB_RESULT);
            // 最终回答是第二轮那句，不是工具调用那轮的空正文
            assertThat(out.text()).isEqualTo(FINAL_ANSWER);
        });
    }

    @Test
    void 流式路径同样走完工具循环() {
        ScriptedChatModel model = scriptedModel();

        contextRunner.withBean("chatModel", ChatModel.class, () -> model).run(context -> {
            AgentPort port = context.getBean(AgentPort.class);
            List<OutboundMessage> chunks = new ArrayList<>();

            port.stream(ask(), chunks::add);

            assertThat(model.taken()).isEqualTo(2);
            assertThat(model.promptTexts().get(1)).contains(SalesTools.STUB_RESULT);
            // 拼接所有块再断言：分块边界会落在词中间，逐块查会误判
            assertThat(chunks.stream().map(OutboundMessage::text).reduce("", String::concat))
                    .isEqualTo(FINAL_ANSWER);
            // 结尾块是唯一的正常结束信号
            assertThat(chunks.getLast().finalChunk()).isTrue();
        });
    }
}
