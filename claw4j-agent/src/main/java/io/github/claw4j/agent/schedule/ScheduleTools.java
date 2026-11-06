package io.github.claw4j.agent.schedule;

import io.github.claw4j.agent.channel.DeliveryTarget;
import io.github.claw4j.agent.session.SessionKeys;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 定时任务的三件工具：建一条、看看有哪些、取消掉（P05）。
 *
 * <p><b>为什么是三个工具而不是一个带 {@code action} 参数的</b>：动作名进的是角色的授权表
 * （{@code claw4j.channel.http.roles} 里那一行），而"能不能新建"与"能不能取消"
 * 是两种授权。合成一个的话，给了"能看自己有哪些任务"就等于给了"能取消"。
 *
 * <p><b>建的时候就把话说死</b>：这张票的标题是"一次授权，多次执行"——
 * 那一次授权指的是<b>建这一下</b>。所以三件事在建的时候全部核一遍：cron 解析得了、
 * 要用的工具主人现在都有、这些工具里没有"每次都要人批准"的。核不过就当场拒，
 * 理由原样交给模型——让它换个说法再来，比明天九点才发现跑不了好得多。
 *
 * <p><b>到点不再问人</b>：任务用主人的身份跑，但权限按那一刻的角色表重算
 * （{@link ScheduleAuthorization}）。要审批的工具因此进不了定时任务：到点没人能点按钮，
 * 结果是每天挂一条没人看见的待批请求，而不是每天办成一件事。
 *
 * <p><b>归属只看身份，不看这个工具实例</b>：{@code owner} 由
 * {@link SessionKeys#ownerOf} 算，与 run 同一把键；"我的任务"与"我的 run"因此是同一种东西。
 * 取消也按这把键判——按 id 判的话，猜中别人的编号就能停别人的任务。
 */
public final class ScheduleTools implements ToolCallbackProvider {

    /** 建一条。名字同时是动作名，与 {@code update_plan} 那套一个口径。 */
    public static final String CREATE = "schedule_task";

    /** 列出自己建的。 */
    public static final String LIST = "list_schedules";

    /** 取消一条（软停用：库里的记录留着，谁建的、什么时候建的都还查得到）。 */
    public static final String CANCEL = "cancel_schedule";

    /**
     * 编号显示几位。
     *
     * <p>UUID 全长 36 位，模型抄错一位的概率不低；而"我的任务"里前 8 位撞车的可能几乎没有。
     * 取消按前缀匹配，于是显示 8 位、用它也能取消回来。
     */
    private static final int SHORT_ID = 8;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String NO_PRINCIPAL = "这一轮没有携带身份，这件事办不成："
            + "定时任务要按你的权限跑，认不出你是谁就没法建、也没法取消。";

    private final ScheduleStore store;

    private final ScheduleAuthorization authorization;

    private final List<ToolCallback> tools;

    public ScheduleTools(ScheduleStore store, ScheduleAuthorization authorization) {
        this.store = Objects.requireNonNull(store, "store");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.tools = List.of(new CreateTool(), new ListTool(), new CancelTool());
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return tools.toArray(new ToolCallback[0]);
    }

    /**
     * 三个工具各自的权限登记。
     *
     * <p>三张表都不碰（{@link ToolPermission#tables()} 为空）：它们改的是"以后什么时候做什么"，
     * 不是业务数据。真正的越权风险在任务跑起来之后，那一步由鉴权 Advisor
     * 按<b>那时候</b>的工具权限判。
     */
    public static Map<String, ToolPermission> permissions() {
        return Map.of(CREATE, ToolPermission.of(CREATE),
                LIST, ToolPermission.of(LIST),
                CANCEL, ToolPermission.of(CANCEL));
    }

    // ---- 三个工具 ----

    private final class CreateTool extends BaseTool {

        private CreateTool() {
            super(CREATE,
                    "定一个以后按点做的任务：到点用你的身份把 goal 重新跑一遍（开一个新的 run）。"
                            + "适合「以后每天早上九点把成交额发我」这类要求。"
                            + "cron 要 6 段（秒 分 时 日 月 周），例如 0 0 9 * * * 是每天九点。"
                            + "tools 写这条任务会用到的工具名：现在没有这些权限就建不成，"
                            + "而建不成比每天跑出一次失败好。",
                    """
                            {
                              "type": "object",
                              "properties": {
                                "goal": {
                                  "type": "string",
                                  "description": "到点要做的那件事，一句话写清：到点会用这句话重新问一遍，那时的对话上下文已经不在了"
                                },
                                "cron": {
                                  "type": "string",
                                  "description": "6 段 cron：秒 分 时 日 月 周。0 0 9 * * * = 每天九点；0 0 9 * * MON-FRI = 工作日九点"
                                },
                                "zone": {
                                  "type": "string",
                                  "description": "时区，例如 Asia/Shanghai；不写就用部署所在时区"
                                },
                                "tools": {
                                  "type": "array",
                                  "items": { "type": "string" },
                                  "description": "这条任务会用到的工具名（如 query_sales）。确实只需要你直接回答就写 []"
                                },
                                "notify": {
                                  "type": "string",
                                  "description": "结果要不要主动推给你：silent（默认，自己来查）或 done_only（跑完推一条）"
                                }
                              },
                              "required": ["goal", "cron", "tools"]
                            }""");
        }

        @Override
        String handle(Map<String, Object> args, Principal principal, ToolContext toolContext) {
            String goal = required(args, "goal");
            String cron = required(args, "cron");
            String zone = text(args, "zone");
            List<String> requiredTools = strings(args, "tools");
            NotifyPolicy notify = policy(text(args, "notify"));

            // ① cron 与时区：写错了当场说，并且把"下一次"算出来给它看——
            // 模型自己不理解 6 段 cron 的日历语义，"明天九点"这种话只有算过才说得准。
            Instant next;
            try {
                next = ScheduleStore.firstRunAt(cron, zone);
            } catch (IllegalArgumentException broken) {
                return "没建成：" + broken.getMessage() + "。换一个再试。";
            }

            // ② 权限：任务到点只能按主人的权限跑，现在没有的，明天也没有。
            Optional<String> refusal = authorization.refusalFor(principal, requiredTools);
            if (refusal.isPresent()) {
                return "没建成：" + refusal.get() + "。这条任务到点只能按你的权限跑，"
                        + "现在跑不了的东西，明天也不会自己变得跑得了。";
            }

            // ③ 角色表：到点要靠它重算权限。认不出来就等于建一条永远跑不起来的任务。
            if (authorization.currentOwner(principal.userId(), principal.role()).isEmpty()) {
                return "没建成：认不出你的角色（" + principal.role() + "）现在的权限，"
                        + "到点那一刻也就没法用你的身份跑。先让这个角色回到授权表里，再建。";
            }

            // ④ 落到哪儿：这件事登记过的第一个会话就是它的出处。没有地址也能建，
            //    只是结果只能按 run id 取。
            Optional<DeliveryTarget> address = addressOf(toolContext);
            Optional<Schedule> created = store.create(new ScheduleStore.Request(
                    SessionKeys.ownerOf(principal), goal, cron, zone, notify,
                    address.map(DeliveryTarget::channel).orElse(null),
                    address.map(DeliveryTarget::channelRef).orElse(null),
                    principal, requiredTools));
            if (created.isEmpty()) {
                return "没建成：任务没写进库（库连不上，或者迁移没跑过）。这件事现在办不了，"
                        + "别让用户以为已经定好了。";
            }
            return createdText(created.get(), next, notify, requiredTools, address.isPresent());
        }
    }

    private final class ListTool extends BaseTool {

        private ListTool() {
            super(LIST,
                    "看看自己定了哪些定时任务：编号、cron、下一次什么时候跑、还启不启用。",
                    """
                            {
                              "type": "object",
                              "properties": {}
                            }""");
        }

        @Override
        String handle(Map<String, Object> args, Principal principal, ToolContext toolContext) {
            List<Schedule> mine = store.listOf(SessionKeys.ownerOf(principal));
            if (mine.isEmpty()) {
                return "你还没有定时任务。要定一个就用 " + CREATE + "。";
            }
            StringBuilder text = new StringBuilder("你的定时任务（" + mine.size() + " 条）：\n");
            for (Schedule task : mine) {
                text.append("- ").append(shortId(task.id())).append(" ｜ ")
                        .append(task.enabled() ? "启用" : "已停用")
                        .append(" ｜ ").append(task.cron())
                        .append("（").append(task.zone() == null ? "部署时区" : task.zone()).append("）");
                if (task.enabled()) {
                    text.append(" ｜ 下次 ").append(when(task.nextRunAt(), task.zone()));
                } else if (task.disabledReason() != null) {
                    text.append(" ｜ 原因：").append(task.disabledReason());
                }
                text.append('\n').append("  ").append(task.goal()).append('\n');
            }
            return text.toString();
        }
    }

    private final class CancelTool extends BaseTool {

        private CancelTool() {
            super(CANCEL,
                    "取消自己定的某一条定时任务：它不会再跑；库里的记录留着"
                            + "（谁建的、什么时候建的都还查得到）。",
                    """
                            {
                              "type": "object",
                              "properties": {
                                "id": {
                                  "type": "string",
                                  "description": "任务编号，可以只写开头 8 位（列表里显示的就是这 8 位）"
                                }
                              },
                              "required": ["id"]
                            }""");
        }

        @Override
        String handle(Map<String, Object> args, Principal principal, ToolContext toolContext) {
            String wanted = required(args, "id");
            List<Schedule> matched = store.listOf(SessionKeys.ownerOf(principal)).stream()
                    .filter(task -> task.id().startsWith(wanted))
                    .toList();
            if (matched.size() > 1) {
                return "有 " + matched.size() + " 条任务的编号都以 " + wanted + " 开头，"
                        + "多写几位再试（" + String.join("、", matched.stream()
                        .map(task -> shortId(task.id())).toList()) + "）。";
            }
            // 别人的任务与不存在的任务给同一个答案：分开说等于替对方确认"这个编号存在"。
            if (matched.isEmpty()) {
                return "没有找到编号以 " + wanted + " 开头的、属于你的定时任务。"
                        + "先用 " + LIST + " 看一眼。";
            }
            Schedule task = matched.getFirst();
            if (!task.enabled()) {
                return "这条任务已经是停用状态了（"
                        + (task.disabledReason() == null ? "没有说明" : task.disabledReason())
                        + "），不用再取消。";
            }
            store.disable(task.id(), "创建者自己取消的");
            return "已取消：" + shortId(task.id()) + "（" + task.goal() + "）。它不会再跑了；"
                    + "记录还在库里，想恢复就重新建一条。";
        }
    }

    // ---- 回执 ----

    private static String createdText(Schedule task, Instant next, NotifyPolicy notify,
                                      List<String> requiredTools, boolean addressable) {
        String zone = task.zone();
        return "已建好，编号 " + shortId(task.id()) + "（取消时用它就够了）。\n"
                + "- cron " + task.cron() + "（" + (zone == null ? "部署时区" : zone) + "），"
                + "下一次 " + when(next, zone) + "\n"
                + "- 要用的工具：" + (requiredTools.isEmpty() ? "没有（只靠模型回答）"
                        : String.join("、", requiredTools)) + "\n"
                + "- 到点会用你现在的身份重新核一遍权限再跑：权限被收回时它会停下并告诉你，"
                + "不会带着旧权限继续\n"
                + "- 结果：" + (notify == NotifyPolicy.SILENT ? "不主动推" : "跑完推一条")
                + (addressable ? "" : "（这件事没有登记过会话，只能按 run id 查）")
                + "，跑出来的每一个 run 都能用 GET /api/runs/{id} 取回全文";
    }

    /** 时间点按任务自己的时区写。{@code null} 时区 = 部署所在时区，与解析时同一个口径。 */
    private static String when(Instant at, String zone) {
        if (at == null) {
            return "（没有下一次）";
        }
        return WHEN.format(LocalDateTime.ofInstant(at, ScheduleStore.zoneOf(zone)));
    }

    private static String shortId(String id) {
        return id.length() <= SHORT_ID ? id : id.substring(0, SHORT_ID);
    }

    // ---- 一条工具调用的公共半截 ----

    /**
     * 定义、身份、参数解析、参数错误变回一句话。
     *
     * <p><b>参数问题不抛出</b>：模型把 cron 写成 5 段这件事，换一个参数就能自救；
     * 抛出去会变成整个请求 500，而在用户那边只是"助手坏了"。
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

        /** 单参入口没有 ToolContext，也就没有身份：按"认不出是谁"处理，不放行。 */
        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            Principal principal = principalOf(toolContext);
            if (principal == null) {
                return NO_PRINCIPAL;
            }
            try {
                return handle(parse(toolInput), principal, toolContext);
            } catch (IllegalArgumentException bad) {
                return "没办成：" + bad.getMessage();
            }
        }

        abstract String handle(Map<String, Object> args, Principal principal, ToolContext toolContext);
    }

    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }

    /** 这一轮的 run。为 null 表示不在一个 run 里——那时任务建得起来，但结果没有可推的地址。 */
    private static String runIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.TURN_ID);
        return value instanceof String runId && !runId.isBlank() ? runId : null;
    }

    private static Map<String, Object> parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return new JsonHelper().fromJsonToMap(toolInput);
        } catch (RuntimeException failure) {
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

    /** 必填的字符串：缺了要说清缺哪个——{@code goal} 与 {@code cron} 少一个都建不成。 */
    private static String required(Map<String, Object> args, String key) {
        String value = text(args, key);
        if (value == null) {
            throw new IllegalArgumentException("缺 " + key + "：这条任务要靠它才知道做什么、什么时候做");
        }
        return value;
    }

    private static List<String> strings(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(key + " 要是一个字符串数组");
        }
        List<String> items = new ArrayList<>();
        for (Object each : raw) {
            if (each != null && !each.toString().isBlank()) {
                items.add(each.toString().strip());
            }
        }
        return List.copyOf(items);
    }

    private static NotifyPolicy policy(String notify) {
        if (notify == null) {
            return NotifyPolicy.SILENT;
        }
        return switch (notify) {
            case "silent" -> NotifyPolicy.SILENT;
            case "done_only" -> NotifyPolicy.DONE_ONLY;
            default -> throw new IllegalArgumentException(
                    "notify 只有 silent 与 done_only 两种，收到的是 " + notify);
        };
    }

    /**
     * 结果推回哪儿：这一轮从哪个通道、哪个会话来的（ADR-0027）。
     *
     * <p>以前按 runId 去一张投递表查，那张表已经不在了——
     * 地址本来就是这一轮的属性。拿不到就是"没地方推"，任务照建，结果只能按 id 取。
     */
    private static Optional<DeliveryTarget> addressOf(ToolContext toolContext) {
        if (toolContext == null) {
            return Optional.empty();
        }
        String channel = textAt(toolContext, ContextKeys.CHANNEL);
        String chatRef = textAt(toolContext, ContextKeys.CHAT_REF);
        if (channel == null || chatRef == null) {
            return Optional.empty();
        }
        return Optional.of(new DeliveryTarget(channel, chatRef));
    }

    private static String textAt(ToolContext toolContext, String key) {
        Object value = toolContext.getContext().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
