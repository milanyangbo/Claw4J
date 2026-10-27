package io.github.claw4j.agent.session;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.tool.CrossSessionRecallTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

import java.util.Map;
import java.util.Objects;

/**
 * 把"回忆这个人以前说过什么"接成模型手上的一个工具：{@code recall_session}（M3-03）。
 *
 * <p><b>这里是关键词匹配，不是语义召回</b>：{@code CrossSessionRecallTools} 是按词在
 * {@code SessionEvent} 的正文里找，换个说法问同一件事它找不到。别在文档或提示词里写成
 * "语义召回"——两者差的是 embedding 与一张向量表，那才是票 05 的载体。
 *
 * <p><b>userId 按每一次调用现取，绝不绑在实例上</b>。这个工具是单例 bean：
 * 把 {@code CrossSessionRecallTools.builder(service, userId)} 的产物缓存成字段，
 * 后来所有人生成的上下文里装的都是<b>第一个人</b>的历史，而它能正常返回结果、
 * 不报错、不告警。所以下面的 {@link #recallToolsFor} 每次调用都重造一个实例，
 * userId 来自这一轮请求的 {@link Principal}。
 *
 * <p><b>为什么召回的是"另一个会话"，现在却只有一个会话</b>：会话 id 由
 * {@link SessionKeys#sessionIdOf} 从 userId 派生，一个人目前只有一段会话。
 * 那这个工具此刻的真实用途是——<b>把被压缩掉的原文找回来</b>。
 * 压缩把早期事件归档或折成摘要（{@code EventFilter} 的 {@code excludeArchived}
 * 默认 false，归档的照样查得到），摘要留着结论、丢了细节；
 * "上次那批单子到底怎么定的"这类细节只能靠关键词回查。
 * 等多话题并行（{@code SessionKeys} 里记的那笔账）落地，它才真的是跨会话。
 *
 * <p>结果会截断再交给模型：{@code pageSize} 有上限，整段文本再按字符裁一次。
 * 十条长对话一口气塞进提示词，召回省下的上下文就白省了。
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
     * 描述里点明三件事：这是别的会话/更早的历史（不是当前这轮）、按关键词匹配（要挑词）、
     * 以及与 {@code conversation_search} 那种"翻当前对话"的区别。
     * 不写清的话，模型会在"刚才说过的那句"上也调它一遍，白花一次往返。
     */
    private static final String DESCRIPTION = "在自己的历史对话里按关键词搜索。"
            + "用于回忆当前上下文里已经没有的内容（更早的对话、被压缩掉的细节、"
            + "以前定过的口径）。按关键词匹配，多个词用英文逗号分隔；"
            + "换个说法问同一件事它找不到，那就换一组更贴近原文的词。"
            + "只搜得到自己的历史。";

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

    /** 框架在没命中时的原话。翻译一下的理由见 {@link #translateNoResults}。 */
    private static final String NO_RESULTS = "No results found.";

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
        // 单参入口没有 ToolContext，也就没有身份。按"没身份就拒绝"处理，
        // 而不是退化成"查一个写死的 userId"——那正是本票要挡的那个失效。
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Principal who = principalOf(toolContext);
        if (who == null || who.userId() == null || who.userId().isBlank()) {
            return "没有携带身份，这次不查历史：召回是按 userId 查的，"
                    + "没有身份只能查到别人的或者什么都查不到。";
        }
        Map<String, Object> input = parse(toolInput);
        String query = text(input, "query");
        try {
            // 每次调用现造：userId 绑在实例上，缓存成字段就是"所有人召回同一个人的历史"。
            String result = recallToolsFor(who).crossSessionSearch(
                    null,
                    query,
                    text(input, "match_mode"),
                    text(input, "since"),
                    integer(input, "page"));
            return translateNoResults(truncate(result));
        }
        catch (IllegalArgumentException bad) {
            // 参数问题回给模型让它自己改，不要抛出去变成整轮 500：
            // 框架在这里抛的是"query 必须有词""关键词超过 20 个""since 解析不了"，
            // 每一条模型都能靠换参数自救。
            return "召回参数不合法：" + bad.getMessage()
                    + "（query 必填，多个关键词用英文逗号分隔、最多 20 个；"
                    + "since 必须是 ISO-8601 时刻，例如 2026-10-01T00:00:00Z）";
        }
    }

    /**
     * 按这一次请求的身份造一个召回器。
     *
     * <p>包内可见是为了让测试能直接钉住"换一个身份就换一个 userId"这条——
     * 它唯一的失效方式是静默的（谁都能召回，只是召回到的是别人的）。
     */
    CrossSessionRecallTools recallToolsFor(Principal who) {
        return CrossSessionRecallTools.builder(sessionService, who.userId())
                .pageSize(MAX_PAGE_SIZE)
                .build();
    }

    /**
     * 超长就裁，并告诉模型"还裁过"。
     *
     * <p>裁在字符上，可能把一条 JSON 切在半截。这里的结果是给模型读的文本，
     * 不是给代码解析的结构——所以可读性优先于"仍是合法 JSON"，
     * 说清"被截断了、怎么缩小范围"比补一个右括号有用。
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
     * 没命中时框架说的是 {@code No results found.}。
     *
     * <p>原样交出去，模型下一句很可能就用英文接着答——用户读到的是一句英文的"没找到"。
     * 顺手把"怎么换个查法"也说了，它下一次调用才有方向。
     */
    private static String translateNoResults(String result) {
        if (!NO_RESULTS.equals(result == null ? null : result.trim())) {
            return result;
        }
        return "没有找到匹配的历史记录。可以换一组更贴近原话的关键词（多个词用英文逗号分隔），"
                + "或者去掉 since 放宽时间范围。";
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

    private static Integer integer(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        }
        catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(key + " 要是整数，收到的是 " + value);
        }
    }

    /** 权限登记：动作是"回忆"，一张业务表都不碰。 */
    public static ToolPermission permission() {
        return ToolPermission.of(ACTION);
    }
}
