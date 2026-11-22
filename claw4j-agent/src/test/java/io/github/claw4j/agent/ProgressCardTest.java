package io.github.claw4j.agent;

import io.github.claw4j.agent.progress.ProgressCardCallback;
import io.github.claw4j.agent.progress.ProgressCards;
import io.github.claw4j.agent.toolaccess.ToolPolicyAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进度卡（票 01）：限额、字段映射、会话归属、子代理禁用。
 *
 * <p>不连库：这一层要钉的是"模型交什么 → 存什么"和"没有会话就不记"，而不是 SQL 本身。
 * 三态（Saved / Cleared / Stale）里只有 Saved 会走到这个包装类，另外两态在 JDBC 那一侧验证。
 */
class ProgressCardTest {

    @Test
    @DisplayName("五十一步被拒，错误文本说得出上限")
    void tooManyStepsRejected() {
        List<ProgressCards.Step> steps = new ArrayList<>();
        for (int i = 0; i < ProgressCards.MAX_STEPS + 1; i++) {
            steps.add(new ProgressCards.Step("第 " + i + " 步", ProgressCards.Status.PENDING));
        }
        assertThatThrownBy(() -> ProgressCards.checked(steps))
                .isInstanceOf(ProgressCards.Rejected.class)
                .hasMessageContaining(String.valueOf(ProgressCards.MAX_STEPS));
    }

    @Test
    @DisplayName("单步五百一十三个字节被拒：按 UTF-8 字节算，不按字符")
    void tooLongStepRejectedByBytes() {
        // 一个汉字三个字节，170 个刚好 510，171 个是 513。
        String tooLong = "测".repeat(ProgressCards.MAX_STEP_BYTES / 3 + 1);
        assertThat(tooLong.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(ProgressCards.MAX_STEP_BYTES);
        assertThatThrownBy(() -> ProgressCards.checked(
                List.of(new ProgressCards.Step(tooLong, ProgressCards.Status.PENDING))))
                .isInstanceOf(ProgressCards.Rejected.class);
    }

    @Test
    @DisplayName("两个「在做」被拒")
    void twoInProgressRejected() {
        assertThatThrownBy(() -> ProgressCards.checked(List.of(
                new ProgressCards.Step("甲", ProgressCards.Status.IN_PROGRESS),
                new ProgressCards.Step("乙", ProgressCards.Status.IN_PROGRESS))))
                .isInstanceOf(ProgressCards.Rejected.class)
                .hasMessageContaining("在做");
    }

    @Test
    @DisplayName("不可见字符与双向控制字符会被清掉：顺序是投递面渲染的依据")
    void controlCharactersStripped() {
        List<ProgressCards.Step> checked = ProgressCards.checked(List.of(
                new ProgressCards.Step("核\u202E对\u200B表结构\n然后\u0007交差",
                        ProgressCards.Status.PENDING)));
        assertThat(checked.getFirst().step()).isEqualTo("核对表结构 然后交差");
    }

    @Test
    @DisplayName("工具参数里的 content 存成 step，activeForm 不进卡")
    void contentBecomesStepAndActiveFormDropped() {
        Recording store = new Recording();
        ToolCallback todo = new ProgressCardCallbackForTest(store,
                tool("TodoWrite", "{\"todos\":[{\"content\":\"核对表结构\","
                        + "\"status\":\"in_progress\",\"activeForm\":\"正在核对\"}]}"));

        todo.call("", context("s-1"));

        assertThat(store.saved).hasSize(1);
        assertThat(store.saved.getFirst().steps())
                .containsExactly(new ProgressCards.Step("核对表结构", ProgressCards.Status.IN_PROGRESS));
    }

    @Test
    @DisplayName("没有会话 id 就不记：不造一个假键，否则不同人的计划会混进同一张卡")
    void withoutSessionNothingIsSaved() {
        Recording store = new Recording();
        ToolCallback todo = new ProgressCardCallbackForTest(store,
                tool("TodoWrite", "{\"todos\":[{\"content\":\"甲\",\"status\":\"pending\"}]}"));

        todo.call("", new ToolContext(Map.of()));

        assertThat(store.saved).isEmpty();
    }

    @Test
    @DisplayName("拒收时把原因回给模型，且不交给工具：它还没被记下")
    void rejectedIsReturnedWithoutRunningTool() {
        Recording store = new Recording();
        store.reject = "单步太长";
        ToolCallback delegate = tool("TodoWrite", "{\"todos\":[{\"content\":\"甲\"}]}");
        ToolCallback todo = new ProgressCardCallbackForTest(store, delegate);

        String result = todo.call("", context("s-1"));

        assertThat(result).contains("计划没有被记录").contains("单步太长");
        assertThat(((FakeTool) delegate).calls).isZero();
    }

    @Test
    @DisplayName("非 TodoWrite 原样穿过，不碰存储")
    void otherToolsPassThrough() {
        Recording store = new Recording();
        ToolCallback read = tool("Read", "{}");
        ToolCallback wrapped = ProgressCardCallback.wrap(List.of(read), store).getFirst();

        wrapped.call("", context("s-1"));

        assertThat(store.saved).isEmpty();
        assertThat(((FakeTool) read).calls).isEqualTo(1);
    }

    @Test
    @DisplayName("子代理拿不到 TodoWrite：卡属于用户对话所在的那一段")
    void subagentsNeverGetProgressCard() {
        assertThat(ToolPolicyAssembler.subagentAlwaysDenied()).contains("TodoWrite");
    }

    private static ToolContext context(String sessionId) {
        return new ToolContext(Map.of(ChatMemory.CONVERSATION_ID, sessionId));
    }

    private static ToolCallback tool(String name, String cannedOutput) {
        return new FakeTool(name, cannedOutput);
    }

    /**
     * 一个只记录结果的存储。
     *
     * <p>用它而不是 JDBC 实现：这一层要钉的是"交给存储的那份是什么"，SQL 由 JDBC 那一侧自己的用例管。
     */
    private static final class Recording implements ProgressCards {

        private final List<Write> saved = new ArrayList<>();

        private String reject;

        @Override
        public Outcome save(String sessionKey, Write write) {
            if (reject != null) {
                throw new Rejected(reject);
            }
            saved.add(write);
            return new Outcome.Cleared();
        }

        @Override
        public Optional<Card> of(String sessionKey) {
            return Optional.empty();
        }
    }

    /** 被包装的工具：记录被调了几次。 */
    private static final class FakeTool implements ToolCallback {

        private final ToolDefinition definition;

        private final String output;

        private int calls;

        private FakeTool(String name, String output) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
            this.output = output;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return output;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            calls++;
            return output;
        }
    }

    /**
     * 让测试能直接构造包装类：它的构造器是私有的，走一次 {@code wrap} 也一样，
     * 但那需要清单里恰好有一个同名工具，绕。
     */
    private static final class ProgressCardCallbackForTest {

        private final ToolCallback callback;

        private ProgressCardCallbackForTest(ProgressCards store, ToolCallback delegate) {
            this.callback = ProgressCardCallback.wrap(List.of(delegate), store).getFirst();
        }

        private String call(String input, ToolContext context) {
            return callback.call(input, context);
        }
    }
}
