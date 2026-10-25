package io.github.claw4j.agent;

import io.github.claw4j.agent.model.ModelConfiguration;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一个完整功能：一次对话从 {@link AgentPort} 进去，到通道拿到末端消息为止。
 *
 * <p>这一层守的是"框架装配好了、但回答的交付方式错了"这一类事故：流式没有结束信号、
 * 结束信号发两次、工具循环把结果丢在第二次模型调用之前、多个工具来源里少收一种。
 * 单看任何一个类都不会红，用户侧看到的是"它不回我了"或"它说没有这个能力"。
 */
class ChatPipelineTest {

    @Test
    @DisplayName("一次问答把模型的答案交回，并带上入站那条会话的 chatId")
    void replyCarriesAnswerAndChatId() {
        FixedChatModel model = FixedChatModel.replying("本月成交额 42 万");

        OutboundMessage reply = port(model).reply(message("feishu:chat-1", "本月成交额多少"));

        assertThat(reply.text()).isEqualTo("本月成交额 42 万");
        assertThat(reply.chatId()).isEqualTo("feishu:chat-1");
        assertThat(reply.finalChunk()).isTrue();
        assertThat(model.prompts()).hasSize(1);
    }

    @Test
    @DisplayName("流式逐块下发，最后一块是唯一的 finalChunk，所有块都带同一个 chatId")
    void streamEndsWithExactlyOneFinalChunk() {
        FixedChatModel model = FixedChatModel.streaming("本月", "成交额", "42 万");
        List<OutboundMessage> chunks = new ArrayList<>();

        port(model).stream(message("feishu:chat-2", "多少"), chunks::add);

        assertThat(chunks).extracting(OutboundMessage::text)
                .containsExactly("本月", "成交额", "42 万", "");
        assertThat(chunks).extracting(OutboundMessage::finalChunk)
                .containsExactly(false, false, false, true);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.chatId()).isEqualTo("feishu:chat-2"));
    }

    @Test
    @DisplayName("模型一块都不吐时仍然发一次 finalChunk：通道不能靠超时猜“说完了”")
    void emptyStreamStillEmitsFinalChunk() {
        List<OutboundMessage> chunks = new ArrayList<>();

        port(FixedChatModel.streaming()).stream(message("feishu:chat-3", "在吗"), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).finalChunk()).isTrue();
        assertThat(chunks.get(0).text()).isEmpty();
        assertThat(chunks.get(0).chatId()).isEqualTo("feishu:chat-3");
    }

    @Test
    @DisplayName("链路断了不伪装成正常收尾：抛错，且不发 finalChunk")
    void brokenStreamDoesNotFakeCompletion() {
        FixedChatModel model = new FixedChatModel(
                prompt -> text("不该走到这里"),
                prompt -> Flux.concat(Flux.just(text("前半")), Flux.error(new IllegalStateException("模型断了"))));
        List<OutboundMessage> chunks = new ArrayList<>();

        assertThatThrownBy(() -> port(model).stream(message("feishu:chat-4", "说一半"), chunks::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型断了");

        assertThat(chunks).extracting(OutboundMessage::text).containsExactly("前半");
        assertThat(chunks).noneMatch(OutboundMessage::finalChunk);
    }

    @Test
    @DisplayName("模型要工具时真的执行一次，工具结果回到模型手上，最终答案来自第二轮")
    void toolCallLoopFeedsResultBackToTheModel() {
        AtomicInteger round = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(
                prompt -> round.getAndIncrement() == 0
                        ? toolCall("querySales", "{\"question\":\"本月成交额\"}")
                        : text("本月成交额 3 单"),
                prompt -> Flux.empty());
        RecordingTool tool = new RecordingTool("querySales", "3 单");

        OutboundMessage reply = port(model, List.of(), List.of(tool), List.of())
                .reply(message("feishu:chat-5", "本月成交额多少"));

        assertThat(reply.text()).isEqualTo("本月成交额 3 单");
        assertThat(tool.calls.get()).isEqualTo(1);
        assertThat(tool.inputs).containsExactly("{\"question\":\"本月成交额\"}");
        assertThat(model.prompts()).hasSize(2);
        // 工具结果必须作为一条 ToolResponseMessage 回到模型手上，否则第二轮是在凭空作答。
        assertThat(model.prompts().get(1).getInstructions())
                .anyMatch(message -> message instanceof ToolResponseMessage response
                        && response.getResponses().stream()
                        .anyMatch(part -> part.responseData().contains("3 单")));
    }

    @Test
    @DisplayName("模型一直要工具时在总数上限处截断，客户端拿到一代回答而不是无限烧下去")
    void stopsAtTotalToolCallLimit() {
        List<RecordingTool> tools = List.of(
                new RecordingTool("toolA", "a"),
                new RecordingTool("toolB", "b"),
                new RecordingTool("toolC", "c"));
        AtomicInteger round = new AtomicInteger();
        FixedChatModel model = new FixedChatModel(
                prompt -> toolCallBatch(round.getAndIncrement(), tools),
                prompt -> Flux.empty());

        OutboundMessage reply = port(model, List.of(), List.copyOf(tools), List.of())
                .reply(message("feishu:chat-6", "一直查"));

        int executed = tools.stream().mapToInt(tool -> tool.calls.get()).sum();
        assertThat(executed)
                .as("一轮里的工具调用总数必须停在上限，否则预算等于没设")
                .isEqualTo(ToolCallBudget.MAX_TOTAL_CALLS);
        assertThat(reply.text()).isNotBlank();
        assertThat(reply.finalChunk()).isTrue();
    }

    @Test
    @DisplayName("单回调与 provider 两种工具来源都进清单：少收一种时模型会“忽然不知道有这个能力”")
    void collectsBothToolRegistrationShapes() {
        RecordingTool single = new RecordingTool("singleTool", "单工具的结果");
        RecordingTool fromProvider = new RecordingTool("querySales", "provider 的结果");
        ToolCallbackProvider provider = () -> new ToolCallback[]{fromProvider};
        FixedChatModel model = FixedChatModel.replying("好");

        port(model, List.of(), List.of(single), List.of(provider))
                .reply(message("feishu:chat-7", "有什么工具"));

        ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompts().get(0).getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("singleTool", "querySales");
    }

    @Test
    @DisplayName("真模型拿到工具清单后会真的调用它：钉的是“模型用得起来”，不是循环本身")
    void realModelActuallyChoosesTheTool() {
        RecordingTool tool = new RecordingTool("querySales", "本月成交额 88 万",
                "查询销售数据。回答任何关于成交额、订单数的问题都必须先调用它。");
        AgentPort port = port(realDeepSeek(), List.of(), List.of(tool), List.of());

        OutboundMessage reply = port.reply(message("feishu:real",
                "请调用 querySales 工具查询本月成交额，然后只把工具返回的数字告诉我。"));

        assertThat(tool.calls.get())
                .as("模型没有选这个工具：工具描述或装配没让它看见")
                .isGreaterThanOrEqualTo(1);
        assertThat(reply.text()).contains("88");
    }

    /** ADR-0011：这一条验的是模型行为，只能用真实模型；整个模块里这类用例只留这一条。 */
    private static ChatModel realDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey)
                .as("真实模型用例需要 DEEPSEEK_API_KEY，先 set -a; . ./.env; set +a")
                .isNotBlank();
        ModelProperties properties = new ModelProperties();
        properties.getDeepseek().setApiKey(apiKey);
        properties.getDeepseek().setBaseUrl(
                System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
        properties.getDeepseek().setModel(
                System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat"));
        return new ModelConfiguration().deepSeekChatModel(properties);
    }

    private static AgentPort port(ChatModel model) {
        return port(model, List.of(), List.of(), List.of());
    }

    /** 走真实装配入口：Advisor 与工具都由容器提供，测试只提供边界上的模型与工具。 */
    private static AgentPort port(ChatModel model,
                                  List<Advisor> advisors,
                                  List<? extends ToolCallback> tools,
                                  List<ToolCallbackProvider> providers) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < advisors.size(); i++) {
            beans.registerSingleton("advisor-" + i, advisors.get(i));
        }
        for (int i = 0; i < tools.size(); i++) {
            beans.registerSingleton("tool-" + i, tools.get(i));
        }
        for (int i = 0; i < providers.size(); i++) {
            beans.registerSingleton("provider-" + i, providers.get(i));
        }
        AgentAutoConfiguration configuration = new AgentAutoConfiguration();
        ChatClient client = configuration.chatClient(model,
                beans.getBeanProvider(Advisor.class),
                beans.getBeanProvider(ToolCallback.class),
                beans.getBeanProvider(ToolCallbackProvider.class),
                beans.getBeanProvider(ObservationRegistry.class));
        return configuration.agentPort(client);
    }

    private static InboundMessage message(String chatId, String text) {
        return new InboundMessage(Principal.readOnly("u-1"), chatId, text);
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(value))))
                .build();
    }

    private static ChatResponse toolCall(String name, String arguments) {
        return toolCall("call-" + name, name, arguments);
    }

    private static ChatResponse toolCall(String id, String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }

    private static ChatResponse toolCallBatch(int round, List<RecordingTool> tools) {
        List<AssistantMessage.ToolCall> calls = tools.stream()
                .map(tool -> new AssistantMessage.ToolCall(
                        "round-" + round + "-" + tool.name, "function", tool.name, "{}"))
                .toList();
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(calls).build();
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }

    /**
     * 固定文本的模型桩：这一层断言的对象是"回答怎么被交付"，不是模型会不会思考。
     * 模型只是文本来源，换掉它不改变被测的规则。
     */
    private static final class FixedChatModel implements ChatModel {

        private final Function<Prompt, ChatResponse> calls;
        private final Function<Prompt, Flux<ChatResponse>> streams;
        private final ChatOptions options = ToolCallingChatOptions.builder().build();
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        private FixedChatModel(Function<Prompt, ChatResponse> calls,
                               Function<Prompt, Flux<ChatResponse>> streams) {
            this.calls = calls;
            this.streams = streams;
        }

        static FixedChatModel replying(String answer) {
            return new FixedChatModel(prompt -> text(answer), prompt -> Flux.just(text(answer)));
        }

        static FixedChatModel streaming(String... chunks) {
            List<ChatResponse> responses = new ArrayList<>();
            for (String chunk : chunks) {
                responses.add(text(chunk));
            }
            return new FixedChatModel(
                    prompt -> text(String.join("", chunks)),
                    prompt -> Flux.fromIterable(responses));
        }

        List<Prompt> prompts() {
            return prompts;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return calls.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return streams.apply(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }
    }

    /** 记录"到底被调用了几次、参数是什么"的工具：越权与循环的断言都落在它身上。 */
    private static final class RecordingTool implements ToolCallback {

        private final String name;
        private final String result;
        private final String description;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> inputs = new CopyOnWriteArrayList<>();

        private RecordingTool(String name, String result) {
            this(name, result, "测试工具 " + name);
        }

        private RecordingTool(String name, String result, String description) {
            this.name = name;
            this.result = result;
            this.description = description;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            inputs.add(toolInput);
            return result;
        }
    }
}
