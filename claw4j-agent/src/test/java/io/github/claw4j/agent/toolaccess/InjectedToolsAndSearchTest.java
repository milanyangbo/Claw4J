package io.github.claw4j.agent.toolaccess;

import io.github.claw4j.agent.turn.ToolCallBudget;
import io.github.claw4j.domain.ContextKeys;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票 32：披露层不再吃掉"上游注入的工具"；检索结果带描述与参数签名。
 *
 * <p>放在 {@code toolaccess} 包里，是因为要直接调 {@code doInitializeLoop} / {@code doBeforeCall}
 * ——它们是 {@code protected}，声明在本包里才拿得到。走 {@code adviseCall} 要搭一条链，
 * 而这两个方法根本不碰链，搭了只是噪音。
 */
class InjectedToolsAndSearchTest {

    private static final int NOISE = 20;

    @Test
    @DisplayName("上游注入的工具不会被整表替换抹掉：它不在 pinned、不是检索回来的，但每轮都在")
    void injectedToolsSurviveTheRebuild() {
        ChatClientRequest prepared = prepare(memoryTools(), List.of("MemoryView", "MemoryCreate"));

        assertThat(names(prepared))
                .as("注入的两个要留着；没登记的噪声工具不该混进来")
                .contains("MemoryView", "MemoryCreate")
                .doesNotContain("tool01", "tool02");
    }

    @Test
    @DisplayName("保住的是登记的那几个名字，不是写死的工具名")
    void registrationIsNotAHardcodedName() {
        ChatClientRequest prepared = prepare(memoryTools(), List.of("tool07"));

        assertThat(names(prepared))
                .as("换成任何一个真实存在、又没被 pinned 的工具名，都该被保住")
                .contains("tool07")
                .doesNotContain("MemoryView");
    }

    @Test
    @DisplayName("检索回来的是对象形状时，下一轮照样把工具放回清单")
    void objectShapedHitsStillUpdateTheNextRound() {
        String hit = "[{\"name\":\"tool07\",\"description\":\"查询成交额\",\"input\":\"(sql: string)\"}]";
        ChatClientRequest prepared = prepareWithHistory(memoryTools(), hit);

        assertThat(names(prepared))
                .as("解析不出来就等于检索白做：模型搜到了却调不动")
                .contains("tool07");
    }

    @Test
    @DisplayName("检索命中带一句话描述和参数签名：模型不必再猜要传什么")
    void hitsCarryDescriptionAndSignature() {
        List<ToolCallback> tools = memoryTools();
        Map<String, ToolCallback> byName = tools.stream()
                .collect(Collectors.toMap(t -> t.getToolDefinition().name(), Function.identity()));
        ToolContext context = new ToolContext(Map.of(
                DescriptiveToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY, "s-32",
                DescriptiveToolSearchTool.TOOL_CALLBACKS_KEY, byName));
        String description = byName.get("MemoryView").getToolDefinition().description();

        List<DescriptiveToolSearchTool.ToolHit> hits =
                new DescriptiveToolSearchTool(stubIndex("MemoryView", description), 5)
                        .toolSearchTool("读记忆", 5, null, context);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().name()).isEqualTo("MemoryView");
        assertThat(hits.getFirst().description())
                .as("描述要压成一行：工具原文是多段带 Usage 的长文本")
                .doesNotContain("\n")
                .startsWith("View the contents");
        assertThat(hits.getFirst().input())
                .as("必填参数不带问号，可选参数带")
                .isEqualTo("(path: string, viewRange?: string)");
    }

    // ------------------------------------------------------------ 夹具

    /**
     * 跑一遍"开一轮 + 出一轮清单"。
     *
     * @param tools    这一轮的全部工具
     * @param injected 上游登记注入的工具名
     * @return 这一轮准备发给模型的请求
     */
    private static ChatClientRequest prepare(List<ToolCallback> tools, List<String> injected) {
        return prepare(request(tools, injected, null));
    }

    private static ChatClientRequest prepareWithHistory(List<ToolCallback> tools, String searchResponse) {
        return prepare(request(tools, List.of(), searchResponse));
    }

    private static ChatClientRequest prepare(ChatClientRequest request) {
        DisclosureToolCallingAdvisor advisor = ToolSearch.toolLoop(
                properties(), new LuceneToolIndex(ToolSearchProperties.Lucene.DEFAULT_MIN_SCORE),
                ObservationRegistry.NOOP, ToolCallBudget.MAX_TOTAL_CALLS).build();
        // 这两个方法不碰链：全塞与否、清单怎么拼都在它们自己里面。
        return advisor.doBeforeCall(advisor.doInitializeLoop(request, null), null);
    }

    private static ChatClientRequest request(List<ToolCallback> tools, List<String> injected,
                                             String searchResponse) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .build();
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你是助手"));
        messages.add(new UserMessage("上周华东成交额"));
        if (searchResponse != null) {
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-1", ToolSearch.TOOL_NAME, searchResponse)))
                    .build());
        }
        Map<String, Object> context = new HashMap<>();
        context.put(ChatMemory.CONVERSATION_ID, "s-32");
        context.put(ContextKeys.INJECTED_TOOLS, injected);
        return new ChatClientRequest(new Prompt(messages, options), context);
    }

    private static ToolSearchProperties properties() {
        return new ToolSearchProperties(null, 5, null, ToolSearchProperties.Mode.ALWAYS, 0,
                List.of(), Map.of(), null, null, null);
    }

    private static List<String> names(ChatClientRequest request) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) request.prompt().getOptions();
        return options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /** 一个记忆工具（描述故意写成多段带 Usage 的长文本）+ 一堆噪声，凑到"工具很多"。 */
    private static List<ToolCallback> memoryTools() {
        List<ToolCallback> tools = new ArrayList<>(List.of(
                MethodToolCallbackProvider.builder().toolObjects(new MemoryTools()).build()
                        .getToolCallbacks()));
        for (int i = 1; i <= NOISE; i++) {
            tools.add(single("tool%02d".formatted(i), "占位能力 " + i));
        }
        return List.copyOf(tools);
    }

    /** 只有名字与描述的工具：签名那块由 {@link #memoryTools()} 提供真实 schema。 */
    private static ToolCallback single(String name, String description) {
        return new StubCallback(name, description,
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}");
    }

    /**
     * 一个只会返回固定命中的索引。
     *
     * <p>描述从 {@code summary} 来——真实实现里索引存的也是工具的 description，
     * 所以这里必须给真描述，否则验的是"空描述不炸"而不是"描述压成一行"。
     */
    private static ToolIndex stubIndex(String name, String summary) {
        List<ToolReference> references = List.of(
                ToolReference.builder().toolName(name).summary(summary).build());
        return new ToolIndex() {
            @Override
            public void indexTool(String sessionId, ToolReference toolReference) {
            }

            @Override
            public ToolSearchResponse search(ToolSearchRequest request) {
                return ToolSearchResponse.builder().toolReferences(references).build();
            }

            @Override
            public void clearIndex(String sessionId) {
            }
        };
    }

    /** 描述从回调本身取：stub 里给的是 null，得让 {@code summary()} 拿到真描述。 */
    private record StubCallback(String name, String description, String schema) implements ToolCallback {

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }
    }

    /** 真工具：描述多段、有必填与可选参数——签名与截断都靠它验。 */
    static class MemoryTools {

        @Tool(name = "MemoryView", description = """
                View the contents of a file or list the contents of a directory in the persistent
                memory store.

                Usage:
                - If path points to a directory: lists it two levels deep showing file sizes.
                """)
        String view(@ToolParam(description = "Path to view") String path,
                    @ToolParam(description = "Optional line range", required = false) String viewRange) {
            return "ok";
        }

        @Tool(name = "MemoryCreate", description = "Create a new memory file.")
        String create(@ToolParam(description = "Path for the new file") String path,
                      @ToolParam(description = "Full file content") String fileText) {
            return "ok";
        }
    }
}
