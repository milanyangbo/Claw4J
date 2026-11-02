package io.github.claw4j.domain.port;

import java.util.Objects;

/**
 * 一条出站消息：Agent 算完之后交回通道的东西。
 *
 * @param chatId     Chat ID，回哪条会话，与 {@link InboundMessage#chatId()} 同源
 * @param text       要发出去的内容
 * @param finalChunk 流式场景里标记这是最后一块，是唯一的正常结束信号（契约见 {@link AgentPort#stream}）
 * @param runId      这件事对应的 run。可为 null——没装配 run 存储、或这一轮没记下来时就是这样，
 *                   那是"能力不在场"，不是错误。ADR-0016 把它加进来，正是因为它若不在，
 *                   调用方不知道该查哪个 run，"做到第几步了"就无从问起。
 */
public record OutboundMessage(String chatId, String text, boolean finalChunk, String runId) {

    public OutboundMessage {
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(text, "text");
    }

    public static OutboundMessage of(String chatId, String text) {
        return new OutboundMessage(chatId, text, true, null);
    }

    public static OutboundMessage of(String chatId, String text, String runId) {
        return new OutboundMessage(chatId, text, true, runId);
    }

    public static OutboundMessage chunk(String chatId, String text) {
        return new OutboundMessage(chatId, text, false, null);
    }

    public static OutboundMessage chunk(String chatId, String text, String runId) {
        return new OutboundMessage(chatId, text, false, runId);
    }
}
