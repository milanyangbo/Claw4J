package io.github.claw4j.agent;

import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link AgentPort} 的默认实现：把入站消息喂给 ChatClient，把回答包成出站消息。
 *
 * <p>这是整个 Agent 对外唯一的门。通道、测试、MCP 入口都走这里，
 * 于是"换编排方式"这件事被收在模块内部，外部不受影响。
 *
 * <p>{@link #stream} 会阻塞到流结束才返回。调用方（通道）自己决定在哪个线程调用它——
 * 飞书那种「立刻 ACK 再异步回推」的场景就靠这个：通道收到事件后立刻返回，
 * 把 stream 丢给业务线程池，结果再通过主动推送接口发回去（ADR-0009）。
 */
public class ChatClientAgentPort implements AgentPort {

    private final ChatClient chatClient;

    public ChatClientAgentPort(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
    }

    @Override
    public OutboundMessage reply(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        String text = chatClient.prompt(message.text()).call().content();
        return OutboundMessage.of(message.sessionKey(), text);
    }

    @Override
    public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(onChunk, "onChunk");

        chatClient.prompt(message.text())
                .stream()
                .content()
                .doOnNext(chunk -> onChunk.accept(OutboundMessage.chunk(message.sessionKey(), chunk)))
                .doOnComplete(() -> onChunk.accept(OutboundMessage.of(message.sessionKey(), "")))
                .blockLast();
    }
}
