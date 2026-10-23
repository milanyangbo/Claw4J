package io.github.claw4j.agent.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Advisor 链上"模型那一环"的桩：给定模型这一轮返回什么，让 Advisor 能在不联网的情况下被测。
 *
 * <p><b>它是桩不是替身模型</b>（ADR-0011）：这里替换的是"模型说了什么"这一个输入，
 * Advisor 本身、它后面的 {@code ToolCallingManager} 与工具对象全是真的。
 * 之所以必须替换：真模型不会配合去调一个它没权限的工具，也不会精确地只调一次，
 * 更不会吐出一个指定的手机号好让分块切在号码上。
 *
 * <p>类里同时给出构造 {@code ChatClientResponse} 的几个辅助：
 * "带工具调用的响应"与"带正文的响应"就是模型那一轮的全部形状。
 */
public final class ChainStubs {

    private ChainStubs() {
    }

    /** 同步路径：模型返回这一条响应。 */
    public static CallAdvisorChain callReturning(ChatClientResponse response) {
        return new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                return response;
            }

            @Override
            public List<CallAdvisor> getCallAdvisors() {
                return List.of();
            }

            @Override
            public CallAdvisorChain copy(CallAdvisor advisor) {
                return this;
            }
        };
    }

    /** 流式路径：模型按这些分块吐。 */
    public static StreamAdvisorChain streamReturning(List<ChatClientResponse> chunks) {
        return new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
                return Flux.fromIterable(chunks);
            }

            @Override
            public List<StreamAdvisor> getStreamAdvisors() {
                return List.of();
            }

            @Override
            public StreamAdvisorChain copy(StreamAdvisor advisor) {
                return this;
            }
        };
    }

    /** 等价于"模型这一轮说要调这些工具"。 */
    public static ChatClientResponse responseRequesting(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = Arrays.stream(toolNames)
                .map(name -> new AssistantMessage.ToolCall(
                        "call-" + name + "-" + System.nanoTime(), "function", name, "{}"))
                .toList();
        return responseOf(AssistantMessage.builder().content("").toolCalls(calls).build());
    }

    /** 等价于"模型这一轮说了这句话"。 */
    public static ChatClientResponse responseWithText(String text) {
        return responseOf(AssistantMessage.builder().content(text).build());
    }

    public static ChatClientResponse responseOf(AssistantMessage message) {
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(message)))
                .build();
        return ChatClientResponse.builder().chatResponse(chatResponse).context(Map.of()).build();
    }

    public static String textOf(ChatClientResponse response) {
        return response.chatResponse().getResults().get(0).getOutput().getText();
    }

    public static List<String> toolCallsOf(ChatClientResponse response) {
        return response.chatResponse().getResults().get(0).getOutput().getToolCalls().stream()
                .map(AssistantMessage.ToolCall::name)
                .toList();
    }
}
