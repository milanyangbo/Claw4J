package io.github.claw4j.agent.subagent;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.untrusted.PromptData;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.util.JsonHelper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 委派这个动作本身，对父 Agent 呈现为一个工具：{@code Task}。
 *
 * <p>描述里只有元数据（每个 Subagent 的名字与它管什么），与 Skill 的渐进式披露同一个形状：
 * 父靠这几行决定该派谁，至于那个 Subagent 具体怎么做（它定义里的正文），只在它自己的窗口里
 * 出现一次。于是「有十个专职 Agent」的代价是十行描述，不是十份流程说明。
 *
 * <p>它不再登记权限（ADR-0030）：能不能看见这个工具由清单决定（{@code Task} 只在
 * {@code coding} 档），子 Agent 能动手到什么程度由它自己那层清单决定。原来那条"委派这个
 * 动作需要什么授权"的声明随权限登记表一起下线。
 *
 * <p>没带身份的委派一律拒绝：身份来自入站请求（{@code ChatClientAgentPort} 把它放进
 * {@link ToolContext}），工具回调拿到什么就是什么，不猜、不兜底。拿不到时返回一句明确的
 * 「没有权限」，而不是照常派出去——委派一旦脱离身份，子链上的会话、审计与审批就都说不清
 * 是谁做的。
 */
public final class SubagentDelegation implements ToolCallback {

    /**
     * 工具名定为 {@code Task}。
     *
     * <p>「把一件事交给一个专职的谁去做」在父 Agent 眼里就是一个工具，而不是每个 Subagent
     * 各占一个位置；定义文件 front matter 里的 {@code tools} 与 {@code disallowedTools}
     * 沿用同一套命名。
     */
    public static final String TOOL_NAME = "Task";

    /**
     * 没带身份时那句话的前缀。测试靠它区分「明确的拒绝」与「查出来是空的」。
     *
     * <p>它原来住在鉴权 Advisor 上：那条链上还有别的拒绝（业务表越权），
     * 两处必须用同一句话。那个 Advisor 在 ADR-0030 里整块下线之后，委派是唯一还会因为
     * 「没有身份」而拒绝的地方，于是这句话跟着它搬到这里。
     */
    public static final String DENIAL_PREFIX = "没有权限";

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "subagent": {
                  "type": "string",
                  "description": "要派哪个专职 Subagent，用下面清单里的名字。"
                },
                "task": {
                  "type": "string",
                  "description": "交给它做的事。它看不到我们的对话，要什么就在这里写清，包括口径与输出形式。"
                }
              },
              "required": ["subagent", "task"]
            }""";

    private static final String SUBAGENT = "subagent";

    private static final String TASK = "task";

    private final List<SubagentDefinition> definitions;

    private final SubagentRunner runner;

    private final ToolDefinition definition;

    public SubagentDelegation(List<SubagentDefinition> definitions, SubagentRunner runner) {
        this.definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        this.runner = Objects.requireNonNull(runner, "runner");
        this.definition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(description(definitions))
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
        Map<String, Object> arguments = argumentsOf(toolInput);
        Principal principal = principalOf(toolContext);
        if (principal == null) {
            // 拒绝而不是照常派：没有身份的委派会绕过子链上那道鉴权。
            return DENIAL_PREFIX + "：本次委派没有携带身份，一律不放行。";
        }
        String name = String.valueOf(arguments.getOrDefault(SUBAGENT, "")).trim();
        String task = String.valueOf(arguments.getOrDefault(TASK, "")).trim();
        if (name.isEmpty() || task.isEmpty()) {
            return "委派要写清 subagent 与 task 两样：派谁、让它做什么。这次拿到的是 " + toolInput;
        }
        SubagentDefinition chosen = definitions.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
            // 把可用清单列出来：模型下一个动作就是改名字再派一次，
            // 而它到底叫什么不该靠模型回忆。
            return "没有名为 " + name + " 的 Subagent。可用的是：\n"
                    + String.join("\n", registrations());
        }
        try {
            String report = runner.delegate(chosen, task, principal);
            // 子 Agent 的结论是"一份报告，供你综合"，不是"新的任务"。包成数据块，label 带上是谁报的：
            // 它是这一段上下文里最像结论的东西，也正因如此最需要标出来源。
            String block = PromptData.wrapDataBlock("Subagent result: " + chosen.name(), report, 0);
            return block.isEmpty() ? "Subagent 没有返回内容。" : block;
        }
        catch (RuntimeException failure) {
            throw new ToolExecutionException(definition, failure);
        }
    }

    /** 给模型看的清单：每行一个名字加一句它管什么，不含任何一份正文。 */
    public List<String> registrations() {
        return definitions.stream().map(SubagentDefinition::registration).toList();
    }

    private static String description(List<SubagentDefinition> definitions) {
        String registry = definitions.stream()
                .map(SubagentDefinition::registration)
                .reduce((one, other) -> one + "\n" + other)
                .orElse("（当前没有注册任何 Subagent）");
        return """
                把一个需要好几步的任务交给专职 Subagent 去做。
                它在独立上下文里自己跑，只把结论回传给你——中间调了什么工具、拿到什么，你看不到。
                需要"自己查、自己核对、再给结论"这类事情时派它，别自己一步步做。

                可用的 Subagent：
                %s

                参数：subagent 用上面清单里的名字；task 把要它做的事写完整，
                包括口径与"最后给我什么形式"，因为它看不到我们的对话。
                """.formatted(registry);
    }

    private static Map<String, Object> argumentsOf(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            throw new IllegalArgumentException(
                    "委派需要 {\"subagent\": \"...\", \"task\": \"...\"} 形状的参数，这次什么都没给。");
        }
        try {
            return new JsonHelper().fromJsonToMap(toolInput);
        }
        catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "委派参数需要是 {\"subagent\": \"...\", \"task\": \"...\"} 形状，拿到的是 " + toolInput, failure);
        }
    }

    /** 身份只从工具上下文里取；取不到就是取不到，不 fallback 到匿名放行。 */
    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }
}
