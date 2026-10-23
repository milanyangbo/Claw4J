package io.github.claw4j.agent.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 出站之前的 PII 脱敏（ADR-0007）。与鉴权是两件事：鉴权管"能不能查"，这里管"查到了能不能原样说出去"。
 *
 * <p>order 排在工具调用循环**外面**（小于 {@link ToolCallingAdvisor#DEFAULT_ORDER}），
 * 于是整通对话只在出站前做一次。放进去也不是不行，但那会连**工具结果**和模型中间轮次的
 * 话一起打码：模型要靠原始值判断"这个号码是哪一单的"，打掉了它就没得判了。
 * 脱敏是给人看的最后一道工序，不是给模型看的。
 *
 * <p>流式路径不能逐块各自脱敏：分块边界会正好落在一个手机号中间，两块都匹配不上，
 * 原始号码整个漏出去，而且漏不漏取决于模型那批吐了多少字——这种偶发漏是最难查的。
 * 这里的做法是**扣住可能属于号码的尾巴**，等下一块来了再一起判，收尾时把剩下的全部吐出。
 */
public class PiiRedactionAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionAdvisor.class);

    /**
     * 工具循环之外、整通对话只跑一次。比 {@link ToolCallingAdvisor#DEFAULT_ORDER} 小，
     * 也就是排在它前面（链按 order 升序）。
     */
    public static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;

    @Override
    public String getName() {
        return "piiRedactionAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(chain, "chain");
        return redact(chain.nextCall(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(chain, "chain");
        // 状态必须建在 defer 里：它是"这一次订阅"的状态，挂在 Advisor 字段上会被并发订阅互相冲掉
        return Flux.defer(() -> {
            StringBuilder pending = new StringBuilder();
            AtomicReference<ChatClientResponse> lastChunk = new AtomicReference<>();
            return chain.nextStream(request)
                    .concatMap(chunk -> {
                        lastChunk.set(chunk);
                        String text = textOf(chunk);
                        if (text.isEmpty()) {
                            return Flux.just(chunk);
                        }
                        pending.append(text);
                        String safe = PiiRedactor.safePrefix(pending.toString());
                        if (safe.isEmpty()) {
                            return Flux.<ChatClientResponse>empty();
                        }
                        pending.delete(0, safe.length());
                        return emit(chunk, PiiRedactor.mask(safe));
                    })
                    .concatWith(Flux.defer(() -> {
                        String rest = PiiRedactor.mask(pending.toString());
                        ChatClientResponse chunk = lastChunk.get();
                        if (chunk == null || rest.isEmpty()) {
                            return Flux.<ChatClientResponse>empty();
                        }
                        pending.setLength(0);
                        return emit(chunk, rest);
                    }));
        });
    }

    private static Flux<ChatClientResponse> emit(ChatClientResponse chunk, String text) {
        if (text.isEmpty()) {
            return Flux.empty();
        }
        return Flux.just(withText(chunk, text));
    }

    private static ChatClientResponse redact(ChatClientResponse response) {
        String text = textOf(response);
        if (text.isEmpty()) {
            return response;
        }
        int hits = PiiRedactor.countHits(text);
        if (hits > 0) {
            log.debug("出站前 PII 脱敏：命中 {} 处", hits);
        }
        return withText(response, PiiRedactor.mask(text));
    }

    private static String textOf(ChatClientResponse response) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static ChatClientResponse withText(ChatClientResponse response, String text) {
        ChatResponse.Builder builder = ChatResponse.builder();
        if (response.chatResponse() != null) {
            builder.from(response.chatResponse());
        }
        ChatResponse redacted = builder.generations(List.of(new Generation(new AssistantMessage(text)))).build();
        return response.mutate().chatResponse(redacted).build();
    }
}
