package io.github.claw4j.agent;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Text-to-SQL 的自纠错链路与重试上限（ADR-0008 列为必测项）。
 *
 * <p><b>这一批是不经过模型的组件级测试（ADR-0011）。</b>自纠错这条链路靠的是
 * "模型第一次写错 SQL、拿到数据库报错后再写一版"——真模型不会配合先写错一次，
 * 所以这三条里"模型这一轮写了什么 SQL"由测试直接构造。**执行 SQL 的是真的
 * {@code ToolCallingManager}、真的预算、真的替身执行器**，
 * 丢掉的只是"这一版 SQL 是谁写的"。
 *
 * <p>因此这里的判据收缩为两件仍然确定的事：
 * ① 数据库报错的**原话**会被当成工具结果交回去（没有它，模型只能瞎猜改哪儿）；
 * ② 重试有上限，一直写错时第三次之后不再执行。
 * "模型真的会据此改对"那一层交给端到端（{@code SalesQueryEndToEndTest} 里真模型自己写 SQL）。
 *
 * <p>数据源用替身，测的是循环而不是 SQL：SQL 对不对是 tools 模块的事。
 */
class TextToSqlLoopTest {

    /** 模型第一次写错的 SQL：把 amount 拼成了 amout。这是真实模型最常见的错法。 */
    private static final String BAD_SQL = "select sum(amout) from business.order_item";

    private static final String GOOD_SQL = "select sum(amount) from business.order_item";

    private static final String DB_ERROR = "column \"amout\" does not exist";

    private static final String SQL_RESULT = "128400.00";

    @Test
    void 写错的SQL拿到的是数据库的原话() {
        SqlExecutor executor = new SqlExecutor();
        ToolCallingManager manager = manager();

        String handedBack = executeAndCollect(manager, executor, BAD_SQL);

        // 工具被执行了，且执行的是那一版错的 SQL
        assertThat(executor.executed()).containsExactly(BAD_SQL);
        // 错误原因回到了"下一轮能看到的地方"：没有它，模型只能瞎猜改哪儿
        assertThat(handedBack).contains("amout").contains("does not exist");
    }

    @Test
    void 改好之后能拿到数() {
        SqlExecutor executor = new SqlExecutor();
        ToolCallingManager manager = manager();

        // 第一轮：错的；第二轮：改好的（真实链路里这两版都由模型写）
        executeAndCollect(manager, executor, BAD_SQL);
        String second = executeAndCollect(manager, executor, GOOD_SQL);

        assertThat(executor.executed()).containsExactly(BAD_SQL, GOOD_SQL);
        assertThat(second).contains(SQL_RESULT);
    }

    /**
     * 重试上限：一直写错时循环必须停下来。
     *
     * <p>断言"第 4 次调用<b>根本没发生</b>"，而不是"发生了但结果被丢掉"——
     * 后者照样烧掉一次数据库往返。
     */
    @Test
    void 一直写错时在上限处截断() {
        SqlExecutor executor = new SqlExecutor();
        ToolCallingManager manager = manager();

        assertThatThrownBy(() -> manager.executeToolCalls(promptWith(executor),
                modelAskedFor(BAD_SQL, BAD_SQL, BAD_SQL, BAD_SQL)))
                .hasMessageContaining("Tool call limit")
                .hasMessageContaining(String.valueOf(ToolCallBudget.MAX_CALLS_PER_TOOL));

        assertThat(executor.calls()).isEqualTo(ToolCallBudget.MAX_CALLS_PER_TOOL);
    }

    private static ToolCallingManager manager() {
        return ToolCallBudget.toolCallingManager(ObservationRegistry.NOOP);
    }

    private static String executeAndCollect(ToolCallingManager manager, SqlExecutor executor, String... sql) {
        var result = manager.executeToolCalls(promptWith(executor), modelAskedFor(sql));
        return result.conversationHistory().stream()
                .map(Object::toString)
                .reduce("", String::concat);
    }

    private static Prompt promptWith(SqlExecutor executor) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(executor).build()
                .getToolCallbacks();
        return new Prompt(List.of(new UserMessage("上周华东区成交额多少")),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
    }

    private static ChatResponse modelAskedFor(String... sql) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < sql.length; i++) {
            calls.add(new AssistantMessage.ToolCall("call-" + i, "function", "querySales",
                    "{\"sql\":\"" + sql[i] + "\"}"));
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())))
                .build();
    }

    /** 数据源替身：写了 amout 就报数据库的原话，写对了就返回那个数。 */
    static final class SqlExecutor {

        private final List<String> executed = new ArrayList<>();

        @Tool(description = "按给定的 SQL 查询业务库（只读）")
        public String querySales(String sql) {
            executed.add(sql);
            if (sql.contains("amout")) {
                return "ERROR: " + DB_ERROR;
            }
            return SQL_RESULT;
        }

        List<String> executed() {
            return List.copyOf(executed);
        }

        int calls() {
            return executed.size();
        }
    }
}
