package io.github.claw4j.agent.goal;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.ToolPermission;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 模型侧的三个目标工具（ADR-0027 决定三，照 OpenClaw 的 goal 工具）。
 *
 * <p><b>三个而不是一个带 {@code action} 参数的</b>：动作名进的是角色的授权表，
 * 而"能不能看目标"与"能不能把它标记成达成"是两种授权。合成一个的话，
 * 给了一个就等于给了另一个——与定时任务那三个工具同一个理由。
 *
 * <p><b>模型能改的只有两种状态</b>：{@code complete} 与 {@code blocked}。OpenClaw 的原话是
 * "模型不能在不作说明的情况下暂停、恢复、清除或替换目标。这些操作仍然是
 * 操作员/会话通过 {@code /goal} 和重置命令执行的控制"。所以 {@code update_goal}
 * <b>没有</b> {@code objective} 参数、也没有 {@code paused}——那两个分别属于
 * {@code /goal edit} 与 {@code /goal pause}，是人的按钮。
 *
 * <p><b>会话键从工具上下文来，模型没有参数可改</b>：键是 {@link ContextKeys#CONVERSATION}
 * （会话节点，**不含段**），值由 {@code ChatClientAgentPort} 算好。模型手上没有"给别的会话定目标"的入参，
 * 于是那条路径不存在，而不是"存在但被拒绝"。这也正是目标必须挂会话的原因：
 * 挂 run 的话，同一句话换个 runId 就看不见自己的目标了。
 */
public final class GoalTools implements ToolCallbackProvider {

    /** 读当前目标。 */
    public static final String GET = "get_goal";

    /** 新建。已有目标时失败。 */
    public static final String CREATE = "create_goal";

    /** 标记达成或阻塞。只有这两个状态。 */
    public static final String UPDATE = "update_goal";

    /** 没有会话键时的回答。与"没有目标"不同：那是没找到位置，这是位置都没有。 */
    private static final String NO_SESSION = "这一轮没有会话标识，目标无处可挂："
            + "目标是会话状态（换了会话就是另一个目标），认不出是哪一段就存不了。";

    private static final String NO_GOAL = "这个会话还没有目标。";

    private final SessionGoalStore goals;

    private final List<ToolCallback> tools;

    public GoalTools(SessionGoalStore goals) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.tools = List.of(new GetTool(), new CreateTool(), new UpdateTool());
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return tools.toArray(new ToolCallback[0]);
    }

    /**
     * 三个工具各自的权限登记。
     *
     * <p>一张业务表都不碰：它们改的是"这一段对话在往哪儿走"，不是业务数据。
     * 真正的越权风险在这一轮调用的业务工具上，那一步由鉴权 Advisor 按表权限判。
     */
    public static Map<String, ToolPermission> permissions() {
        return Map.of(GET, ToolPermission.of(GET),
                CREATE, ToolPermission.of(CREATE),
                UPDATE, ToolPermission.of(UPDATE));
    }

    // ---- 三个工具 ----

    private final class GetTool extends BaseTool {

        private GetTool() {
            super(GET,
                    "读取当前会话的目标：状态、目标内容、Token 用量和 Token 预算。"
                            + "拿不准现在在做什么、或者想知道用量还剩多少时调它。",
                    """
                            {
                              "type": "object",
                              "properties": {}
                            }""");
        }

        @Override
        String handle(Map<String, Object> args, String sessionKey) {
            return goals.of(sessionKey).map(GoalTools::render).orElse(NO_GOAL);
        }
    }

    private final class CreateTool extends BaseTool {

        private CreateTool() {
            super(CREATE,
                    "给这个会话定一个目标。**只有用户或系统指令明确要求时才建**："
                            + "这是「这一段对话要一起达成什么」的承诺，不是你顺手给自己加的待办。"
                            + "一个会话同时只能有一个目标；已有目标时这一步会失败，"
                            + "要换目标得让用户先 /goal clear。",
                    """
                            {
                              "type": "object",
                              "properties": {
                                "objective": {
                                  "type": "string",
                                  "description": "目标本身，一句话或一份清单。用户说的原话照抄，不要自己改写成更"专业"的说法"
                                },
                                "token_budget": {
                                  "type": "integer",
                                  "description": "可选的正数 Token 预算。不写就是不限额；写了就由这个数决定目标什么时候转为预算受限"
                                }
                              },
                              "required": ["objective"]
                            }""");
        }

        @Override
        String handle(Map<String, Object> args, String sessionKey) {
            String objective = required(args, "objective");
            Long budget = budget(args);
            return goals.start(sessionKey, objective, budget)
                    .map(created -> "已建目标。\n" + render(created))
                    .orElseGet(() -> goals.of(sessionKey)
                            .map(existing -> "这个会话已经有目标了，没有新建：\n" + render(existing)
                                    + "\n要换目标得先让用户 /goal clear。")
                            .orElse("目标没写进去（库连不上，或者迁移没跑过）。"
                                    + "别告诉用户「已经定好了」。"));
        }
    }

    private final class UpdateTool extends BaseTool {

        private UpdateTool() {
            super(UPDATE,
                    "把目标标记为 complete 或 blocked。**只有目标确实达成时才标 complete**；"
                            + "**只有同一个阻塞条件至少连续三个目标轮次都出现**，才标 blocked——"
                            + "一般性的困难、或者「还没打磨好」不算阻塞。"
                            + "额度快用完**不是**把没做完的活标成达成的理由。"
                            + "改目标状态**不会替你回一句话**：用户要的那个最终回答仍然要由你给出。"
                            + "暂停、恢复、清除、改写目标都不归你：那是用户用 /goal 按的按钮。",
                    """
                            {
                              "type": "object",
                              "properties": {
                                "status": {
                                  "type": "string",
                                  "enum": ["complete", "blocked"],
                                  "description": "complete = 目标已达成；blocked = 真实的阻塞，且它已经连续三个轮次都在"
                                },
                                "note": {
                                  "type": "string",
                                  "description": "一句话说明：达成是怎么验证的，或者卡在哪一件事上"
                                }
                              },
                              "required": ["status"]
                            }""");
        }

        @Override
        String handle(Map<String, Object> args, String sessionKey) {
            GoalStatus status = modelStatus(required(args, "status"));
            String note = text(args, "note");
            // 先读一次拿 goalId：变更请求必须指向"我看到的那一版目标"。
            // 读与写之间被换过目标时，SQL 里 goal_id 对不上，返回 0 行——
            // 这正是它文档里"旧按钮不能改到替换后的目标"那条。
            SessionGoal current = goals.of(sessionKey).orElse(null);
            if (current == null) {
                return NO_GOAL;
            }
            if (current.status() == GoalStatus.COMPLETE) {
                return "这个目标已经是达成状态了，没有再改。要开始别的事得先 /goal clear。";
            }
            return goals.setStatus(sessionKey, current.id(), status, note)
                    .map(changed -> "目标已标成" + changed.status().label() + "。\n" + render(changed))
                    .orElse("目标已经换了（或者没写进去），这一次没有改。重新 get_goal 看一眼再决定。");
        }
    }

    /**
     * 模型只能标这两个状态。
     *
     * <p><b>判据只有一处</b>：{@link GoalStatus#modelSettable()}。这里先把词映射成枚举，
     * 再用那个方法判"能不能设"。写成这样一个 switch 白名单也行，但那样同一条规则
     * （"只有 complete 与 blocked"）就有两份，改一处忘一处就漂。
     */
    private static GoalStatus modelStatus(String raw) {
        GoalStatus status;
        try {
            status = GoalStatus.fromColumn(raw.toLowerCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "不认识的状态「" + raw + "」。只能标 complete 或 blocked。");
        }
        if (!status.modelSettable()) {
            throw new IllegalArgumentException(
                    "只能标 complete 或 blocked，收到的是「" + raw + "」。"
                            + "暂停与恢复是用户用 /goal pause、/goal resume 按的按钮，不归你改。");
        }
        return status;
    }

    /** 摘要：形状照 OpenClaw 的 {@code /goal} 输出（状态 / 目标 / 已用 / 预算 / 命令）。 */
    static String render(SessionGoal goal) {
        StringBuilder text = new StringBuilder("目标状态：").append(goal.status().label()).append('\n')
                .append("目标：").append(goal.objective()).append('\n')
                .append("已用 Token：").append(tokens(goal.tokensUsed())).append('\n');
        if (goal.tokenBudget() != null) {
            text.append("Token 预算：").append(tokens(goal.tokensUsed())).append('/')
                    .append(tokens(goal.tokenBudget())).append('\n');
        } else {
            text.append("Token 预算：未设\n");
        }
        if (goal.note() != null && !goal.note().isBlank()) {
            text.append("状态说明：").append(goal.note()).append('\n');
        }
        text.append("命令：/goal edit <objective>、/goal pause");
        if (goal.status().resumable()) {
            text.append("、/goal resume");
        }
        return text.append("、/goal complete、/goal clear").toString();
    }

    /** {@code 1200} → {@code 1.2k}。摘要只求一眼看出量级，不报精确值。 */
    static String tokens(long value) {
        if (value < 1000) {
            return Long.toString(value);
        }
        String scaled = String.format(java.util.Locale.ROOT, "%.1f", value / 1000.0);
        return (scaled.endsWith(".0") ? scaled.substring(0, scaled.length() - 2) : scaled) + "k";
    }

    // ---- 一条工具调用的公共半截 ----

    /**
     * 定义、会话键、参数解析、参数错误变回一句话。
     *
     * <p>参数问题不抛出：模型换一个参数就能自救，抛出去会变成整个请求 500，
     * 而在用户那边只是"助手坏了"。
     */
    private abstract class BaseTool implements ToolCallback {

        private final ToolDefinition definition;

        BaseTool(String name, String description, String schema) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        /** 单参入口没有 ToolContext，也就没有会话键。 */
        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String sessionKey = sessionKeyOf(toolContext);
            if (sessionKey == null) {
                return NO_SESSION;
            }
            try {
                return handle(parse(toolInput), sessionKey);
            } catch (IllegalArgumentException bad) {
                return "没办成：" + bad.getMessage();
            }
        }

        abstract String handle(Map<String, Object> args, String sessionKey);
    }

    /**
     * 目标挂在会话节点上（键不含段），所以读的是 {@link ContextKeys#CONVERSATION}，
     * 不是框架记忆用的 {@code CONVERSATION_ID}（那个含段）。
     *
     * <p>两者不是同一个字符串：{@code /new} 之后目标被清掉、记忆换到新段。
     * 拿错键的表现是"每个新段都读不到目标"——看起来像"目标丢了"，
     * 而实际上它挂在节点上、只是被查到了另一个键上。
     */
    private static String sessionKeyOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.CONVERSATION);
        return value instanceof String sessionKey && !sessionKey.isBlank() ? sessionKey : null;
    }

    private static Map<String, Object> parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return new JsonHelper().fromJsonToMap(toolInput);
        }
        catch (RuntimeException failure) {
            throw new IllegalArgumentException("参数不是 JSON 对象：" + toolInput, failure);
        }
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static String required(Map<String, Object> args, String key) {
        String value = text(args, key);
        if (value == null) {
            throw new IllegalArgumentException("缺 " + key);
        }
        return value;
    }

    /** @return null 表示不限额 */
    private static Long budget(Map<String, Object> args) {
        Object value = args.get("token_budget");
        if (value == null) {
            return null;
        }
        long parsed;
        if (value instanceof Number number) {
            if (number.doubleValue() != Math.floor(number.doubleValue())) {
                throw new IllegalArgumentException("token_budget 要是一个整数");
            }
            parsed = number.longValue();
        }
        else {
            try {
                parsed = Long.parseLong(value.toString().strip());
            }
            catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("token_budget 要是一个整数，收到的是 " + value);
            }
        }
        if (parsed <= 0) {
            // 0 的语义是"一点额度都不给"，从这个数出发的目标第一轮就是预算受限。
            // 想要不限额就别传这个参数。
            throw new IllegalArgumentException("token_budget 必须是正数，收到的是 " + parsed
                    + "。不限额就别传这个参数");
        }
        return parsed;
    }
}
