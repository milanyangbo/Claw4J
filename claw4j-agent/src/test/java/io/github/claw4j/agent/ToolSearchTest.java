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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具检索：工具多到塞不进提示词时，先给模型一个"找工具"的工具。
 *
 * <p>断言的对象是**模型这一轮看到的工具清单**，不是"配置里有一个索引对象"。
 * 这件事的成本就在那份清单上——清单没变短，检索就什么都没做。
 */
class ToolSearchTest {

    @Test
    @DisplayName("工具没超过阈值时全塞：不为了省 token 多换一次模型往返")
    void belowThresholdStuffsEveryTool() {
        List<ToolCallback> tools = noisyTools(3);
        FixedChatModel model = new FixedChatModel("好的");

        port(model, tools, new ToolSearchProperties(20, 5)).reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().getFirst()))
                .as("阈值以内应该一个不少地列出来，也不该凭空多一个检索工具")
                .hasSize(3)
                .doesNotContain(ToolSearch.TOOL_NAME);
    }

    @Test
    @DisplayName("超过阈值先只给「找工具」：检索到之后那个工具才进清单，并且真的被执行")
    void aboveThresholdSearchesThenCallsTheFoundTool() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        List<ToolCallback> tools = withNoise(lookup, 20);
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (turn.getAndIncrement()) {
            case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"成交额\"}");
            case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
            default -> ChatPipelineTest.answer("查到了 128400");
        });

        String text = port(model, tools, new ToolSearchProperties(20, 5))
                .reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().get(0)))
                .as("第一轮只该看见检索工具：这一份清单就是省下来的 token")
                .containsExactly(ToolSearch.TOOL_NAME);
        assertThat(toolNames(model.prompts().get(1)))
                .as("检索回来的工具要进清单，检索工具自己也要留着（还可能要继续找）")
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
                new ToolSearchProperties(20, 5))).reply(message("机密档案")).text();

        assertThat(secret.calls()).as("越权的工具一次都不能被执行").isZero();
        assertThat(text).contains(AuthorizationAdvisor.DENIAL_PREFIX);
    }

    @Test
    @DisplayName("阈值 0 表示永远走检索：只剩一个工具也先让模型找一次")
    void zeroThresholdAlwaysSearches() {
        RecordingTool lookup = new RecordingTool("lookup", "128400", "查询销售成交额");
        AtomicInteger turn = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> switch (turn.getAndIncrement()) {
            case 0 -> ChatPipelineTest.requestTool(ToolSearch.TOOL_NAME, "{\"query\":\"成交额\"}");
            case 1 -> ChatPipelineTest.requestTool("lookup", "{}");
            default -> ChatPipelineTest.answer("查到了 128400");
        });

        port(model, List.of(lookup), new ToolSearchProperties(0, 5)).reply(message("上周华东成交额")).text();

        assertThat(toolNames(model.prompts().getFirst())).containsExactly(ToolSearch.TOOL_NAME);
        assertThat(lookup.calls()).isEqualTo(1);
    }

    /**
     * 会话 id 不在上下文里时，检索这条路没有"退化成全塞"的兜底——它直接抛。
     *
     * <p>这条钉的是 {@code ChatClientAgentPort} 里那个 {@code chat_memory_conversation_id} 参数：
     * 有人以为它只服务会话记忆、删掉也没事，而实际后果是"工具超过阈值之后每一次对话都失败"。
     */
    @Test
    @DisplayName("上下文里没有会话 id 时直接报错，不会静默退化成全塞")
    void missingSessionIdFailsLoudly() {
        ChatClient client = ChatClient.builder(new FixedChatModel("好的"),
                        ObservationRegistry.NOOP, null, null,
                        ToolSearch.toolLoop(new ToolSearchProperties(0, 5), 1, new RegexToolIndex(),
                                ObservationRegistry.NOOP))
                .defaultToolCallbacks(new RecordingTool("lookup", "128400", "查询销售成交额"))
                .build();

        // 注意断言写在 content() 上：call() 只是拿到一个待执行的 spec，不触发请求。
        assertThatThrownBy(() -> client.prompt("查成交额").call().content())
                .hasMessageContaining("chat_memory_conversation_id");
    }

    /**
     * 阈值没配的时候必须落回 20，而不是 0。
     *
     * <p>这条守的是配置绑定的一个坑：键缺失时原始类型会被填成 0，而 0 在我们这里的意思是
     * "永远走检索"。字段用 {@code Integer} 才分得开这两件事。
     */
    @Test
    @DisplayName("配置绑定：键缺失落回 20，写 0 才是「永远检索」")
    void missingThresholdIsNotZero() {
        assertThat(ToolSearchProperties.defaults().maxToolsInPrompt())
                .isEqualTo(ToolSearchProperties.DEFAULT_MAX_TOOLS_IN_PROMPT);

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class).maxToolsInPrompt())
                        .as("没写这个键 = 用默认值，不是 0")
                        .isEqualTo(ToolSearchProperties.DEFAULT_MAX_TOOLS_IN_PROMPT));

        new ApplicationContextRunner()
                .withUserConfiguration(BindingOnly.class)
                .withPropertyValues("claw4j.tool-search.max-tools-in-prompt=0")
                .run(context -> assertThat(context.getBean(ToolSearchProperties.class).maxToolsInPrompt())
                        .as("显式写 0 才是「永远检索」")
                        .isZero());
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
                        ToolSearch.toolLoop(properties, tools.size(), index, ObservationRegistry.NOOP))
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

    /** 一个真工具 + 一堆占位工具，凑到超过阈值。 */
    private static List<ToolCallback> withNoise(RecordingTool real, int noise) {
        List<ToolCallback> tools = new ArrayList<>(List.of(real));
        tools.addAll(noisyTools(noise));
        return List.copyOf(tools);
    }
}
