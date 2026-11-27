package io.github.claw4j.agent.toolaccess;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.util.JsonHelper;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索工具：把命中项连同**一句话描述与参数签名**一起交回给模型（票 32）。
 *
 * <p>为什么不用框架的 {@code ToolSearchTool}：它最后一行是 {@code map(tr -> tr.toolName())}，
 * 描述被主动丢掉，模型拿到的是一串光秃秃的名字——像 {@code WebFetch} / {@code Http} / {@code Fetch}
 * 这种同族名字，它分不出该用哪个，只能再搜一遍或者猜。OpenClaw 的 {@code tools.search} 每个命中项
 * 都带一个<b>有界的 input 签名</b>（{@code { id: string; mode?: "drip" | "flood" }}），
 * 签名够用时模型可以跳过 {@code describe}（{@code docs.openclaw.ai/zh-CN/tools/tool-search}）。
 *
 * <p><b>收益边界要说清</b>：带签名省不掉"下一轮"——能不能调用取决于那一轮开头就定好的
 * {@code toolCallbacks} 清单，清单要等下一轮 {@code prepare()} 才更新。它省的是另外两件：
 * 盲搜第二遍（看到描述就知道方向错了），以及参数写错后的第三次调用（签名已经摆在这里）。
 *
 * <p><b>为什么不把签名写进索引</b>：索引里只放名字与描述。签名塞进描述会让向量检索的文本被
 * 参数名稀释——那是拿检索质量换格式。签名是"检出之后"才需要的信息，所以在这里按名字现取。
 */
public class DescriptiveToolSearchTool {

    /**
     * 这一轮的工具回调，放在工具上下文里的键名。
     *
     * <p>签名只能从完整的 {@code ToolDefinition} 里取，而索引里只有名字与描述。回调由
     * {@code DisclosureToolCallingAdvisor} 每轮放进工具上下文——它本来就是这一轮清单的来源，
     * 多放一份不需要额外的查找。
     */
    public static final String TOOL_CALLBACKS_KEY =
            DescriptiveToolSearchTool.class.getName() + ".callbacks";

    /** 与 {@link org.springframework.ai.tool.toolsearch.ToolSearchTool} 同一个键：框架的执行器按它取会话。 */
    public static final String TOOL_SEARCH_TOOL_SESSION_ID_KEY = "toolSearchToolSessionId";

    /** 一条描述最多给多少字符。够看清方向，又不至于把五条命中撑成一篇说明书。 */
    private static final int MAX_DESCRIPTION_CHARS = 120;

    /** 一条签名最多给多少字符。超出就按完整参数截断，不留半个参数。 */
    private static final int MAX_SIGNATURE_CHARS = 90;

    private static final JsonHelper JSON = new JsonHelper();

    private final ToolIndex toolIndex;

    @Nullable
    private final Integer advisorMaxResults;

    /**
     * @param toolIndex         工具索引，必填
     * @param advisorMaxResults 模型没传 {@code maxResults} 时的缺省值
     */
    public DescriptiveToolSearchTool(ToolIndex toolIndex, @Nullable Integer advisorMaxResults) {
        this.toolIndex = Objects.requireNonNull(toolIndex, "toolIndex");
        this.advisorMaxResults = advisorMaxResults;
    }

    /**
     * 一次命中。
     *
     * @param name        工具名，模型用它发起调用
     * @param description 一句话描述（已截断）
     * @param input       参数签名，例如 {@code (path: string, lines?: string)}；取不到时为空串
     */
    public record ToolHit(String name, String description, String input) {
    }

    @Tool(name = "toolSearchTool", description = """
            Search the tool registry for capabilities this task needs.
            Use it when what you need is not among the tools you can see right now.
            Each hit carries the tool name, a one-line description, and a compact argument
            signature such as (path: string, lines?: string) — enough to judge whether it is
            the right tool and what to pass. The full schema is loaded on the next step, so
            call the tool by name after searching; do not search again for the same thing.
            """)
    public List<ToolHit> toolSearchTool(
            @ToolParam(description = "A natural language description of the capability you need. "
                    + "Be specific and include relevant keywords.") String query,
            @ToolParam(description = "Maximum number of hits to return (1-10). Default is 5.",
                    required = false) Integer maxResults,
            @ToolParam(description = "Optional filter to narrow the search to one tool category.",
                    required = false) String categoryFilter,
            ToolContext toolContext) {

        int limit = maxResults != null ? maxResults : this.advisorMaxResults;
        String sessionId = String.valueOf(Objects.requireNonNull(
                toolContext.getContext().get(TOOL_SEARCH_TOOL_SESSION_ID_KEY)));
        Map<String, ToolCallback> callbacks = callbacksOf(toolContext);

        ToolSearchResponse response = this.toolIndex.search(
                new ToolSearchRequest(sessionId, query, limit, categoryFilter));
        return response.toolReferences().stream()
                .map(reference -> new ToolHit(
                        reference.toolName(),
                        descriptionOf(reference),
                        signatureOf(reference.toolName(), callbacks)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ToolCallback> callbacksOf(ToolContext toolContext) {
        Object value = toolContext.getContext().get(TOOL_CALLBACKS_KEY);
        return value instanceof Map<?, ?> map ? (Map<String, ToolCallback>) map : Map.of();
    }

    /**
     * 描述压成一行：取第一段，超长就截。
     *
     * <p>工具的 description 常常是好几段带 "Usage:" 的长文本（{@code MemoryView} 那份五百多字）。
     * 五条命中各带五百字，等于把索引又塞回提示词里，披露层省下的东西全还回去了。
     */
    private static String descriptionOf(ToolReference reference) {
        String summary = reference.summary() == null ? "" : reference.summary().strip();
        int end = summary.indexOf('\n');
        String firstLine = (end < 0 ? summary : summary.substring(0, end)).strip();
        if (firstLine.isEmpty()) {
            // 有些工具的 description 第一行是空的（三引号开头就换行），退一步取第一个非空行。
            for (String line : summary.split("\n")) {
                if (!line.isBlank()) {
                    firstLine = line.strip();
                    break;
                }
            }
        }
        if (firstLine.length() <= MAX_DESCRIPTION_CHARS) {
            return firstLine;
        }
        return firstLine.substring(0, MAX_DESCRIPTION_CHARS - 1) + "…";
    }

    /**
     * 把 input schema 压成一行签名。
     *
     * <p>只保留"调用方要传什么"：参数名、是否必填、类型。不展开嵌套结构——那正是完整 schema 的活，
     * 下一轮就会给。取不到定义时返回空串，而不是编一个：模型看到空串会去调工具，看到假签名会传错参数。
     *
     * <p>解析成 {@code Map} 而不是 JSON 树：{@code JsonHelper} 走的是框架那一版 Jackson，
     * 树类型的抽象节点在它那边反序列化会失败（实测报 "Cannot construct instance of JsonNode"）。
     * 普通 Map 是任何一版都能读的形状。
     */
    private static String signatureOf(String toolName, Map<String, ToolCallback> callbacks) {
        ToolCallback callback = callbacks.get(toolName);
        if (callback == null) {
            return "";
        }
        String schema = callback.getToolDefinition().inputSchema();
        if (schema == null || schema.isBlank()) {
            return "()";
        }
        try {
            Map<String, Object> root = JSON.fromJson(schema,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (root == null || !(root.get("properties") instanceof Map<?, ?> properties)
                    || properties.isEmpty()) {
                return "()";
            }
            Set<String> required = new LinkedHashSet<>();
            if (root.get("required") instanceof Collection<?> names) {
                names.forEach(name -> required.add(String.valueOf(name)));
            }

            StringBuilder signature = new StringBuilder("(");
            boolean first = true;
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String piece = name
                        + (required.contains(name) ? "" : "?")
                        + ": " + typeOf(entry.getValue());
                if (signature.length() + piece.length() + 2 > MAX_SIGNATURE_CHARS) {
                    return signature.append("…)").toString();
                }
                signature.append(first ? "" : ", ").append(piece);
                first = false;
            }
            return signature.append(')').toString();
        } catch (RuntimeException e) {
            // schema 不是我们发的（MCP 服务端给的），解析失败就不给签名，调用照旧。
            return "";
        }
    }

    private static String typeOf(Object raw) {
        if (!(raw instanceof Map<?, ?> property)) {
            return "object";
        }
        Object type = property.get("type");
        if (type instanceof Collection<?> union) {
            StringBuilder joined = new StringBuilder();
            union.forEach(node -> joined.append(joined.isEmpty() ? "" : "|")
                    .append(shortType(String.valueOf(node))));
            return joined.isEmpty() ? "object" : joined.toString();
        }
        if (type != null) {
            return shortType(String.valueOf(type));
        }
        if (property.get("anyOf") instanceof Collection<?> anyOf && !anyOf.isEmpty()) {
            return typeOf(anyOf.iterator().next());
        }
        return "object";
    }

    private static String shortType(String type) {
        return switch (type) {
            case "integer" -> "int";
            case "number" -> "number";
            case "boolean" -> "bool";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }
}
