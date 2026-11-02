package io.github.claw4j.agent.run;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.ToolPermission;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把"打算怎么干"写进 run：{@code update_plan}（A03）。
 *
 * <p><b>计划为什么不放在上下文里</b>：会话被压缩之后上下文里就没有它了，而"还剩什么没做"
 * 恰恰是最不能丢的一条信息——丢了的表现不是答不上来，是<b>把做完的那一步重做一遍</b>，
 * 看起来跟"接着做"一模一样。计划落在 {@code agent_step} 里（{@link StepKind#PLAN}），
 * 压缩压不掉数据库。
 *
 * <p><b>runId 来自上下文，不是来自参数</b>：入口把这一轮的 run 放进 {@code ToolContext}
 * （{@link ContextKeys#RUN_ID}），模型点名不了别人的 run，也就不需要在这里再查一次归属。
 * 让模型传 {@code run_id} 等于给它一张"往别人那件事上写计划"的通行证，
 * 而挡住它的代价是每次调用多一次往返。
 *
 * <p><b>回执里一定要写"还剩哪几步没做"</b>：这是这条工具唯一被要求的产出。
 * 只回一个 JSON 让模型自己去数，等于把"还剩什么"这件事又交回了自然语言。
 */
public final class UpdatePlanTool implements ToolCallback {

    /** 工具名。写进提示词与鉴权登记表，两处都认这一个常量。 */
    public static final String TOOL_NAME = "update_plan";

    /**
     * 需要的动作名，与工具名同值。
     *
     * <p>不复用 {@code read}：它读的是业务表，而这里改的是"这一件事自己的计划"。
     * 复用会让"能不能改自己的计划"与"能不能查销售单"变成同一件事。
     */
    public static final String ACTION = "update_plan";

    private static final String DESCRIPTION = "把这件要做的事按顺序列成计划，并逐条标出做没做。"
            + "计划存在这件事（run）里，不在对话上下文里：会话被压缩之后照样读得到还剩什么没做。"
            + "多步的任务开工前先列一次；每做完一步就用 done 把它的序号标上，"
            + "决定不做的用 skipped。改主意就带 steps 再调一次（旧的那版不再算数）。"
            + "什么都不传就是只读当前计划。";

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "steps": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "整体重写计划：按要做的顺序写，每步一句话；旧的那版不再算数"
                },
                "done": {
                  "type": "array",
                  "items": { "type": "integer" },
                  "description": "按计划里的序号（从 1 开始）把这几步标成已做"
                },
                "skipped": {
                  "type": "array",
                  "items": { "type": "integer" },
                  "description": "按计划里的序号把这几步标成跳过（决定不做）"
                }
              }
            }""";

    private final RunStore runs;

    private final ToolDefinition definition;

    public UpdatePlanTool(RunStore runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.definition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    /** 单参入口没有上下文，也就没有 runId：这一轮没有可写的计划。 */
    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String runId = runIdOf(toolContext);
        if (runId == null) {
            return "这一轮没有 run（没装配 run 存储，或者这一轮没开出来），计划无处可写。";
        }
        Map<String, Object> input;
        try {
            input = parse(toolInput);
            List<String> items = strings(input, "steps");
            if (items != null) {
                runs.setPlan(runId, items);
            }
            mark(runId, integers(input, "done"), StepStatus.DONE);
            mark(runId, integers(input, "skipped"), StepStatus.SKIPPED);
        }
        catch (IllegalArgumentException bad) {
            // 参数问题回给模型让它自己改，不要抛出去变成整轮 500：
            // 序号写错、steps 不是数组，每一条模型都能靠换参数自救。
            return "计划没写进去：" + bad.getMessage();
        }
        return render(runs.planOf(runId), runId);
    }

    private void mark(String runId, List<Integer> ordinals, StepStatus status) {
        if (ordinals == null) {
            return;
        }
        for (int ordinal : ordinals) {
            // 标不上的原因只有一个交代得清的（没有这一步），
            // 其余（没记下来）由存储降级消化——run 是记账，不是回答的前置条件。
            runs.markPlan(runId, ordinal, status);
        }
    }

    /** 回执：一份能一眼看出"还剩哪几步"的清单，不是一段给代码解析的 JSON。 */
    static String render(List<Step> plan, String runId) {
        if (plan.isEmpty()) {
            return "这件事还没有计划。带 steps 调用可以把要做的事按顺序列下来。";
        }
        StringBuilder text = new StringBuilder("计划共 ").append(plan.size()).append(" 步（run ")
                .append(runId, 0, Math.min(8, runId.length())).append("）：\n");
        List<String> outstanding = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Step step = plan.get(i);
            text.append("第 ").append(i + 1).append(" 步 [").append(step.status().label()).append("] ")
                    .append(step.input() == null ? "" : step.input().strip()).append('\n');
            if (step.status() == StepStatus.PENDING) {
                outstanding.add(String.valueOf(i + 1));
            }
        }
        if (outstanding.isEmpty()) {
            return text.append("没有待做的步骤了。").toString();
        }
        return text.append("还剩 ").append(outstanding.size()).append(" 步没做：第 ")
                .append(String.join("、", outstanding)).append(" 步。").toString();
    }

    private static String runIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.RUN_ID);
        return value instanceof String runId && !runId.isBlank() ? runId : null;
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

    /** @return null 表示没传这个参数 */
    private static List<String> strings(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(key + " 要是一个字符串数组");
        }
        List<String> items = new ArrayList<>();
        for (Object each : raw) {
            if (each == null || each.toString().isBlank()) {
                continue;
            }
            items.add(each.toString().strip());
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException(key + " 不能是空数组：不传它就是不改计划");
        }
        return items;
    }

    /** @return null 表示没传这个参数 */
    private static List<Integer> integers(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(key + " 要是一个序号数组");
        }
        List<Integer> ordinals = new ArrayList<>();
        for (Object each : raw) {
            if (each instanceof Number number) {
                ordinals.add(number.intValue());
                continue;
            }
            if (each == null) {
                throw new IllegalArgumentException(key + " 里只能是序号，收到一个空值");
            }
            try {
                ordinals.add(Integer.valueOf(each.toString().trim()));
            }
            catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException(key + " 里只能是序号，收到的是 " + each);
            }
        }
        return ordinals;
    }

    /** 权限登记：动作是"改自己的计划"，一张业务表都不碰。 */
    public static ToolPermission permission() {
        return ToolPermission.of(ACTION);
    }
}
