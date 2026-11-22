package io.github.claw4j.agent.progress;

import io.github.claw4j.capability.builtin.agenttools.AgentToolsAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把 {@code TodoWrite} 每一次调用落成一段会话的进度卡。
 *
 * <p>为什么包在 {@link ToolCallback} 上、而不用库自己的 {@code TodoEventHandler}：那个钩子的签名是
 * {@code handle(Todos)}，<b>不带任何上下文</b>，而"这条计划属于哪一段会话"只有
 * {@link ToolContext} 里有。用它写库就得靠 ThreadLocal 传会话 id，而流式路径上工具不一定跑在
 * 发起那一轮的线程上——那种错会表现为"计划跑到了别人的卡里"，比没有卡更糟。
 *
 * <p>会话 id 取 {@link ChatMemory#CONVERSATION_ID}（含段）。它在 {@code PromptComposer} 里每轮都会
 * 放进去，所以这里不再接受一个构造期捕获的 id：捕获的那个在换段之后就是旧值。
 *
 * <p>顺序是「先校验、再落库、最后交给工具」：{@code TodoWrite} 没有外部副作用，先拒收可以让模型
 * 拿到原因并重交；反过来先跑工具再落库，模型已经"以为自己记下了"。
 */
public final class ProgressCardCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ProgressCardCallback.class);

    private final ToolCallback delegate;

    private final ProgressCards cards;

    private final ObjectMapper json = JsonMapper.builder().build();

    private ProgressCardCallback(ToolCallback delegate, ProgressCards cards) {
        this.delegate = delegate;
        this.cards = cards;
    }

    /**
     * 给这一轮的工具清单包上进度卡。
     *
     * @param tools 这一轮要交给模型的清单
     * @param cards 进度卡存储；为 null 表示这段部署没有会话存储，原样返回
     * @return 包过的清单，只动 {@code TodoWrite}
     */
    public static List<ToolCallback> wrap(List<ToolCallback> tools, ProgressCards cards) {
        if (cards == null || tools == null || tools.isEmpty()) {
            return tools;
        }
        List<ToolCallback> wrapped = new ArrayList<>(tools.size());
        for (ToolCallback tool : tools) {
            boolean todo = tool != null && tool.getToolDefinition() != null
                    && AgentToolsAutoConfiguration.TODO_TOOL_NAME
                    .equals(tool.getToolDefinition().name());
            wrapped.add(todo ? new ProgressCardCallback(tool, cards) : tool);
        }
        return List.copyOf(wrapped);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 单参重载：拿不到会话，于是记不了卡，原样转给被包装的工具。
     *
     * @param toolInput 模型给出的参数
     * @return 工具结果
     */
    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String sessionId = sessionIdOf(toolContext);
        if (sessionId == null) {
            // 没有会话就记不了。不造一个假键（那会把不同人的计划混进同一张卡）。
            log.debug("进度卡跳过：这一轮没有会话 id");
            return delegate.call(toolInput, toolContext);
        }
        try {
            cards.save(sessionId, new ProgressCards.Write(null, parse(toolInput), null));
        } catch (ProgressCards.Rejected rejected) {
            // 拒收要回给模型看：它会据此改写再交一次，而不是以为已经记下了。
            return "计划没有被记录：" + rejected.getMessage();
        }
        return delegate.call(toolInput, toolContext);
    }

    /**
     * 从工具上下文里取这一段会话。
     *
     * @param toolContext 工具上下文，可为 null
     * @return 会话 id；没有时返回 null
     */
    private static String sessionIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Map<String, Object> context = toolContext.getContext();
        if (context == null) {
            return null;
        }
        Object value = context.get(ChatMemory.CONVERSATION_ID);
        if (value == null) {
            value = context.get("conversationId");
        }
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /**
     * 把工具参数解析成计划。
     *
     * <p>解析失败也按"拒收"处理：宁可让模型重交一次，也不要静默记下一份残缺的计划。
     *
     * @param toolInput 工具参数的 JSON
     * @return 计划
     */
    private List<ProgressCards.Step> parse(String toolInput) {
        JsonNode root;
        try {
            root = json.readTree(toolInput == null ? "{}" : toolInput);
        } catch (JacksonException e) {
            throw new ProgressCards.Rejected("参数不是合法 JSON。");
        }
        JsonNode todos = root.path("todos");
        if (!todos.isArray()) {
            throw new ProgressCards.Rejected("缺少 todos 数组。");
        }
        List<ProgressCards.Step> steps = new ArrayList<>(todos.size());
        for (JsonNode node : todos) {
            // 卡的步骤只有两个字段：内容的 content 映射成 step，activeForm 不进卡
            // （那是给模型看的措辞，不是进度）。
            steps.add(new ProgressCards.Step(
                    node.path("content").asString(""),
                    statusOf(node.path("status").asString(""))));
        }
        return steps;
    }

    /**
     * 工具的状态词是 {@code pending} / {@code in_progress} / {@code completed}（小写）。
     *
     * @param raw 原始值
     * @return 对应状态；不认识的值当待办，不因此整份拒收
     */
    private static ProgressCards.Status statusOf(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "in_progress", "in-progress", "inprogress" -> ProgressCards.Status.IN_PROGRESS;
            case "completed", "complete", "done" -> ProgressCards.Status.COMPLETED;
            default -> ProgressCards.Status.PENDING;
        };
    }

    @Override
    public String toString() {
        return "ProgressCardCallback[" + Objects.requireNonNullElse(delegate.getToolDefinition().name(), "?")
                + "]";
    }
}
