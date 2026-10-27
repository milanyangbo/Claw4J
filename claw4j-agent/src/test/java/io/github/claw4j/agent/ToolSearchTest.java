package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具循环：模型永远只先看见"找工具"那一个，查到谁再给谁。
 *
 * <p>断言的对象是**模型这一轮看到的工具清单**，不是"配置里有一个索引对象"。
 * 这件事的成本与价值都在那份清单上——清单没变短，检索就什么都没做；
 * 清单里少了本该有的工具，就是把能力藏丢了。
 *
 * <p>这里也就只剩这一条路：没有"工具少就全塞"的分支，所以第一条用例专门钉"只有一个工具时
 * 照样先检索"——那条分支一旦被加回来，它先红。
 */
class ToolSearchTest {

    @Test
    @DisplayName("只有一个工具也要先检索：模型看见的清单不随工具数量改变")
    void singleToolIsStillSearchedFirst() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (turn.getAndIncrement()) {
            case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"成交额\"}");
            case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
            default -> ChatPipelineTest.answer("查到了 128400");
        });

        String text = port(model, List.of(lookup), ToolSearchProperties.defaults())
                .reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().getFirst()))
                .as("第一轮只该看见检索工具：这就是渐进式披露，与工具总数无关")
                .containsExactly(ToolSearch.TOOL_NAME);
        assertThat(toolNames(model.prompts().get(1)))
                .as("检索回来的工具要进清单，检索工具自己也要留着（还可能要继续找）")
                .contains(ToolSearch.TOOL_NAME, "lookup")
                .hasSize(2);
        assertThat(lookup.calls()).isEqualTo(1);
        assertThat(text).isEqualTo("查到了 128400");
    }

    @Test
    @DisplayName("一堆工具里检索挑得出来：找到之后才进清单，并且真的被执行")
    void searchPicksTheRightToolOutOfMany() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        List<ToolCallback> tools = withNoise(lookup, 20);
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (turn.getAndIncrement()) {
            case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"成交额\"}");
            case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
            default -> ChatPipelineTest.answer("查到了 128400");
        });

        String text = port(model, tools, ToolSearchProperties.defaults())
                .reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().get(0)))
                .as("第一轮只该看见检索工具：这一份清单就是省下来的 token")
                .containsExactly(ToolSearch.TOOL_NAME);
        assertThat(toolNames(model.prompts().get(1)))
                .as("20 个占位工具一个都不该进来：检索按描述匹配，只有 lookup 对得上")
                .contains(ToolSearch.TOOL_NAME, "lookup")
                .hasSize(2);
        assertThat(lookup.calls()).isEqualTo(1);
        assertThat(text).isEqualTo("查到了 128400");
    }

    @Test
    @DisplayName("检索出来的工具不等于能调：没登记权限的照样在调用前被拦下")
    void searchIsNotAuthorization() {
        RecordingTool secret = new RecordingTool("secretTool", "机密数据", "查询机密档案");
        List<ToolCallback> tools = withNoise(secret, 20);
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> turn.getAndIncrement() == 0
                ? ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"机密\"}")
                : ChatPipelineTest.requestTool("secretTool", "{}"));

        String text = new ChatClientAgentPort(client(model, tools,
                // 只登记检索工具自己。secretTool 谁也调不动——它能不能调由登记表决定，
                // 跟"检索得到"没有关系。
                ToolPermissionRegistry.of(Map.of(ToolSearch.TOOL_NAME, ToolPermission.read())),
                ToolSearchProperties.defaults())).reply(message("机密档案")).text();

        assertThat(secret.calls()).as("越权的工具一次都不能被执行").isZero();
        assertThat(text).contains(AuthorizationAdvisor.DENIAL_PREFIX);
    }

    /**
     * 会话 id 不在上下文里时，这条路没有"退化成全塞"的兜底——它直接抛。
     *
     * <p>这条钉的是 {@code ChatClientAgentPort} 里那个 {@code chat_memory_conversation_id} 参数：
     * 有人以为它只服务会话记忆、删掉也没事，而实际后果是每一次对话都失败。
     */
    @Test
    @DisplayName("上下文里没有会话 id 时直接报错，不会静默退化成全塞")
    void missingSessionIdFailsLoudly() {
        ChatClient client = ChatClient.builder(new FixedChatModel("好的"),
                        ObservationRegistry.NOOP, null, null,
                        ToolSearch.toolLoop(ToolSearchProperties.defaults(), new RegexToolIndex(),
                                ObservationRegistry.NOOP))
                .defaultToolCallbacks(new RecordingTool("lookup", "128400", "查询销售成交额"))
                .build();

        // 注意断言写在 content() 上：call() 只是拿到一个待执行的 spec，不触发请求。
        assertThatThrownBy(() -> client.prompt("查成交额").call().content())
                .hasMessageContaining("chat_memory_conversation_id");
    }

    /**
     * 一次检索返回几个候选，夹在框架约定的 1–10 之间。
     *
     * <p>这条守的是"配错一个数会静默变成另一件事"：0 或负数会让检索空手而归，
     * 大于 10 则被框架自己截断，两种都不该让调用方以为配置生效了。
     */
    @Test
    @DisplayName("max-results 越界夹到 1–10，键缺失落回 5")
    void maxResultsIsClamped() {
        assertThat(ToolSearchProperties.defaults().maxResults())
                .as("没配时是 5")
                .isEqualTo(ToolSearchProperties.DEFAULT_MAX_RESULTS);
        assertThat(new ToolSearchProperties(3).maxResults()).isEqualTo(3);
        assertThat(new ToolSearchProperties(99).maxResults())
                .as("框架写的是 1–10，超出的部分由它兜底，这里提前夹住")
                .isEqualTo(10);

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class).maxResults())
                        .as("没写这个键 = 用默认值")
                        .isEqualTo(ToolSearchProperties.DEFAULT_MAX_RESULTS));

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.tool-search.max-results=3")
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class).maxResults())
                        .isEqualTo(3));
    }

    /**
     * 流式路径走的是另一对钩子（{@code doInitializeLoopStream} / {@code doBeforeStream}）。
     *
     * <p>生产上真正的入口是 SSE：只给检索工具、追加那段提示词这两件事都发生在流式钩子上，
     * 同步路径绿不能推出它绿。这里只钉两件事——第一轮的工具清单、提示词确实被追加了。
     */
    @Test
    @DisplayName("流式路径同样先检索：SSE 上模型看到的也不是全部工具")
    void streamPathAlsoUsesProgressiveDisclosure() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        FixedChatModel model = new FixedChatModel(
                prompt -> ChatPipelineTest.answer("同步路径不该被走到"),
                prompt -> Flux.just(ChatPipelineTest.answer("查到了 128400")));

        port(model, List.of(lookup), ToolSearchProperties.defaults())
                .stream(message("上周华东成交额"), chunk -> {
                });

        Prompt first = model.prompts().getFirst();
        assertThat(toolNames(first))
                .as("流式第一轮同样只看得见检索工具")
                .containsExactly(ToolSearch.TOOL_NAME);
        assertThat(textsOf(first))
                .as("检索提示词也要追加到流式的系统消息上，否则模型会说「我没有工具」")
                .contains("空格分隔");
    }

    @Test
    @DisplayName("中文检索查询要空格分词：整句会被当成一个词，什么都匹配不到")
    void chineseQueryNeedsSpaces() {
        RegexToolIndex index = new RegexToolIndex();
        index.indexTool("chat-1", ToolReference.builder()
                .toolName("recall_session")
                .summary("按关键词检索历史对话：回忆、说过什么、以前的结论")
                .build());

        assertThat(foundTools(index, "回忆 历史 会话"))
                .as("空格分隔的关键词能命中")
                .containsExactly("recall_session");
        assertThat(foundTools(index, "回忆以前说过的话"))
                .as("整句里没有任何一个 token 与描述相等，一个都匹配不到——"
                        + "这就是系统提示词必须要求空格分词的原因，不是措辞偏好")
                .isEmpty();
        assertThat(ToolSearch.SYSTEM_SUFFIX)
                .as("提示词里不写清楚，中文场景下模型会照着「一句话描述」去写，然后每次都检索不到")
                .contains("空格");
    }

    private static List<String> foundTools(RegexToolIndex index, String query) {
        return index.search(new ToolSearchRequest("chat-1", query, 5, null))
                .toolReferences()
                .stream()
                .map(ToolReference::toolName)
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ToolSearchProperties.class)
    static class BindingOnly {
    }

    private static ChatClientAgentPort port(FixedChatModel model, List<ToolCallback> tools,
                                            ToolSearchProperties properties) {
        return new ChatClientAgentPort(client(model, tools, readsEverything(tools), properties));
    }

    private static ChatClient client(FixedChatModel model, List<ToolCallback> tools,
                                     ToolPermissionRegistry registry, ToolSearchProperties properties) {
        ToolIndex index = new RegexToolIndex();
        List<ToolPermissionRegistry> registries = List.of(registry,
                // 与装配里那个 bean 同一条：检索工具自己按 read 放行
                ToolPermissionRegistry.of(Map.of(ToolSearch.TOOL_NAME, ToolPermission.read())));
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolSearch.toolLoop(properties, index, ObservationRegistry.NOOP))
                .defaultAdvisors(new AuthorizationAdvisor(registries))
                .defaultToolCallbacks(tools)
                .build();
    }

    /** 这一轮模型看到的工具清单。检索的全部价值都在它身上。 */
    private static List<String> toolNames(Prompt prompt) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        return options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static String textsOf(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static InboundMessage message(String text) {
        return new InboundMessage(Principal.readOnly("u-1"), "chat-1", text);
    }

    /** 所有工具都能调，用来把"检索"与"鉴权"分开验。 */
    private static ToolPermissionRegistry readsEverything(List<ToolCallback> tools) {
        Map<String, ToolPermission> permissions = tools.stream()
                .collect(java.util.stream.Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        callback -> ToolPermission.read("sales_order")));
        return ToolPermissionRegistry.of(permissions);
    }

    /** 占位工具：名字与描述都不含业务词，检索它们时不该命中。 */
    private static List<ToolCallback> noisyTools(int count) {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String name = String.format("tool%02d", i);
            tools.add(new RecordingTool(name, "ok", "占位能力 " + name));
        }
        return List.copyOf(tools);
    }

    /** 一个真工具 + 一堆占位工具，凑到"工具很多"的样子。 */
    private static List<ToolCallback> withNoise(RecordingTool real, int noise) {
        List<ToolCallback> tools = new ArrayList<>(List.of(real));
        tools.addAll(noisyTools(noise));
        return List.copyOf(tools);
    }
}
