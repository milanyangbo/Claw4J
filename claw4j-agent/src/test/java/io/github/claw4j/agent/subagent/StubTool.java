package io.github.claw4j.agent.subagent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子 Agent 那一侧的工具替身：一个名字、一句固定的返回、一个调用计数。
 *
 * <p>计数是这个夹具存在的全部理由："这个工具到底有没有被执行过"是好几条断言的落点——
 * 隔离那条要它被调用过（否则"什么都没发生"也能绿），
 * 鉴权那条要它**一次都没被调用**（拒绝发生在执行之前，不是执行完再过滤结果）。
 *
 * <p>它刻意不做任何业务逻辑：子 Agent 会调什么、调了得到什么，由用例自己给。
 * 替身去实现业务就等于把被测的东西复制进夹具。
 */
public final class StubTool implements ToolCallback {

    private final String name;

    private final String result;

    private final AtomicInteger calls = new AtomicInteger();

    private StubTool(String name, String result) {
        this.name = Objects.requireNonNull(name, "name");
        this.result = result;
    }

    /**
     * 每次调用都返回这一句。失败场景也用它（返回"这一步失败了：……"）：
     * 抛异常会被框架直接冒泡，测不到"一直失败时在上限处停下"——
     * 真实工具也是这么做的，见 {@code SalesTools}。
     */
    public static StubTool returning(String name, String result) {
        return new StubTool(name, result);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name + "（测试替身）").inputSchema("{}").build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        calls.incrementAndGet();
        return result;
    }

    public int calls() {
        return calls.get();
    }
}
