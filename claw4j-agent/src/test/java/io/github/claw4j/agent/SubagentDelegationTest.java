package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.subagent.SubagentAutoConfiguration;
import io.github.claw4j.agent.subagent.SubagentCatalog;
import io.github.claw4j.agent.subagent.SubagentDefinition;
import io.github.claw4j.agent.subagent.SubagentDelegation;
import io.github.claw4j.agent.subagent.SubagentRunner;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个完整功能：把一件事派给专职 Subagent。
 *
 * <p>这一层的规则坏掉之后都不会立刻响：定义写坏被静默略过，模型手上少一个 Agent；
 * 子窗口继承了父的上下文，隔离就没了；委派绕过了父的身份鉴权，越权就从后门发生。
 * 所以断言落在"子 Agent 看到了什么、用了谁的身份、父最后拿到什么"上。
 */
class SubagentDelegationTest {

    private static final String SUBAGENT_PROMPT = "你是数据分析师，只回答查数问题。";

    @Test
    @DisplayName("目录扫描按文件名排序，只收 *.md，front matter 与正文各归各位")
    void scansDirectoryInStableOrder(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("agents"));
        write(directory, "b-analyst.md", "analyst", "负责查数与核对口径", "querySales", SUBAGENT_PROMPT);
        write(directory, "a-writer.md", "writer", "负责把口径写成一句话", null, "你是文案。");
        Files.writeString(directory.resolve("README.txt"), "不是定义文件");

        List<SubagentDefinition> definitions = SubagentCatalog.load(directory);

        assertThat(definitions).extracting(SubagentDefinition::name).containsExactly("writer", "analyst");
        assertThat(definitions.get(1).tools()).containsExactly("querySales");
        assertThat(definitions.get(1).systemPrompt()).isEqualTo(SUBAGENT_PROMPT);
    }

    @Test
    @DisplayName("配了目录却不存在时启动失败：这是配置错误，不是“这一层不存在”")
    void failsWhenDirectoryIsMissing(@TempDir Path root) {
        Path missing = root.resolve("no-such-dir");

        assertThatThrownBy(() -> SubagentCatalog.load(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在或不是目录");
    }

    @Test
    @DisplayName("目录一个 *.md 都没有也失败：空目录与“没配”不是一回事")
    void failsWhenDirectoryHasNoDefinitions(@TempDir Path root) throws IOException {
        Path empty = Files.createDirectories(root.resolve("empty"));

        assertThatThrownBy(() -> SubagentCatalog.load(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("一个 *.md 都没有");
    }

    @Test
    @DisplayName("定义缺 description 就整层起不来，而不是让父 Agent 只看到一个名字")
    void failsWhenDefinitionMissesDescription(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("agents"));
        Files.writeString(directory.resolve("broken.md"), """
                ---
                name: analyst
                ---
                正文在，但父看不到它该什么时候派我。
                """);

        assertThatThrownBy(() -> SubagentCatalog.load(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("同一个工具同时进白名单与黑名单按定义错误处理，不靠过滤先后顺序决定")
    void failsWhenAllowAndDenyListsContradict(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("agents"));
        Files.writeString(directory.resolve("contradiction.md"), """
                ---
                name: analyst
                description: 负责查数
                tools: querySales
                disallowedTools: querySales
                ---
                正文。
                """);

        assertThatThrownBy(() -> SubagentCatalog.load(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自相矛盾");
    }

    @Test
    @DisplayName("白名单里写 Task 按定义错误处理：委派不嵌套")
    void failsWhenDefinitionAllowsNestedDelegation(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("agents"));
        Files.writeString(directory.resolve("nested.md"), """
                ---
                name: analyst
                description: 负责查数
                tools: Task
                ---
                正文。
                """);

        assertThatThrownBy(() -> SubagentCatalog.load(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("委派不嵌套");
    }

    @Test
    @DisplayName("白名单点名了没装上的工具时拒绝执行，并点名缺哪个")
    void refusesWhenWhitelistedToolIsMissing() {
        SubagentDefinition definition = definition(List.of("missingTool"));
        RecordingTool available = new RecordingTool("querySales", "3 单");
        SubagentDelegation delegation = delegation(definition, available);

        assertThatThrownBy(() -> delegation.call(
                "{\"subagent\":\"analyst\",\"task\":\"查成交额\"}", context(admin())))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .hasMessageContaining("missingTool");
    }

    @Test
    @DisplayName("委派在独立窗口里跑：子只拿到 task，父只拿回结论，中间过程不外泄")
    void delegationRunsInItsOwnWindowAndReturnsOnlyTheConclusion() {
        RecordingTool sales = new RecordingTool("querySales", "内部明细 999");
        AtomicInteger subRounds = new AtomicInteger();
        AtomicInteger parentRounds = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> {
            if (prompt.getContents().contains(SUBAGENT_PROMPT)) {
                return subRounds.getAndIncrement() == 0
                        ? toolCall("querySales", "{}")
                        : text("结论：3 单");
            }
            if (parentRounds.getAndIncrement() == 0) {
                return toolCall("Task", "{\"subagent\":\"analyst\",\"task\":\"查一下本月成交额\"}");
            }
            if (!hasToolResponse(prompt, "结论：3 单")) {
                throw new AssertionError("父的上下文里没有子 Agent 的结论：" + prompt.getInstructions());
            }
            return text("父收到：结论：3 单");
        });
        SubagentDefinition definition = definition(List.of("querySales"));
        AuthorizationAdvisor authorization = authorization("querySales");
        SubagentDelegation delegation = new SubagentDelegation(List.of(definition),
                new SubagentRunner(model, ObservationRegistry.NOOP, () -> List.of(sales), authorization));
        AgentPort port = port(model, delegation, authorization);

        OutboundMessage reply = port.reply(message(admin(), "帮我查成交额"));

        assertThat(reply.text()).isEqualTo("父收到：结论：3 单");
        assertThat(sales.calls.get()).isEqualTo(1);
        String subWindow = model.prompts().stream()
                .map(Prompt::getContents)
                .filter(contents -> contents.contains(SUBAGENT_PROMPT))
                .findFirst()
                .orElseThrow();
        assertThat(subWindow)
                .as("子窗口是新建的：父那一轮的原话不该出现")
                .doesNotContain("帮我查成交额")
                .contains("查一下本月成交额");
        assertThat(model.prompts().stream()
                .map(Prompt::getContents)
                .filter(contents -> !contents.contains(SUBAGENT_PROMPT)))
                .as("子 Agent 的中间过程不会回到父窗口")
                .noneMatch(contents -> contents.contains("内部明细 999"));
    }

    @Test
    @DisplayName("委派不提权：父是 guest 时，子 Agent 照样读不到 sales_order，工具一次都不执行")
    void delegationNeverElevatesPrivileges() {
        RecordingTool sales = new RecordingTool("querySales", "越权数据");
        AtomicInteger subRounds = new AtomicInteger();
        AtomicInteger parentRounds = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(prompt -> {
            if (prompt.getContents().contains(SUBAGENT_PROMPT)) {
                subRounds.incrementAndGet();
                return toolCall("querySales", "{}");
            }
            if (parentRounds.getAndIncrement() == 0) {
                return toolCall("Task", "{\"subagent\":\"analyst\",\"task\":\"查一下本月成交额\"}");
            }
            if (!hasToolResponse(prompt, AuthorizationAdvisor.DENIAL_PREFIX)) {
                throw new AssertionError("父没有收到子链的拒绝：" + prompt.getInstructions());
            }
            return text("父看到拒绝");
        });
        SubagentDefinition definition = definition(List.of("querySales"));
        AuthorizationAdvisor authorization = authorization("querySales");
        SubagentDelegation delegation = new SubagentDelegation(List.of(definition),
                new SubagentRunner(model, ObservationRegistry.NOOP, () -> List.of(sales), authorization));
        AgentPort port = port(model, delegation, authorization);

        OutboundMessage reply = port.reply(message(guest(), "帮我查成交额"));

        assertThat(reply.text()).isEqualTo("父看到拒绝");
        assertThat(sales.calls.get()).as("子 Agent 用的是父身份，父读不到的表子也读不到").isZero();
        assertThat(subRounds.get()).as("被拒之后不该再回头问子模型").isEqualTo(1);
    }

    @Test
    @DisplayName("没带身份的委派一律拒绝，不猜一个匿名身份照常派出去")
    void refusesDelegationWithoutPrincipal() {
        RecordingTool sales = new RecordingTool("querySales", "3 单");
        FixedChatModel model = new FixedChatModel(prompt -> text("不该被调用"));
        SubagentDelegation delegation = new SubagentDelegation(
                List.of(definition(List.of("querySales"))),
                new SubagentRunner(model, ObservationRegistry.NOOP,
                        () -> List.of(sales), authorization("querySales")));

        String withoutContext = delegation.call("{\"subagent\":\"analyst\",\"task\":\"查\"}");
        String emptyContext = delegation.call("{\"subagent\":\"analyst\",\"task\":\"查\"}",
                new ToolContext(Map.of()));

        assertThat(withoutContext).startsWith(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(emptyContext).startsWith(AuthorizationAdvisor.DENIAL_PREFIX);
        assertThat(model.prompts()).isEmpty();
        assertThat(sales.calls.get()).isZero();
    }

    @Test
    @DisplayName("派了一个不存在的 Subagent 时回一份可用清单，模型下一步能改名字再派")
    void listsAvailableSubagentsForUnknownName() {
        RecordingTool sales = new RecordingTool("querySales", "3 单");
        FixedChatModel model = new FixedChatModel(prompt -> text("不该被调用"));
        SubagentDelegation delegation = new SubagentDelegation(
                List.of(definition(List.of("querySales"))),
                new SubagentRunner(model, ObservationRegistry.NOOP,
                        () -> List.of(sales), authorization("querySales")));

        String answer = delegation.call("{\"subagent\":\"nobody\",\"task\":\"查\"}", context(admin()));

        assertThat(answer).contains("没有名为 nobody").contains("analyst");
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    @DisplayName("父看到的只有名字与一句描述：正文不在工具描述里，十个 Agent 也只是十行")
    void toolDescriptionCarriesMetadataOnly() {
        SubagentDefinition definition = definition(List.of("querySales"));
        SubagentDelegation delegation = new SubagentDelegation(List.of(definition),
                new SubagentRunner(new FixedChatModel(prompt -> text("ok")), ObservationRegistry.NOOP,
                        () -> List.of(), authorization("querySales")));

        String description = delegation.getToolDefinition().description();

        assertThat(description).contains("analyst: 负责查数与核对口径");
        assertThat(description).doesNotContain(SUBAGENT_PROMPT);
        assertThat(delegation.registrations()).containsExactly("- analyst: 负责查数与核对口径");
    }

    @Test
    @DisplayName("委派进父的工具清单，也进权限登记：只读身份不能委派，带 delegate_task 的才行")
    void delegationJoinsToolListAndPermissionRegistry(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("agents"));
        write(directory, "analyst.md", "analyst", "负责查数", "querySales", SUBAGENT_PROMPT);
        RecordingTool single = new RecordingTool("singleTool", "单工具");
        RecordingTool providerTool = new RecordingTool("querySales", "3 单");
        ToolCallbackProvider provider = () -> new ToolCallback[]{providerTool};
        FixedChatModel model = new FixedChatModel(prompt -> text("ok"));

        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("chatModel", model);
        beans.registerSingleton("singleTool", single);
        beans.registerSingleton("provider", provider);
        SubagentAutoConfiguration configuration = new SubagentAutoConfiguration();
        ToolCallback task = configuration.subagentToolCallback(directory.toString(), model,
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(ObservationRegistry.class),
                authorization("querySales"));
        beans.registerSingleton("task", task);

        List<ToolCallback> collected = AgentAutoConfiguration.collectToolCallbacks(
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class));
        assertThat(collected).extracting(callback -> callback.getToolDefinition().name())
                .contains("Task", "singleTool", "querySales");

        ToolPermissionRegistry registry = configuration.subagentPermissionRegistry();
        ToolPermission permission = registry.permissionFor(SubagentDelegation.TOOL_NAME).orElseThrow();
        assertThat(permission.action()).isEqualTo(SubagentDelegation.ACTION);
        assertThat(permission.tables()).as("委派本身不碰库：替它登记表就是给越权开通行证").isEmpty();
        assertThat(Principal.readOnly("u-1").canUse(permission)).isFalse();
        assertThat(admin().canUse(permission)).isTrue();
    }

    private static Principal admin() {
        return Principal.of("u-1", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));
    }

    private static Principal guest() {
        return Principal.of("u-1", "guest", Set.of("region"),
                Set.of(ToolPermission.READ, SubagentDelegation.ACTION));
    }

    private static InboundMessage message(Principal principal, String text) {
        return new InboundMessage(principal, "feishu:sub", text);
    }

    private static ToolContext context(Principal principal) {
        return new ToolContext(Map.of(AuthorizationAdvisor.PRINCIPAL_CONTEXT_KEY, principal));
    }

    private static SubagentDefinition definition(List<String> tools) {
        return new SubagentDefinition("analyst", "负责查数与核对口径", SUBAGENT_PROMPT, tools, List.of());
    }

    private static AuthorizationAdvisor authorization(String toolName) {
        return new AuthorizationAdvisor(List.of(ToolPermissionRegistry.of(Map.of(
                toolName, ToolPermission.read("sales_order"),
                SubagentDelegation.TOOL_NAME, SubagentDelegation.permission()))));
    }

    private static SubagentDelegation delegation(SubagentDefinition definition, ToolCallback available) {
        return new SubagentDelegation(List.of(definition),
                new SubagentRunner(new FixedChatModel(prompt -> text("ok")), ObservationRegistry.NOOP,
                        () -> List.of(available), authorization(available.getToolDefinition().name())));
    }

    private static void write(Path directory, String fileName, String name, String description,
                              String tools, String body) throws IOException {
        StringBuilder markdown = new StringBuilder("---\n")
                .append("name: ").append(name).append('\n')
                .append("description: ").append(description).append('\n');
        if (tools != null) {
            markdown.append("tools: ").append(tools).append('\n');
        }
        markdown.append("---\n").append(body).append('\n');
        Files.writeString(directory.resolve(fileName), markdown.toString());
    }

    private static boolean hasToolResponse(Prompt prompt, String expected) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .anyMatch(message -> message.getResponses().stream()
                        .anyMatch(response -> response.responseData() != null
                                && response.responseData().contains(expected)));
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(value))))
                .build();
    }

    private static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments)))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }

    private static AgentPort port(ChatModel model, ToolCallback tool, Advisor... advisors) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < advisors.length; i++) {
            beans.registerSingleton("advisor-" + i, advisors[i]);
        }
        beans.registerSingleton("tool", tool);
        AgentAutoConfiguration configuration = new AgentAutoConfiguration();
        ChatClient client = configuration.chatClient(model,
                beans.getBeanProvider(Advisor.class),
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(ObservationRegistry.class));
        return configuration.agentPort(client);
    }

    private static final class FixedChatModel implements ChatModel {

        private final Function<Prompt, ChatResponse> responder;
        private final ChatOptions options = ToolCallingChatOptions.builder().build();
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        private FixedChatModel(Function<Prompt, ChatResponse> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return responder.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return Flux.just(responder.apply(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        List<Prompt> prompts() {
            return prompts;
        }
    }

    private static final class RecordingTool implements ToolCallback {

        private final String name;
        private final String result;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("测试工具 " + name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return result;
        }
    }
}
