package io.github.claw4j.agent.intent;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code intent} 工具：建、查、撤（ADR-0036）。
 *
 * <p>一个工具三个动作，而不是三个工具：它们操作的是同一张表、同一条生命周期，
 * 分成三个工具只会让"给了查就等于给了撤"这件事变得不容易看出来（对比
 * {@code ScheduleTools}：那三个的权限边界不同，所以分开）。
 *
 * <p>归属只看身份：owner 从 {@link ToolContext} 里的 {@link Principal} 取，不从参数取。
 * 参数里带 owner 的写法，等于让模型自己声明"我是谁"。
 */
public final class IntentTools implements ToolCallbackProvider {

    /** 工具名。 */
    public static final String NAME = "intent";

    private static final String NO_PRINCIPAL = "这一轮没有携带身份，意图记不了："
            + "它按人存、按人触发，认不出你是谁就没法建、也没法撤。";

    private final StandingIntents store;

    /**
     * @param store 意图存储
     */
    public IntentTools(StandingIntents store) {
        this.store = store;
    }

    /**
     * @return 只含 {@code intent} 的清单
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks();
    }

    /**
     * 建 / 查 / 撤一条事件条件提醒。
     *
     * @param action          动作：{@code create} / {@code list} / {@code cancel}
     * @param description     命中时提醒什么（create 必填）
     * @param triggers        触发词，逗号或分号分隔（create 必填）
     * @param maxFires        最多提醒几次，默认 3
     * @param cooldownSeconds 两次之间至少隔几秒，默认 86400
     * @param expiresInDays   几天后过期，默认 90
     * @param id              要取消的编号（cancel 必填）
     * @param context         工具上下文，取身份
     * @return 给模型看的结果
     */
    @Tool(name = NAME, description = """
            留下一条"以后遇到某种说法就提醒我"的记忆（不是定时任务）。
            适合「以后有人问客户数，先确认是签约数还是活跃数」这类要求；
            要「每天九点提醒我」请用 schedule_task。
            触发词命中后会在那一轮回复里出现一小段提醒，最多提醒几次、多久冷却、何时过期都可配。
            """)
    public String intent(
            @ToolParam(description = "动作：create 新建、list 列出自己的、cancel 取消") String action,
            @ToolParam(required = false, description = "命中时提醒什么，一句话") String description,
            @ToolParam(required = false, description = "触发词，逗号或分号分隔；中文按子串命中，"
                    + "英文按整词命中") String triggers,
            @ToolParam(required = false, description = "最多提醒几次，默认 3") Integer maxFires,
            @ToolParam(required = false, description = "两次提醒之间至少隔几秒，默认 86400（一天）")
            Integer cooldownSeconds,
            @ToolParam(required = false, description = "多少天后过期，默认 90") Integer expiresInDays,
            @ToolParam(required = false, description = "要取消的意图编号，list 里能看到") String id,
            ToolContext context) {
        Optional<Principal> principal = principalOf(context);
        if (principal.isEmpty()) {
            return NO_PRINCIPAL;
        }
        String owner = principal.get().userId();
        String verb = action == null ? "" : action.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (verb) {
            case "create" -> create(owner, principal.get(), description, triggers, maxFires,
                    cooldownSeconds, expiresInDays, context);
            case "list" -> list(owner);
            case "cancel" -> cancel(owner, id);
            default -> "action 只能是 create / list / cancel，收到的是「" + action + "」。";
        };
    }

    private String create(String owner, Principal principal, String description, String triggers,
                          Integer maxFires, Integer cooldownSeconds, Integer expiresInDays,
                          ToolContext context) {
        if (description == null || description.isBlank()) {
            return "要建一条意图，得有 description：命中时提醒你什么。";
        }
        if (triggers == null || triggers.isBlank()) {
            return "要建一条意图，得有 triggers：什么样的话算命中。";
        }
        int fires = maxFires == null ? StandingIntents.DEFAULT_MAX_FIRES : maxFires;
        if (fires <= 0) {
            return "maxFires 必须是正整数。";
        }
        int cooldown = cooldownSeconds == null
                ? StandingIntents.DEFAULT_COOLDOWN_SECONDS : cooldownSeconds;
        if (cooldown < 0) {
            return "cooldownSeconds 不能是负数（0 表示不冷却）。";
        }
        int days = expiresInDays == null ? StandingIntents.DEFAULT_EXPIRES_DAYS : expiresInDays;
        if (days <= 0) {
            return "expiresInDays 必须是正整数。";
        }
        String channel = stringOf(context, ContextKeys.CHANNEL);
        StandingIntent created = store.create(new StandingIntents.New(owner,
                description.strip(), triggers.strip(), channel, principal.userId(),
                principal.userId(), Instant.now().plus(Duration.ofDays(days)),
                fires, cooldown, stringOf(context,
                org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID)));
        return "记下了：" + created.id() + "。以后出现「" + created.triggerKeywords() + "」时提醒你「"
                + created.description() + "」；最多 " + created.maxFires() + " 次，冷却 "
                + created.cooldownSeconds() + " 秒，" + days + " 天后自动过期。";
    }

    private String list(String owner) {
        List<StandingIntent> all = store.list(owner);
        if (all.isEmpty()) {
            return "你还没有留下任何意图。";
        }
        StringBuilder out = new StringBuilder("你留下的意图：\n");
        for (StandingIntent intent : all) {
            out.append("- ").append(intent.id())
                    .append(" [").append(intent.status().id()).append("] ")
                    .append("触发词「").append(intent.triggerKeywords()).append("」 → ")
                    .append(intent.description())
                    .append("（已触发 ").append(intent.fireCount()).append('/')
                    .append(intent.maxFires()).append("）\n");
        }
        return out.toString();
    }

    private String cancel(String owner, String id) {
        if (id == null || id.isBlank()) {
            return "要取消哪一条？给个编号（list 里能看到）。";
        }
        return store.cancel(owner, id.strip())
                ? "取消了：" + id.strip() + "。"
                : "没找到这条意图，或者它已经不在了：" + id.strip();
    }

    private static Optional<Principal> principalOf(ToolContext context) {
        Map<String, Object> values = context == null ? null : context.getContext();
        Object value = values == null ? null : values.get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? Optional.of(principal) : Optional.empty();
    }

    private static String stringOf(ToolContext context, String key) {
        Map<String, Object> values = context == null ? null : context.getContext();
        Object value = values == null ? null : values.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
