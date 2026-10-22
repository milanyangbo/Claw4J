package io.github.claw4j.agent.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 不联网、不烧 token 的模型实现（ADR-0006）。
 *
 * <p>它把用户消息原样回声，前缀标记来源，这样端到端测试能断言"这句话确实走完了全链路"，
 * 而不是只看有没有返回。没有 API key 时它是应用默认使用的模型。
 */
public class MockChatModel implements ChatModel {

    public static final String PREFIX = "[mock] ";

    @Override
    public ChatResponse call(Prompt prompt) {
        return responseFor(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(responseFor(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().model("mock").build();
    }

    private ChatResponse responseFor(Prompt prompt) {
        var userMessage = prompt.getUserMessage();
        String echoed = userMessage == null ? "" : userMessage.getText();

        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(PREFIX + echoed))))
                .build();
    }
}
