package io.github.claw4j.agent.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按脚本吐响应的假模型（测试夹具，ADR-0006：单测不烧 token、不依赖网络）。
 *
 * <p>与 {@link MockChatModel} 的区别：mock 是回声，脚本化能**按顺序**吐出不同的响应。
 * 这个区别是 M1 的地基——"模型先要调工具、再据此作答"是**两步**，
 * 回声模型永远只能给出一步，所以工具循环在 CI 里根本测不出来。
 *
 * <p>两个刻意的设计：
 *
 * <ul>
 *   <li><b>脚本耗尽时抛异常</b>，而不是返回空响应。空响应会让工具循环要么静默结束
 *       （测试假绿），要么一直转下去（跑满超时）——都比直接报错难查。
 *   <li><b>记录收到的每一个 Prompt</b>。这是"工具真的执行过"的唯一外部证据：
 *       工具返回值会被框架塞进第二轮的提示词里，断言第二轮的提示词含工具返回值，
 *       比去工具内部数调用次数干净。
 * </ul>
 *
 * <p>流式路径沿用 {@link MockChatModel#STREAM_CHUNK_SIZE} 分块：只吐一块的假模型
 * 测不出"流式被降级成一次性响应"。带工具调用的响应**不分块**——分块会丢掉
 * {@code toolCalls}，流式下的工具调用就断了。
 */
public final class ScriptedChatModel implements ChatModel {

    private final Queue<ChatResponse> script = new ConcurrentLinkedQueue<>();

    private final List<Prompt> received = new CopyOnWriteArrayList<>();

    private final AtomicInteger taken = new AtomicInteger();

    private ScriptedChatModel(List<ChatResponse> responses) {
        script.addAll(responses);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static String textOf(ChatResponse response) {
        var text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        received.add(prompt);
        taken.incrementAndGet();
        return next();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        received.add(prompt);
        taken.incrementAndGet();
        ChatResponse response = next();
        if (response.getResult().getOutput().hasToolCalls()) {
            return Flux.just(response);
        }
        return Flux.fromIterable(split(textOf(response))).map(ScriptedChatModel::text);
    }

    /**
     * 必须返回 {@code ToolCallingChatOptions}：{@code ToolCallingAdvisor} 开头的判断是
     * {@code options instanceof ToolCallingChatOptions}，不是就直接跳过整个工具循环。
     *
     * <p>这是假模型最容易不忠实的地：真实的 {@code DeepSeekChatOptions} /
     * {@code OpenAiChatOptions} 都实现了这个接口，所以生产路径正常，
     * 而一个返回普通 {@code ChatOptions} 的假模型会让工具调用**静默不发生**——
     * 不报错、不告警，只是模型永远拿不到工具结果。
     */
    @Override
    public ChatOptions getOptions() {
        return DefaultToolCallingChatOptions.builder().model("scripted").build();
    }

    /** 已经吐出去几条脚本。用它断言"走了几轮模型调用"。 */
    public int taken() {
        return taken.get();
    }

    public int remaining() {
        return script.size();
    }

    /** 每一次调用收到的提示词原文（按调用顺序）。调试工具循环时用得着。 */
    public List<Prompt> receivedPrompts() {
        return List.copyOf(received);
    }

    /**
     * 每一次调用收到的提示词内容（按调用顺序）。用来断言"工具真的执行过且结果回到了模型"。
     *
     * <p>{@code ToolResponseMessage#getText()} 是空串——工具结果在
     * {@code getResponses()} 的 {@code responseData()} 里，不在正文里。
     * 只拼各条消息的 {@code getText()} 会把工具结果整个漏掉，
     * 于是"工具没执行"和"执行了"看起来一模一样。
     */
    public List<String> promptTexts() {
        return received.stream()
                .map(prompt -> prompt.getInstructions().stream()
                        .map(ScriptedChatModel::textOf)
                        .reduce("", String::concat))
                .toList();
    }

    private static String textOf(org.springframework.ai.chat.messages.Message message) {
        if (message instanceof ToolResponseMessage toolResponse) {
            return toolResponse.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .map(data -> data == null ? "" : data)
                    .reduce("", String::concat);
        }
        return message.getText() == null ? "" : message.getText();
    }

    private ChatResponse next() {
        ChatResponse response = script.poll();
        if (response == null) {
            throw new IllegalStateException("脚本已耗尽：模型被调用了比脚本更多的次数。"
                    + "通常是工具循环没有按预期结束（比如工具一直被调用），"
                    + "或者脚本少写了一条响应。已调用 " + taken.get() + " 次。");
        }
        return response;
    }

    private static ChatResponse text(String content) {
        return response(AssistantMessage.builder().content(content).build());
    }

    private static List<String> split(String content) {
        List<String> chunks = new ArrayList<>();
        int size = MockChatModel.STREAM_CHUNK_SIZE;
        for (int i = 0; i < content.length(); i += size) {
            chunks.add(content.substring(i, Math.min(i + size, content.length())));
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }

    public static final class Builder {

        private final List<ChatResponse> responses = new ArrayList<>();

        private int toolCallSequence;

        /** 追加一条纯文本响应。 */
        public Builder thenText(String content) {
            responses.add(text(content));
            return this;
        }

        /**
         * 追加一条工具调用响应：正文为空，只带一次调用请求。
         * 正文刻意为空——这样"工具没被真的调用"时最终回答就是空字符串，一眼可辨。
         */
        public Builder thenToolCall(String name, String arguments) {
            var toolCall = new AssistantMessage.ToolCall(
                    "call-" + (++toolCallSequence), "function", name, arguments);
            responses.add(response(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build()));
            return this;
        }

        public ScriptedChatModel build() {
            return new ScriptedChatModel(responses);
        }
    }

    private static ChatResponse response(AssistantMessage message) {
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }
}
