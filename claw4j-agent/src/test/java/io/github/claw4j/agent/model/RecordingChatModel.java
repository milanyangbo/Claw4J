package io.github.claw4j.agent.model;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 真模型的**探针**：把调用原样转发给真实 DeepSeek，只顺手把收到的 {@code Prompt} 记下来。
 *
 * <p>它<b>不是</b>假模型——请求真的发出去了，回答也是模型给的。
 * 与已经删掉的 {@code ScriptedChatModel} 的区别是：那个决定"模型说什么"，
 * 这个只观察"模型收到了什么"。之所以还要有它，是因为有一类判据落在
 * **请求侧**："模型这一轮看得见哪些工具"只能从 {@code ChatOptions} 上读——
 * 清单里没有的工具，模型根本不会去调，而这件事从回答上看不出来。
 *
 * <p>记录请求而不是记录回答，还有一个好处：它不会因为回答的措辞变化而失效。
 */
public final class RecordingChatModel implements ChatModel {

    private final ChatModel delegate;

    private final List<Prompt> received = new ArrayList<>();

    private RecordingChatModel(ChatModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** 包一层真模型。传进来的必须是真模型，否则"探针看到的就是假的"。 */
    public static RecordingChatModel of(ChatModel delegate) {
        return new RecordingChatModel(delegate);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        received.add(prompt);
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        received.add(prompt);
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    /** 每一次调用收到的 {@code Prompt}，按调用顺序。 */
    public List<Prompt> prompts() {
        return List.copyOf(received);
    }

    /**
     * 最近一次调用里模型可见的工具名。
     *
     * <p>从 {@code ToolCallingChatOptions} 上读而不是从容器里读 bean：
     * 容器里有这个 bean 不等于它进了这一轮的清单——装配漏收一种形态时，
     * bean 还在，模型却看不见那个工具。
     */
    public List<String> toolNamesOfLastRequest() {
        if (received.isEmpty()) {
            return List.of();
        }
        ChatOptions options = received.get(received.size() - 1).getOptions();
        if (options instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null) {
            return toolOptions.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
        }
        return List.of();
    }
}
