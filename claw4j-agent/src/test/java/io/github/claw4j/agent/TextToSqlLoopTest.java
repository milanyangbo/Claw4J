package io.github.claw4j.agent;

import io.github.claw4j.agent.model.FakeSqlExecutor;
import io.github.claw4j.agent.model.ScriptedChatModel;
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
 * Text-to-SQL 的自纠错链路与重试上限（ADR-0008 列为必测项）。
 *
 * <p>数据源用替身，所以这里测的是<b>循环的行为</b>：模型拿到错误之后会不会再试一次、
 * 试几次会被截断。SQL 对不对是 tools 模块的事，端到端的那个数字是 app 模块的事。
 *
 * <p>判据必须能证伪，所以每条断言都钉在外部可观测的事实上：
 * 工具被送进执行器的次数、模型被调了几轮、客户端最后拿到什么。
 * 不去断言"循环内部做了什么"——那等于把实现复述一遍。
 */
class TextToSqlLoopTest {

    /** 模型第一次写错的 SQL：把 amount 拼成了 amout。这是真实模型最常见的错法。 */
    private static final String BAD_SQL = "select sum(amout) from business.order_item";

    private static final String GOOD_SQL = "select sum(amount) from business.order_item";

    private static final String DB_ERROR = "column \"amout\" does not exist";

    private static final String SQL_RESULT = "128400.00";

    private static final String FINAL_ANSWER = "上周华东区成交额是 128400.00 元。";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class, ToolsAutoConfiguration.class));

    private static String toolCallArgs(String sql) {
        return "{\"sql\":\"" + sql + "\"}";
    }

    private static InboundMessage ask() {
        return new InboundMessage(Principal.readOnly("ethan"), "http:t1", "上周华东区成交额多少");
    }

    @Test
    void 第一次写错时模型拿到错误原因并重写一版() {
        FakeSqlExecutor executor = fakeThatFailsOn("amout");
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("querySales", toolCallArgs(BAD_SQL))
                .thenToolCall("querySales", toolCallArgs(GOOD_SQL))
                .thenText(FINAL_ANSWER)
                .build();

        runner(executor, model).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

            // 工具被真的调了两次：一次错的、一次改好的
            assertThat(executor.calls()).isEqualTo(2);
            assertThat(executor.executed()).containsExactly(BAD_SQL, GOOD_SQL);
            // 第二轮的提示词里带着数据库的原话——没有它，模型只能瞎猜改哪儿。
            // 分开断言两个片段：工具结果在提示词里是 JSON 编码的，引号会被转义，
            // 整句比对只会测到转义规则而不是"有没有把原因传回去"。
            assertThat(model.promptTexts().get(1)).contains("amout").contains("does not exist");
            // 第三轮的提示词里带着查到的数
            assertThat(model.promptTexts().get(2)).contains(SQL_RESULT);
            assertThat(model.taken()).isEqualTo(3);
            assertThat(out.text()).isEqualTo(FINAL_ANSWER);
        });
    }

    @Test
    void 流式路径同样能自纠错() {
        FakeSqlExecutor executor = fakeThatFailsOn("amout");
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("querySales", toolCallArgs(BAD_SQL))
                .thenToolCall("querySales", toolCallArgs(GOOD_SQL))
                .thenText(FINAL_ANSWER)
                .build();

        runner(executor, model).run(context -> {
            List<OutboundMessage> chunks = new ArrayList<>();
            context.getBean(AgentPort.class).stream(ask(), chunks::add);

            assertThat(executor.calls()).isEqualTo(2);
            assertThat(chunks.stream().map(OutboundMessage::text).reduce("", String::concat))
                    .isEqualTo(FINAL_ANSWER);
            assertThat(chunks.getLast().finalChunk()).isTrue();
        });
    }

    /**
     * 重试上限：模型一直写错时，循环必须停下来，而不是陪它耗到脚本耗尽。
     *
     * <p>断言的是"执行器只被调了 {@code MAX_CALLS_PER_TOOL} 次"——
     * 超限之后那一次调用<b>根本没发生</b>，而不是"发生了但结果被丢掉"。
     * 后者照样烧掉了一次数据库往返。
     */
    @Test
    void 一直写错时在上限处截断() {
        FakeSqlExecutor executor = fakeThatFailsOn("amout");
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thenToolCall("querySales", toolCallArgs(BAD_SQL))
                .thenToolCall("querySales", toolCallArgs(BAD_SQL))
                .thenToolCall("querySales", toolCallArgs(BAD_SQL))
                // 第四次：脚本里给足，好让"没有第四次"这件事是真的没有，
                // 而不是脚本不够长
                .thenToolCall("querySales", toolCallArgs(BAD_SQL))
                .thenText("这句话不该被用到")
                .build();

        runner(executor, model).run(context -> {
            OutboundMessage out = context.getBean(AgentPort.class).reply(ask());

            assertThat(executor.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
            // 上限被撞上之后不再回头问模型：最后一句话是框架给的失败原因，不是模型的回答
            assertThat(out.text())
                    .contains("Tool call limit")
                    .contains(String.valueOf(ToolCallBudget.MAX_CALLS_PER_TOOL))
                    .doesNotContain("这句话不该被用到");
        });
    }

    private static FakeSqlExecutor fakeThatFailsOn(String fragment) {
        return FakeSqlExecutor.returning("amount", SQL_RESULT).failingWhen(fragment, DB_ERROR);
    }

    private ApplicationContextRunner runner(ReadOnlySqlExecutor executor, ScriptedChatModel model) {
        return contextRunner
                .withBean("sqlExecutor", ReadOnlySqlExecutor.class, () -> executor)
                .withBean("chatModel", ChatModel.class, () -> model);
    }
}
