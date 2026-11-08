package io.github.claw4j.agent.session;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把"回忆刚才说过什么"接成模型手上的一个工具：{@code recall_session}（M3-03）。
 *
 * <p><b>它查多大范围是可配的，不是写死的</b>（票 05）：{@code claw4j.tools.sessions.visibility}
 * 有四个值，照 OpenClaw 的 {@code tools.sessions.visibility}，默认 {@code all}。
 * "能不能问这一段"与"去哪查"都收在 {@link SessionRecallScope} 里，这个类只管
 * 把三种拒绝翻成三句不同的话、让模型能自救，再拼结果。
 *
 * <p><b>原来的口径是"只查当前这一段"，那只是 {@code self} 这一档</b>：会话键按
 * "人 + 权限 + 通道 + chatId" 分段（{@link SessionKeys}），{@code self} 下召回跟着同一个粒度走——
 * 网页私聊里查到的 128,400，不会被它在项目群里念出来。权限指纹挡不住那一层：
 * 它保证"权限一样才共享"，而这里听众不一样。那次收窄是对的（真泄露），
 * 但它不该把开关一起删掉：收窄是四档里的一档，不是唯一的一档。
 *
 * <p><b>为什么不直接用框架的 {@code CrossSessionRecallTools}</b>：它按
 * {@code SessionService#findEventsByUserId} 查（{@code WHERE user_id = ?}），而我们的
 * {@code user_id} 是 {@code userId@权限指纹}——跨用户时它一条都查不到，
 * {@code all} 会静默退化成"什么也没有"，而那与"历史里没说过"在结果上完全一样。
 * 跨会话那一档因此走 {@link CrossSessionEvents}（一条 SQL），{@code self} 仍然走
 * {@code getEvents(sessionId, filter)}：查询条件本身把范围钉死，不靠事后过滤。
 *
 * <p><b>会话 id 由宿主写进工具上下文，模型没有参数可改</b>：键是
 * {@link ChatMemory#CONVERSATION_ID}，值来自 {@code ChatClientAgentPort}（那里已经算好
 * {@code sessionId}）。模型手上没有"换一个会话查"的入参，于是"查别人的聊天记录"
 * 这条路径不是一个可以被点开的选项，而是一条按配置放开的范围。
 *
 * <p><b>关键词匹配，不是语义召回</b>：按词在事件正文里找，换个说法问同一件事它找不到。
 * 别在文档或提示词里写成"语义召回"——两者差的是 embedding 与一张向量表。
 *
 * <p><b>结果会截断再交给模型</b>：{@code pageSize} 有上限，整段文本再按字符裁一次。
 * 五条长对话一口气塞进提示词，召回省下的上下文就白省了。
 *
 * <p><b>它现在的价值是"把被压缩掉的原文找回来"</b>：压缩会把早期事件归档或折成摘要
 * （{@code EventFilter} 的 {@code excludeArchived} 默认 false，归档的照样查得到），
 * 摘要留结论、丢细节；"上次那批单子到底怎么定的"这类细节只能靠关键词回查。
 */
public final class RecallSessionTool implements ToolCallback {

    /** 工具名。写进提示词与鉴权登记表，两处都认这一个常量。 */
    public static final String TOOL_NAME = "recall_session";

    /**
     * 需要的动作名，与工具名同值。
     *
     * <p>复用 {@code read} 是不对的：{@code read} 的语义是"读业务表"，拿它兜住"读自己的历史"
     * 会让"能不能回忆"和"能不能查销售单"变成同一件事。只给 read-only 与 admin
     * （见 application.yml 的 {@code actions}），guest 不给——他能读的那张 region 表
     * 与"他过去说过什么"没有关系。
     */
    public static final String ACTION = "recall_session";

    /**
     * 单次召回最多取几条。
     *
     * <p>框架默认 10，这里压到 5：召回是一段**额外**塞进提示词的上下文，
     * 而它换来的通常只是其中一两条有用。取多了是每轮都付这份钱。
     */
    static final int MAX_PAGE_SIZE = 5;

    /**
     * 整段结果最多多少个字符。
     *
     * <p>条数上限挡不住"一条就一万字"——事件存的是整条消息正文。
     * 按字符再裁一次，两种越界都兜住。
     */
    static final int MAX_RESULT_CHARS = 2000;

    /**
     * 一次最多几个关键词。
     *
     * <p>与框架同值（{@code CrossSessionRecallTools.MAX_QUERY_TERMS}）：词越多命中越窄，
     * 而模型很容易把一句话整句拆成二十个词塞进来。超过就拒，让它自己收敛。
     */
    static final int MAX_QUERY_TERMS = 20;

    /**
     * 工具描述里**不随范围变**的那一半：这是本段历史里更早的部分、按关键词匹配（要挑词）、
     * 以及与"翻当前上下文"的区别。
     *
     * <p>范围那半句由 {@link #descriptionFor} 按生效值拼，不写死在这里：
     * 写死"看不到别的会话"的话，{@code all} 下模型会拿这句去**拒绝**一次本来查得到的召回
     * ——那是"模型被喂了错的能力清单"，与 {@code ScheduleTools} 那条同一类问题。
     */
    private static final String DESCRIPTION_HEAD = "在会话历史里按关键词搜索。"
            + "用于回忆当前上下文里已经没有的内容（更早的、被压缩掉的细节，以前定过的口径）。"
            + "按关键词匹配，多个词用英文逗号分隔；换个说法问同一件事它找不到，那就换一组更贴近原文的词。";

    /** 范围是当前这一段的说法。{@code tree} 也用它：子 Agent 会话今天不落库，差集是空的。 */
    private static final String SELF_SCOPE = "范围是当前这一段对话：看不到你在别的会话或群聊里说过的话。";

    /** 跨会话档的说法：要顺带交代"结果里带出处"，否则模型会把两段对话揉成一段回答。 */
    private static final String CROSS_SCOPE = "范围是本部署的全部会话，"
            + "包括其他用户与其他群聊；结果里每一条都带着它来自哪一段，引用时要说清是哪一段里的。";

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "关键词。多个词用英文逗号分隔（最多 20 个），按原文里的字面词匹配"
                },
                "match_mode": {
                  "type": "string",
                  "enum": ["any", "all"],
                  "description": "多个关键词怎么组合：any 命中任一（默认），all 必须全中"
                },
                "since": {
                  "type": "string",
                  "description": "只看这个时刻之后的历史，ISO-8601，例如 2026-10-01T00:00:00Z。不填就是全部"
                },
                "page": {
                  "type": "integer",
                  "description": "页码，从 0 开始。只有第一页没找到时才往后翻"
                }
              },
              "required": ["query"]
            }""";

    private final SessionRecallScope scope;

    private final ToolDefinition definition;

    public RecallSessionTool(SessionRecallScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.definition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(descriptionFor(scope.visibility()))
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    /**
     * 工具描述 = 不变的那半 + 生效范围那半句。
     *
     * <p>包内可见是为了让用例能直接断言文本：范围改了而描述没改，症状是模型主动拒绝
     * 一次查得到的召回，而日志上一个字都不会有。
     */
    static String descriptionFor(SessionVisibility visibility) {
        SessionVisibility effective = visibility == null ? SessionVisibility.DEFAULT : visibility;
        return DESCRIPTION_HEAD + (effective.crossSession() ? CROSS_SCOPE : SELF_SCOPE);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        // 单参入口没有 ToolContext，也就没有身份与会话。按"没身份就拒绝"处理，
        // 而不是退化成"查一个写死的 userId 或最近一段会话"——那正是本类要挡的那个失效。
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // 三件事都收在 scope.admit 里：有没有身份、有没有会话、这一段是不是他的。
        // 工具这里只负责把三种拒绝翻成三句不同的话——它们在模型与排查的人那里是三件事。
        Principal who = principalOf(toolContext);
        String sessionId = sessionIdOf(toolContext);
        switch (scope.admit(who, sessionId)) {
            case NO_PRINCIPAL -> {
                return "没有携带身份，这次不查历史：召回要用身份核对这段会话是不是你的，"
                        + "没有身份就分不出该查哪一段。";
            }
            case NO_SESSION -> {
                return "没有携带会话标识，这次不查历史：召回要从当前这一段出发，"
                        + "没有会话标识就不知道该找哪一段。";
            }
            case NOT_YOURS -> {
                return "这段会话不属于当前身份，这次不查历史（会话键里带着权限指纹，"
                        + "换人或换角色都是另一段）。";
            }
            case ADMITTED -> {
                // 继续往下查。
            }
        }
        Map<String, Object> input = parse(toolInput);
        try {
            return render(scope.events(sessionId, filterOf(input)), scope.visibility());
        }
        catch (IllegalArgumentException bad) {
            // 参数问题回给模型让它自己改，不要抛出去变成整轮 500：
            // 这里抛的是"query 必须有词""关键词超过 20 个""since 解析不了"，
            // 每一条模型都能靠换参数自救。
            return "召回参数不合法：" + bad.getMessage()
                    + "（query 必填，多个关键词用英文逗号分隔、最多 20 个；"
                    + "since 必须是 ISO-8601 时刻，例如 2026-10-01T00:00:00Z）";
        }
    }

    /**
     * 按入参拼过滤条件。范围（哪一段会话）不在这里，它由 {@code getEvents} 的第一个参数决定。
     *
     * <p><b>不设 {@code excludeArchived}</b>：它默认 false，也就是归档的照样查得到——
     * 压缩把早期事件归档或折成摘要之后，"被压掉的那句原话"是召回唯一的价值来源，
     * 在这里把它排除掉，这个工具就只剩下返回"没找到"。
     *
     * <p><b>设 {@code excludeSynthetic(true)}</b>：钉住的系统提示词与压缩生成的摘要
     * 都不是"我说过的话"，每轮都有一条，召回它们只是白占上下文。
     */
    private static EventFilter filterOf(Map<String, Object> input) {
        return EventFilter.builder()
                .keywords(keywordsOf(text(input, "query")))
                .matchMode(matchModeOf(text(input, "match_mode")))
                .from(instantOf(text(input, "since")))
                .page(pageOf(input))
                .pageSize(MAX_PAGE_SIZE)
                .excludeSynthetic(true)
                .build();
    }

    /**
     * 拆关键词。中英文逗号都认。
     *
     * <p>框架只按 {@code ","} 拆，中文输入法打出来的是全角逗号——那会让整句变成一个词，
     * 匹配到的东西等于零，而表现只是"没找到"，看不出是拆分的问题。
     * 拆完去重：重复词在 {@code all} 模式下没有影响，在 {@code any} 下只是白算一遍。
     */
    static List<String> keywordsOf(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 至少要有一个关键词");
        }
        List<String> keywords = new ArrayList<>();
        for (String part : query.split("[,，]")) {
            String keyword = part.strip();
            if (!keyword.isEmpty() && !keywords.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("query 至少要有一个关键词");
        }
        if (keywords.size() > MAX_QUERY_TERMS) {
            throw new IllegalArgumentException(
                    "关键词最多 " + MAX_QUERY_TERMS + " 个，收到 " + keywords.size() + " 个");
        }
        return keywords;
    }

    private static EventFilter.MatchMode matchModeOf(String mode) {
        if (mode == null || mode.isBlank()) {
            return EventFilter.MatchMode.ANY;
        }
        String normalized = mode.strip();
        if ("any".equalsIgnoreCase(normalized)) {
            return EventFilter.MatchMode.ANY;
        }
        if ("all".equalsIgnoreCase(normalized)) {
            return EventFilter.MatchMode.ALL;
        }
        throw new IllegalArgumentException("match_mode 只能是 any 或 all，收到的是 " + mode);
    }

    private static Instant instantOf(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since.strip());
        }
        catch (DateTimeParseException notATime) {
            throw new IllegalArgumentException(
                    "since 要是 ISO-8601 时刻，例如 2026-10-01T00:00:00Z，收到的是 " + since);
        }
    }

    private static Integer pageOf(Map<String, Object> input) {
        Object value = input.get("page");
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(value.toString().strip()));
        }
        catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("page 要是整数，收到的是 " + value);
        }
    }

    /**
     * 把命中的事件拼成给模型读的文本：一行一条，带时间与说话人。
     *
     * <p><b>跨会话时每行再带 {@code sessionId}</b>：范围放开之后，"这条是从哪一段来的"
     * 变成必需信息——不带的话模型会把两段对话的内容混成一段回答，而混出来的那句话
     * 没有任何一处可以对照。{@code self} 下不带：那段恒等于当前这一段，重复只是白花 token。
     *
     * <p><b>不再输出框架那份 JSON</b>：里面每一条都重复四个键名，文本行读起来更省事，
     * 模型不需要解析就能直接用。
     *
     * <p>正文为空的（纯工具调用、附件消息）跳过：它们在搜索结果里是一行 {@code text:null}，
     * 占位置但不提供任何信息，而"条数上限 5"会被这种行吃掉。
     */
    private static String render(List<SessionEvent> hits, SessionVisibility visibility) {
        boolean withOrigin = visibility.crossSession();
        StringBuilder out = new StringBuilder();
        for (SessionEvent event : hits) {
            Message message = event.getMessage();
            String text = message == null ? null : message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("- ");
            if (withOrigin) {
                out.append('[').append(event.getSessionId()).append("] ");
            }
            out.append(event.getTimestamp()).append(' ')
                    .append(labelOf(event)).append('：').append(text.strip());
        }
        if (out.isEmpty()) {
            return emptyAnswer(visibility);
        }
        return truncate(out.toString());
    }

    /**
     * "没找到"要按范围说，不能只有一句"没找到"。
     *
     * <p>{@code self} 下"没找到"是"这一段里没说过"；跨会话下是"整个部署都没说过"。
     * 两句话在模型那里都能变成"我没说过"，在排查时却是两件事——
     * 一句会让人去改关键词，另一句会让人去怀疑范围配错了。
     */
    private static String emptyAnswer(SessionVisibility visibility) {
        String where = visibility.crossSession()
                ? "全部会话里没有找到匹配的记录（范围：" + visibility.description() + "）"
                : "本段对话里没有找到匹配的记录（范围：" + visibility.description() + "）";
        return where + "。可以换一组更贴近原话的关键词"
                + "（多个词用英文逗号分隔），或者去掉 since 放宽时间范围。";
    }

    /** 说话人。用中文，因为它直接读给用户听。 */
    private static String labelOf(SessionEvent event) {
        MessageType type = event.getMessageType();
        if (type == null) {
            return "记录";
        }
        return switch (type) {
            case USER -> "用户";
            case ASSISTANT -> "助手";
            case SYSTEM -> "系统";
            case TOOL -> "工具";
        };
    }

    /**
     * 超长就裁，并告诉模型"还裁过"。
     *
     * <p>裁在字符上，可能把一句话切在半截。这里的结果是给模型读的文本，
     * 所以可读性优先于"结构完整"，说清"被截断了、怎么缩小范围"比补一个句号有用。
     */
    static String truncate(String result) {
        if (result == null || result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        return result.substring(0, MAX_RESULT_CHARS)
                + "\n…（结果过长已截断，只保留前 " + MAX_RESULT_CHARS + " 字。"
                + "把关键词写得更贴近原文，或者加 since 缩小时间范围）";
    }

    private static String sessionIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ChatMemory.CONVERSATION_ID);
        return value instanceof String sessionId ? sessionId : null;
    }

    private static Principal principalOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }

    private static Map<String, Object> parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            throw new IllegalArgumentException("recall_session 需要 {\"query\":\"..\"}");
        }
        try {
            return new JsonHelper().fromJsonToMap(toolInput);
        }
        catch (RuntimeException failure) {
            throw new IllegalArgumentException("recall_session 的参数不是 JSON 对象：" + toolInput, failure);
        }
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : value.toString();
    }

    /** 权限登记：动作是"回忆"，一张业务表都不碰。 */
    public static ToolPermission permission() {
        return ToolPermission.of(ACTION);
    }
}
