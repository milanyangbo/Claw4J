package io.github.claw4j.domain.port;

import java.util.Objects;

/**
 * 一条出站消息：Agent 算完之后交回通道的东西。
 *
 * @param chatId     Chat ID，回哪条会话，与 {@link InboundMessage#chatId()} 同源
 * @param text       要发出去的内容
 * @param finalChunk 流式场景里标记这是最后一块，是唯一的正常结束信号（契约见 {@link AgentPort#stream}）
 */
public record OutboundMessage(String chatId, String text, boolean finalChunk) {

    public OutboundMessage {
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(text, "text");
    }

    public static OutboundMessage of(String chatId, String text) {
        return new OutboundMessage(chatId, text, true);
    }

    public static OutboundMessage chunk(String chatId, String text) {
        return new OutboundMessage(chatId, text, false);
    }
}
