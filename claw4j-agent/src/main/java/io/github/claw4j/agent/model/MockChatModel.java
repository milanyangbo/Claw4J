package io.github.claw4j.agent.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 不联网、不烧 token 的模型实现（ADR-0006）。
 *
 * <p>它把用户消息原样回声，前缀标记来源，这样端到端测试能断言"这句话确实走完了全链路"，
 * 而不是只看有没有返回。没有 API key 时它是应用默认使用的模型。
 *
 * <p>流式路径刻意切成 {@value #STREAM_CHUNK_SIZE} 个字符一块：如果流式被静默降级成
 * "整段一次性返回"（比如通道写错了、或者 Advisor 把流吞了），测试能从块数上看出来。
 * 只返回一块的 mock 模型测不出这种降级。
 */
public class MockChatModel implements ChatModel {

    public static final String PREFIX = "[mock] ";

    public static final int STREAM_CHUNK_SIZE = 8;

    @Override
    public ChatResponse call(Prompt prompt) {
        return response(echo(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.fromIterable(split(echo(prompt))).map(MockChatModel::response);
    }

    /**
     * 返回 {@code ToolCallingChatOptions} 而不是普通 {@code ChatOptions}：
     * {@code ToolCallingAdvisor} 的开头是 {@code options instanceof ToolCallingChatOptions}，
     * 不是就**跳过整个工具循环**——不报错、不告警。
     *
     * <p>真实的 {@code DeepSeekChatOptions} / {@code OpenAiChatOptions} 都实现了它，
     * 所以生产路径天然正常；假模型不跟着实现的话，就会出现"接了真模型工具能调、
     * 一退到 mock 工具就静默不调"这种只在本地演示时暴露的差异。
     */
    @Override
    public ChatOptions getOptions() {
        return DefaultToolCallingChatOptions.builder().model("mock").build();
    }

    private static String echo(Prompt prompt) {
        var userMessage = prompt.getUserMessage();
        return PREFIX + (userMessage == null ? "" : userMessage.getText());
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    /**
     * 分块。包内可见是故意的：测试用的 {@code ScriptedChatModel} 也按同一口径切块，
     * 否则"流式被降级成一次性响应"这件事在两个假模型上会有两种判据。
     */
    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += STREAM_CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(i + STREAM_CHUNK_SIZE, text.length())));
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }
}
