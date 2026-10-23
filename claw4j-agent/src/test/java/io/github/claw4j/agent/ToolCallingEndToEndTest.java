package io.github.claw4j.agent;

import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.tools.ToolsAutoConfiguration;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
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
 * <p><b>现在是真模型自己去调工具。</b>改真模型之前（ADR-0011）这三件事靠脚本喂进去：
 * 脚本先吐一个 tool call、再吐一句固定回答，于是"工具被调用了""结果回到了模型"
 * 都是脚本保证的，模型侧唯一没被验的就是"它会不会自己去调"。
 * 现在唯一的前提只留下"这个问题该调工具"，其余全交给模型。
 *
 * <p>证明的仍然是三件事：工具**真的被调用了**（替身执行器计数）、
 * 工具的返回值**回到了模型**（那个数出现在最终回答里）、循环**走完了第二轮**。
 * 数据源接缝仍是替身 —— 这里测的是循环，不是 SQL。
 */
class ToolCallingEndToEndTest {

    private static final String SQL_RESULT = "128400.00";

    /** 替身执行器返回的那个数，去掉小数好按各种写法去匹配。 */
    private static final String SQL_RESULT_DIGITS = "128400";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class, ToolsAutoConfiguration.class))
            .withBean("chatModel", ChatModel.class, RealModelSupport::chatModel);

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:t1", "上周华东区成交额多少");
    }

    @Test
    void 同步路径_工具被调用_结果回到模型() {
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", SQL_RESULT);

        runner(executor).run(context -> RealModelSupport.eventually(2, "同步走完工具循环", () -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

            // 工具真的被执行了：替身计数从 0 变成非 0，这是"调用发生过"的外部证据
            assertThat(executor.calls()).isGreaterThanOrEqualTo(1);
            // 工具的返回值回到了模型：那个数出现在它给客户端的回答里。
            // 这是比"读第二轮提示词"更强的判据——提示词里有不等于模型真的用了它。
            RealModelSupport.assertContainsAmount(out.text(), SQL_RESULT_DIGITS);
            return out.text();
        }));
    }

    @Test
    void 流式路径同样走完工具循环() {
        FakeSqlExecutor executor = FakeSqlExecutor.returning("amount", SQL_RESULT);

        runner(executor).run(context -> RealModelSupport.eventually(2, "流式走完工具循环", () -> {
            List<OutboundMessage> chunks = new ArrayList<>();
            context.getBean(AgentPort.class).stream(ask(), chunks::add);

            assertThat(executor.calls()).isGreaterThanOrEqualTo(1);
            // 拼接所有块再断言：分块边界会落在词中间，逐块查会误判
            String streamed = chunks.stream().map(OutboundMessage::text).reduce("", String::concat);
            RealModelSupport.assertContainsAmount(streamed, SQL_RESULT_DIGITS);
            // 结尾块是唯一的正常结束信号
            assertThat(chunks.getLast().finalChunk()).isTrue();
            return streamed;
        }));
    }

    private ApplicationContextRunner runner(ReadOnlySqlExecutor executor) {
        return contextRunner.withBean("sqlExecutor", ReadOnlySqlExecutor.class, () -> executor);
    }
}
