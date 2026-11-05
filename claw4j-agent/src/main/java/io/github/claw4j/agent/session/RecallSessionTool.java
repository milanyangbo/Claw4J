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
import org.springframework.ai.session.SessionService;
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
 * <p><b>它只查当前这一段对话，不是这个人的所有会话。</b>会话键按"人 + 权限 + 通道 + chatId"
 * 分段（{@link SessionKeys}），召回跟着同一个粒度走：网页私聊里查到的 128,400，
 * 不会被它在项目群里念出来。权限指纹挡不住那一层——它保证"权限一样才共享"，
 * 而这里听众不一样。这是本类唯一真正的安全边界，靠 {@code recallsOnlyThisConversation} 守着。
 *
 * <p><b>为什么不直接用框架的 {@code CrossSessionRecallTools}</b>：它按
 * {@code SessionService#findEventsByUserId} 查（{@code WHERE user_id = ?}），而
 * {@code EventFilter} 里<b>没有会话字段</b>——想按会话收窄，框架这一层根本没有入口。
 * 所以这里自己拼 {@link EventFilter} 走 {@code getEvents(sessionId, filter)}，
 * 查询条件本身就把范围钉死在一段会话上，不靠事后过滤（事后过滤会在"没命中"和
 * "命中但被滤掉"之间撒谎）。
 *
 * <p><b>会话 id 由宿主写进工具上下文，模型没有参数可改</b>：键是
 * {@link ChatMemory#CONVERSATION_ID}，值来自 {@code ChatClientAgentPort}（那里已经算好
 * {@code sessionId}）。模型手上没有"换一个会话查"的入参，于是"查别人的聊天记录"
 * 这条路径不存在，而不是"存在但被拒绝"。
 *
 * <p><b>身份仍然要查一次</b>：不是用来限定查询范围，而是核对"这一段会话是不是你的"——
 * {@code sessionId} 必须以当前身份的归属键开头。防的是宿主把 sessionId 传错、
 * 或者将来有人调这个工具时把别人的会话 id 递进来。核不上就拒，不退化成"照样查"。
 *
 * <p><b>关键词匹配，不是语义召回</b>：按词在事件正文里找，换个说法问同一件事它找不到。
 * 别在文档或提示词里写成"语义召回"——两者差的是 embedding 与一张向量表，那是票 05 的载体。
 *
 * <p><b>它现在的价值是"把被压缩掉的原文找回来"</b>：压缩会把早期事件归档或折成摘要
 * （{@code EventFilter} 的 {@code excludeArchived} 默认 false，归档的照样查得到），
 * 摘要留结论、丢细节；"上次那批单子到底怎么定的"这类细节只能靠关键词回查。
 *
 * <p>结果会截断再交给模型：{@code pageSize} 有上限，整段文本再按字符裁一次。
 * 五条长对话一口气塞进提示词，召回省下的上下文就白省了。
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
     * 描述里点明四件事：这是本段对话里更早的部分（不是别的会话）、按关键词匹配（要挑词）、
     * 只搜得到这一段、以及与"翻当前上下文"的区别。
     * 不写清的话，模型会去搜别人会话里的内容，然后拿"没找到"当成"我没说过"。
     */
    private static final String DESCRIPTION = "在这一段对话的历史里按关键词搜索。"
            + "用于回忆当前上下文里已经没有的内容（更早的、被压缩掉的细节，以前定过的口径）。"
            + "按关键词匹配，多个词用英文逗号分隔；换个说法问同一件事它找不到，那就换一组更贴近原文的词。"
            + "只搜得到这一段对话，看不到你在别的会话或群聊里说过的话。";

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

    private final SessionService sessionService;

    private final ToolDefinition definition;

    public RecallSessionTool(SessionService sessionService) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
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

    @Override
    public String call(String toolInput) {
        // 单参入口没有 ToolContext，也就没有身份与会话。按"没身份就拒绝"处理，
        // 而不是退化成"查一个写死的 userId 或最近一段会话"——那正是本类要挡的那个失效。
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Principal who = principalOf(toolContext);
        if (who == null || who.userId() == null || who.userId().isBlank()) {
            return "没有携带身份，这次不查历史：召回要用身份核对这段会话是不是你的，"
                    + "没有身份就分不出该查哪一段。";
        }
        String sessionId = sessionIdOf(toolContext);
        if (sessionId == null || sessionId.isBlank()) {
            return "没有携带会话标识，这次不查历史：召回只在本段对话里找，"
                    + "没有会话标识就不知道该找哪一段。";
        }
        if (!belongsTo(who, sessionId)) {
            return "这段会话不属于当前身份，这次不查历史（会话键里带着权限指纹，"
                    + "换人或换角色都是另一段）。";
        }
        Map<String, Object> input = parse(toolInput);
        try {
            return render(sessionService.getEvents(sessionId, filterOf(input)));
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
     * <p><b>不再输出框架那份 JSON</b>：里面每一条都重复 {@code sessionId} 与四个键名，
     * 而会话现在恒等于当前这一段——重复的字段只是白花 token。文本行读起来也更省事，
     * 模型不需要解析就能直接用。
     *
     * <p>正文为空的（纯工具调用、附件消息）跳过：它们在搜索结果里是一行 {@code text:null}，
     * 占位置但不提供任何信息，而"条数上限 5"会被这种行吃掉。
     */
    private static String render(List<SessionEvent> hits) {
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
            out.append("- ").append(event.getTimestamp()).append(' ')
                    .append(labelOf(event)).append('：').append(text.strip());
        }
        if (out.isEmpty()) {
            return "本段对话里没有找到匹配的记录。可以换一组更贴近原话的关键词"
                    + "（多个词用英文逗号分隔），或者去掉 since 放宽时间范围。";
        }
        return truncate(out.toString());
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

    /**
     * 这一段会话是不是这个身份的。
     *
     * <p>会话 id 的形状是 {@code claw4j:user:<owner>:<通道>:<chatId>:<段>}，而 {@code owner}
     * 里带着权限指纹（见 {@link SessionKeys#ownerOf}）。前缀对不上，说明要么换人了，
     * 要么换权限了，要么宿主把别人的 id 递了进来——三种都不该查。
     *
     * <p><b>前缀只到 {@code <owner>:} 为止</b>，所以后面接 {@code <通道>:<chatId>} 还是
     * 将来再加一段（ADR-0026 的段）都成立。反过来，把新维度拼在 owner 前面会让这条校验
     * 对别人成立——那是一条静默的越权读，而不是一次报错。
     */
    private static boolean belongsTo(Principal who, String sessionId) {
        String prefix = SessionKeys.PREFIX + SessionKeys.ownerOf(who) + SessionKeys.SESSION_SEPARATOR;
        return sessionId.startsWith(prefix);
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
