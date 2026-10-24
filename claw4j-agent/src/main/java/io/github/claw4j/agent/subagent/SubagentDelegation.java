package io.github.claw4j.agent.subagent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
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
 * <p><b>描述里只有元数据</b>（每个 Subagent 的名字与它管什么），与 Skill 的渐进式披露
 * 同一个形状：父靠这几行决定"该派谁"，至于那个 Subagent 具体怎么做（它定义里的正文），
 * 只在它自己的窗口里出现一次。于是"有十个专职 Agent"的代价是十行描述，
 * 不是十份流程说明——这条由 {@code SubagentDelegationTest} 里"描述不含正文"盯着。
 *
 * <p><b>它要过鉴权，而且不授予任何业务表</b>：能不能委派由 Principal 决定
 * （ADR-0007），登记的动作是 {@link #ACTION}，业务表一张都没有。
 * 表清单为空不是"没有约束"，而是明确声明"委派这个动作本身不碰库"——
 * 真正碰库的是子 Agent 要调的那个工具，那一步在子链上照样过鉴权、照样看表。
 *
 * <p><b>没带身份的委派一律拒绝</b>：身份来自入站请求（{@code ChatClientAgentPort}
 * 把它放进 {@link ToolContext}），工具回调拿到什么就是什么，不猜、不兜底。
 * 拿不到时返回的是一句明确的"没有权限"，而不是照常派出去——
 * 委派一旦脱离身份，子链上的鉴权就成了摆设。
 */
public final class SubagentDelegation implements ToolCallback {

    /**
     * 工具名与 Claude Code 的那个保持一致：{@code Task}。
     * "把一件事交给一个专职的谁去做"在两边的形状是一样的，
     * 于是 Subagent 定义文件的格式（front matter 里的 tools / disallowedTools）也能共用。
     */
    public static final String TOOL_NAME = "Task";

    /**
     * 委派所需的动作名，与 {@code read} 是两个口径：派一个 Agent 出去跑一堆步骤，
     * 与读一张表不是同一件事。分开之后"谁能委派"是一行配置能回答的问题。
     */
    public static final String ACTION = "delegate_task";

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

    /** 委派所需的权限：{@link #ACTION} 动作，零张业务表。 */
    public static ToolPermission permission() {
        return ToolPermission.of(ACTION);
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
            return AuthorizationAdvisor.DENIAL_PREFIX + "：本次委派没有携带身份，一律不放行。";
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
            // 而"它到底叫 what"这种事不该靠模型回忆。
            return "没有名为 " + name + " 的 Subagent。可用的是：\n"
                    + String.join("\n", registrations());
        }
        try {
            return runner.delegate(chosen, task, principal);
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

    /** 身份只从工具上下文里取；没有就是没有，不 fallback 到"匿名放行"。 */
    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY);
        return value instanceof Principal principal ? principal : null;
    }
}
