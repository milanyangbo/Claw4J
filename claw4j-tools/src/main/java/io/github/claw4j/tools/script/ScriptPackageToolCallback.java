package io.github.claw4j.tools.script;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.util.JsonHelper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把一个脚本包适配成 {@link ToolCallback}：三层能力对 Agent 呈现为同一件事，
 * 靠的就是这一层适配（ADR-0003）。
 *
 * <p><b>名字与参数 Schema 是唯一契约</b>：模型看到的是包名加一个可选参数，
 * 看不到这是一个脚本、更看不到脚本写了什么。MCP 那一层（{@code McpToolCallback}）
 * 是同一个形状，将来换成"脚本跑在另一台容器里"，Agent 侧一个字都不用改。
 *
 * <p>出错一律包成 {@link ToolExecutionException}：工具调用管理器只处理这一种，
 * 抛别的会一路冒泡把整通对话炸掉，而"这个脚本这次没跑出来"应当变成一次失败的工具结果，
 * 让模型有机会换条路。沙箱那条边界同样如此——被拦下来的话也要让模型看见，
 * 它才知道该换个参数而不是继续重试。
 */
public final class ScriptPackageToolCallback implements ToolCallback {

    /**
     * 参数 Schema：只有一个可选的 {@code argument}。
     *
     * <p>为什么不给"任意多个参数"：参数越多，模型越容易把自由文本塞进命令行，
     * 而命令行是 ADR-0002 管得最紧的那条边界。一个参数够表达绝大多数脚本包的用法
     * （一个城市名、一个文件名），不够的那些本来就该写成 Java 工具。
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

    private final ScriptPackage pkg;

    private final ScriptPackageRunner runner;

    private final ToolDefinition definition;

    public ScriptPackageToolCallback(ScriptPackage pkg, ScriptPackageRunner runner) {
        this.pkg = Objects.requireNonNull(pkg, "pkg");
        this.runner = Objects.requireNonNull(runner, "runner");
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

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            String argument = argumentOf(toolInput);
            return runner.run(pkg.name(), argument == null ? List.of() : List.of(argument));
        }
        catch (ToolExecutionException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
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
