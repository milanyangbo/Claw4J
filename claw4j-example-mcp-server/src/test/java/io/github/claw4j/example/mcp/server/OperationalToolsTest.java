package io.github.claw4j.example.mcp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三个工具本身的判定：给出去的东西对不对、越界的入参是不是当场拒。
 *
 * <p>这里直接调方法，不起进程：跨进程与真端口由 {@link JavaMcpServerProcessTest} 验，
 * 两者管的是不同的事——那边红了说明"连不上"，这边红了说明"算错了"。
 */
class OperationalToolsTest {

    private final OperationalTools tools = new OperationalTools();

    @Test
    @DisplayName("runtimeSnapshot 报的是这个进程：pid 对得上，调用计数自己涨")
    void runtimeSnapshotDescribesThisProcess() {
        String first = tools.runtimeSnapshot();
        String second = tools.runtimeSnapshot();

        assertThat(first)
                .contains("pid=" + ProcessHandle.current().pid())
                .contains("handledCalls=1")
                .contains("timezone=Asia/Shanghai");
        assertThat(second)
                .as("计数包含本次调用：第二次是 2")
                .contains("handledCalls=2");
    }

    @Test
    @DisplayName("cronNext 解出来的第一行是表达式该有的时间，不是现在")
    void cronNextReturnsTheScheduleNotNow() {
        String answer = tools.cronNext("0 9 * * 1-5", 2);

        assertThat(answer)
                .contains("未来 2 次")
                .contains("09:00")
                .doesNotContain("08:00");
    }

    @Test
    @DisplayName("入参越界与永远不成立的表达式都当场拒，不返回空结果")
    void cronNextRejectsWhatItCannotSolve() {
        assertThatThrownBy(() -> tools.cronNext("0 9 * *", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 个字段");
        assertThatThrownBy(() -> tools.cronNext("0 9 * * 1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
        assertThatThrownBy(() -> tools.cronNext("0 0 30 2 *", 2))
                .as("2 月 30 日不存在：要报出来，而不是给一个空表让人以为表达式没问题")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("四年内没有匹配");
    }

    @Test
    @DisplayName("simulateSlowCall 真的睡，越界当场拒")
    void simulateSlowCallSleepsAndRejectsOutOfRange() {
        assertThat(tools.simulateSlowCall(30)).contains("请求睡 30 毫秒");

        assertThatThrownBy(() -> tools.simulateSlowCall(60_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..60000");
        assertThatThrownBy(() -> tools.simulateSlowCall(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("工具调用也计入 handledCalls：三个工具共用同一个计数")
    void everyToolCallCounts() {
        tools.cronNext("0 9 * * *", 1);
        tools.simulateSlowCall(0);

        assertThat(tools.runtimeSnapshot()).contains("handledCalls=3");
    }
}
