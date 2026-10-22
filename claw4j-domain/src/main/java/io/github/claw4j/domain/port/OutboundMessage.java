package io.github.claw4j.domain.port;

import java.util.Objects;

/**
 * 一条出站消息：Agent 算完之后交回通道的东西。
 *
 * @param sessionKey 回哪条会话，与 {@link InboundMessage#sessionKey()} 同源
 * @param text       要发出去的内容
 * @param finalChunk 流式场景里标记这是最后一块。通道据此决定何时结束 SSE 或关闭一次主动推送
 */
public record OutboundMessage(String sessionKey, String text, boolean finalChunk) {

    public OutboundMessage {
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(text, "text");
    }

    public static OutboundMessage of(String sessionKey, String text) {
        return new OutboundMessage(sessionKey, text, true);
    }

    public static OutboundMessage chunk(String sessionKey, String text) {
        return new OutboundMessage(sessionKey, text, false);
    }
}
