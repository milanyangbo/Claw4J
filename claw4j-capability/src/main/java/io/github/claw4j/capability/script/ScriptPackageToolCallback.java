package io.github.claw4j.capability.script;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.util.JsonHelper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把一个脚本包适配成 {@link ToolCallback}，三层能力对 Agent 呈现为同一件事（ADR-0003）。
 *
 * <p>名字与参数 Schema 是唯一契约：模型看到的是包名加一个可选参数，看不到这是一个脚本，
 * 也看不到脚本写了什么。MCP 那一层（{@code McpToolCallback}）是同一个形状，换成"脚本跑在
 * 另一台容器里"时，Agent 侧不用改。
 *
 * <p>出错一律包成 {@link ToolExecutionException}：工具调用管理器只处理这一种，抛别的会一路冒泡
 * 把整通对话炸掉，而"这次没跑出来"应当变成一次失败的工具结果。沙箱拒绝同样如此——
 * 模型看见那句拒绝，才知道该换参数而不是继续重试。
 */
public final class ScriptPackageToolCallback implements ToolCallback {

    /**
     * 参数 Schema：只有一个可选的 {@code argument}。
     *
     * <p>不给"任意多个参数"：参数越多，模型越容易把自由文本塞进命令行，而命令行是 ADR-0002
     * 管得最紧的那条边界。一个参数够表达绝大多数脚本包的用法（一个城市名、一个文件名），
     * 不够的那些本来就该写成 Java 工具。
     */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "argument": {
                  "type": "string",
                  "description": "传给脚本的单个参数。看起来是路径的参数必须落在脚本包自己的目录内。"
                }
              }
            }""";

    private static final String ARGUMENT = "argument";

    /** 判定结果的机器可读取值，审计回放时用。 */
    private static final String OUTCOME_OK = "ok";

    private static final String OUTCOME_NON_ZERO = "non-zero";

    private static final String OUTCOME_REJECTED = "rejected";

    private static final String OUTCOME_FAILED = "failed";

    private final ScriptPackage pkg;

    private final ScriptPackageRunner runner;

    private final ScriptExecutionListener listener;

    private final ToolDefinition definition;

    /**
     * 建一个工具回调，执行留痕不接。
     *
     * @param pkg 这个回调代表的包
     * @param runner 执行这个包的入口
     */
    public ScriptPackageToolCallback(ScriptPackage pkg, ScriptPackageRunner runner) {
        this(pkg, runner, ScriptExecutionListener.NO_OP);
    }

    /**
     * 建一个工具回调，执行结果交给给定的观察者。
     *
     * @param pkg 这个回调代表的包
     * @param runner 执行这个包的入口
     * @param listener 执行结果的观察者
     */
    public ScriptPackageToolCallback(ScriptPackage pkg, ScriptPackageRunner runner,
                                     ScriptExecutionListener listener) {
        this.pkg = Objects.requireNonNull(pkg, "pkg");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.definition = ToolDefinition.builder()
                .name(pkg.name())
                .description(pkg.description())
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * 三种结局各留一条痕：成功、被沙箱拦下、跑了但没跑出结果。
     *
     * <p>被拦下也要记，而且要在抛出之前记：留痕的意义是回放时看得到"有人试过"，
     * 只记成功的那种审计回答不了"谁在用它捅边界"，而那正是这类记录会被翻出来的场景。
     *
     * @param toolInput 模型给的 JSON 输入
     * @param toolContext 工具上下文，身份从这里取
     * @return 脚本写到标准输出上的内容
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            String argument = argumentOf(toolInput);
            String output = runner.run(pkg.name(), argument == null ? List.of() : List.of(argument));
            listener.executed(pkg.name(), toolContext, OUTCOME_OK, output);
            return output;
        }
        catch (SandboxViolationException rejected) {
            listener.executed(pkg.name(), toolContext, OUTCOME_REJECTED, rejected.getMessage());
            throw new ToolExecutionException(definition, rejected);
        }
        catch (ScriptExecutionException nonZero) {
            listener.executed(pkg.name(), toolContext, OUTCOME_NON_ZERO, nonZero.getMessage());
            throw new ToolExecutionException(definition, nonZero);
        }
        catch (ToolExecutionException failure) {
            listener.executed(pkg.name(), toolContext, OUTCOME_FAILED, failure.getMessage());
            throw failure;
        }
        catch (RuntimeException failure) {
            listener.executed(pkg.name(), toolContext, OUTCOME_FAILED, failure.getMessage());
            throw new ToolExecutionException(definition, failure);
        }
    }

    /** 模型给的参数可能是空的，也可能是一段没按 Schema 写的文本；两种都当成"没给参数"。 */
    private static String argumentOf(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = new JsonHelper().fromJsonToMap(toolInput);
            Object argument = parsed.get(ARGUMENT);
            return argument == null ? null : argument.toString();
        }
        catch (RuntimeException failure) {
            throw new IllegalArgumentException("脚本包参数需要是 {\"argument\": \"...\"} 形状，拿到的是 " + toolInput,
                    failure);
        }
    }
}
