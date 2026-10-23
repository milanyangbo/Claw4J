package io.github.claw4j.agent.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由与主备降级的行为。全部用假模型，不联网、不烧 token（ADR-0006）。
 *
 * <p>为什么值得单独测：降级是"平时看不见、出事才生效"的逻辑，靠真实调用怎么也测不全
 * ——你没法让 DeepSeek 在测试里恰好超时。假模型能把每一种失败时序摆出来。
 */
class RoutingChatModelTest {

    private static Prompt prompt(String text) {
        return new Prompt(new UserMessage(text));
    }

    @Test
    void 主模型正常时不惊动备模型() {
        FakeChatModel primary = FakeChatModel.ok("deepseek", "来自主模型");
        FakeChatModel secondary = FakeChatModel.ok("qwen", "来自备模型");
        RoutingChatModel routing = routing(primary, secondary);

        assertThat(text(routing.call(prompt("你好")))).isEqualTo("来自主模型");
        assertThat(primary.callCount()).isEqualTo(1);
        assertThat(secondary.callCount()).isZero();
    }

    @Test
    void 主模型失败时退到备模型() {
        FakeChatModel primary = FakeChatModel.broken("deepseek", new IllegalStateException("连接超时"));
        FakeChatModel secondary = FakeChatModel.ok("qwen", "来自备模型");

        assertThat(text(routing(primary, secondary).call(prompt("你好")))).isEqualTo("来自备模型");
    }

    @Test
    void 全部失败时抛出路由异常并把每次失败都带上() {
        IllegalStateException first = new IllegalStateException("主模型 429");
        IllegalStateException second = new IllegalStateException("备模型 500");
        RoutingChatModel routing = routing(
                FakeChatModel.broken("deepseek", first),
                FakeChatModel.broken("qwen", second));

        assertThatThrownBy(() -> routing.call(prompt("你好")))
                .isInstanceOf(ModelRoutingException.class)
                .hasMessageContaining("deepseek")
                .hasMessageContaining("qwen")
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .containsExactly(first, second));
    }

    @Test
    void 流式在首块之前失败会降级到备模型() {
        RoutingChatModel routing = routing(
                FakeChatModel.broken("deepseek", new IllegalStateException("握手就失败了")),
                FakeChatModel.ok("qwen", "来自备模型"));

        List<String> chunks = routing.stream(prompt("你好"))
                .map(RoutingChatModelTest::text)
                .collectList()
                .block();

        assertThat(chunks).containsExactly("来自备模型");
    }

    /**
     * 已经吐出去的内容不能重来：此时再转投备模型，客户端会收到"半句主 + 全句备"。
     * 这种重复在 IM 里比直接报错更难看，所以流式的降级窗口只到首块为止。
     */
    @Test
    void 流式已经吐出内容后失败不再降级() {
        FakeChatModel primary = FakeChatModel.breaksAfterFirstChunk("deepseek", "前半句", new IllegalStateException("读到一半断了"));
        FakeChatModel secondary = FakeChatModel.ok("qwen", "来自备模型");
        RoutingChatModel routing = routing(primary, secondary);

        assertThatThrownBy(() -> routing.stream(prompt("你好")).collectList().block())
                .hasMessageContaining("读到一半断了");
        assertThat(secondary.streamCount()).isZero();
    }

    @Test
    void 默认参数取主模型的() {
        FakeChatModel primary = FakeChatModel.ok("deepseek", "主");
        RoutingChatModel routing = routing(primary, FakeChatModel.ok("qwen", "备"));

        assertThat(routing.getOptions().getModel()).isEqualTo("deepseek");
    }

    /**
     * 这条钉的是一个真 bug：各家 {@code ChatOptions} 互不兼容，厂商的 ChatModel 直接把
     * {@code prompt.getOptions()} 强转成自己那一型。降级时若原样把主模型的参数递给备模型，
     * 当场 ClassCastException —— 救命的那条路径反而必崩。
     */
    @Test
    void 降级时把参数换成备模型那一家的类型() {
        FakeChatModel primary = FakeChatModel
                .broken("qwen", new IllegalStateException("429 限流"))
                .withOptions(OpenAiChatOptions.builder().model("qwen-plus").temperature(0.2).build());
        FakeChatModel secondary = FakeChatModel
                .ok("deepseek", "来自备模型")
                .withOptions(DeepSeekChatOptions.builder().model("deepseek-chat").build());

        Prompt incoming = new Prompt(new UserMessage("你好"), primary.getOptions());
        assertThat(text(routing(primary, secondary).call(incoming))).isEqualTo("来自备模型");

        ChatOptions used = secondary.lastPrompt().getOptions();
        assertThat(used).isInstanceOf(DeepSeekChatOptions.class);
        // 主模型的 model 名绝不能带到备模型上：那等于让 DeepSeek 去跑 qwen-plus
        assertThat(used.getModel()).isEqualTo("deepseek-chat");
        // 厂商无关的参数要跟着过去，否则调用方设的温度在降级后悄悄失效
        assertThat(used.getTemperature()).isEqualTo(0.2);
    }

    /**
     * 这条钉的是第二个真 bug，而且比类型转换那个更阴：那一个是降级时直接崩，
     * 这一个降级后<b>照常返回，只是再也没有工具可用</b>。
     *
     * <p>跨厂商换参数时只搬温度、topP 那几个标量是不够的——工具回调不在标量里。
     * 真实链路上的表现是：主的端点指死后备模型接住请求，回答变成
     * "我无法直接访问数据库，请确认口径"。不报错、不超时，只是查不到数了。
     * 所以断言钉在"备模型收到的参数里有没有工具"，而不是"它有没有回答"。
     */
    @Test
    void 降级时工具回调与工具上下文跟着过去() {
        ToolCallback tool = new FakeToolCallback("querySales");
        FakeChatModel primary = FakeChatModel
                .broken("deepseek", new IllegalStateException("连接被拒"))
                .withOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-chat")
                        .toolCallbacks(List.of(tool))
                        .toolContext(Map.of("tenant", "acme"))
                        .build());
        FakeChatModel secondary = FakeChatModel
                .ok("qwen", "来自备模型")
                .withOptions(OpenAiChatOptions.builder().model("qwen-plus").build());

        Prompt incoming = new Prompt(new UserMessage("上周华东区成交额多少"), primary.getOptions());
        assertThat(text(routing(primary, secondary).call(incoming))).isEqualTo("来自备模型");

        ChatOptions used = secondary.lastPrompt().getOptions();
        assertThat(used).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(((ToolCallingChatOptions) used).getToolCallbacks()).containsExactly(tool);
        assertThat(((ToolCallingChatOptions) used).getToolContext()).containsEntry("tenant", "acme");
    }

    @Test
    void 调用方给的参数不是这一家的类型时也要转换() {
        FakeChatModel primary = FakeChatModel
                .ok("deepseek", "主模型回答")
                .withOptions(DeepSeekChatOptions.builder().model("deepseek-chat").build());

        Prompt incoming = new Prompt(new UserMessage("你好"),
                ChatOptions.builder().model("随便写的").temperature(0.9).build());
        routing(primary, FakeChatModel.ok("qwen", "备")).call(incoming);

        ChatOptions used = primary.lastPrompt().getOptions();
        assertThat(used).isInstanceOf(DeepSeekChatOptions.class);
        assertThat(used.getModel()).isEqualTo("deepseek-chat");
        assertThat(used.getTemperature()).isEqualTo(0.9);
    }

    @Test
    void 空路由没有意义直接拒绝() {
        assertThatThrownBy(() -> new RoutingChatModel(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RoutingChatModel routing(FakeChatModel primary, FakeChatModel secondary) {
        return new RoutingChatModel(List.of(
                new RoutingChatModel.Route(primary.name(), primary),
                new RoutingChatModel.Route(secondary.name(), secondary)));
    }

    private static String text(ChatResponse response) {
        return response.getResult().getOutput().getText();
    }

    /**
     * 假模型：能正常回答、能一调用就炸、也能"吐出一块再炸"。
     * 第三种是流式降级规则的边界，没有它这条规则测不出来。
     */
    private static final class FakeChatModel implements ChatModel {

        private final String name;
        private final RuntimeException failure;
        private final String chunkBeforeFailure;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger streams = new AtomicInteger();
        private ChatOptions options;
        private Prompt lastPrompt;

        private FakeChatModel(String name, RuntimeException failure, String chunkBeforeFailure) {
            this.name = name;
            this.failure = failure;
            this.chunkBeforeFailure = chunkBeforeFailure;
        }

        static FakeChatModel ok(String name, String answer) {
            return new FakeChatModel(name, null, answer);
        }

        /** 同步与流式都在吐出任何内容之前失败。 */
        static FakeChatModel broken(String name, RuntimeException failure) {
            return new FakeChatModel(name, failure, null);
        }

        /** 流式先吐一块再失败；同步调用仍然成功。 */
        static FakeChatModel breaksAfterFirstChunk(String name, String chunk, RuntimeException failure) {
            return new FakeChatModel(name, failure, chunk);
        }

        String name() {
            return name;
        }

        int callCount() {
            return calls.get();
        }

        int streamCount() {
            return streams.get();
        }

        /** 这一家的参数类型。跨厂商降级时能不能转对，全靠它演得像。 */
        FakeChatModel withOptions(ChatOptions options) {
            this.options = options;
            return this;
        }

        Prompt lastPrompt() {
            return lastPrompt;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            if (failure != null && chunkBeforeFailure == null) {
                throw failure;
            }
            return response(failure == null ? chunkBeforeFailure : name);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streams.incrementAndGet();
            lastPrompt = prompt;
            if (failure == null) {
                return Flux.just(response(chunkBeforeFailure));
            }
            // chunkBeforeFailure 为 null 表示"一块都没吐就炸"，否则是"吐了一块才炸"
            return chunkBeforeFailure == null
                    ? Flux.error(failure)
                    : Flux.just(response(chunkBeforeFailure)).concatWith(Flux.error(failure));
        }

        @Override
        public ChatOptions getOptions() {
            return options != null ? options : ChatOptions.builder().model(name).build();
        }

        private static ChatResponse response(String text) {
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(text))))
                    .build();
        }
    }

    /**
     * 最小可用的工具回调：只要能报出自己的名字就够了 ——
     * 这里要验的是"它有没有被搬到备模型那一侧"，不是它能不能真的执行。
     */
    private record FakeToolCallback(String name) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("假工具，只为验参数搬运")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String input) {
            return "{}";
        }
    }
}
