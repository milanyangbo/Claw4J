package io.github.claw4j.domain.port;

import java.util.Objects;

/**
 * 一条出站消息：Agent 算完之后交回通道的东西。
 *
 * @param chatId     Chat ID，回哪条会话，与 {@link InboundMessage#chatId()} 同源
 * @param text       要发出去的内容
 * @param finalChunk 流式场景里标记这是最后一块，是唯一的正常结束信号（契约见 {@link AgentPort#stream}）
 * @param runId      这一轮的编号（ADR-0027），与 OpenClaw 的 {@code runId} 同名同义：
 *                   它是这一轮运行的句柄，不是一个可以接着续的实体——答完即作废，
 *                   没有状态、也没有"按它接上这一轮"的路。用途只有两个：
 *                   串日志，以及让调用方把同一次回答的前后几块认成一批。
 *                   命令类的回执（{@code /new}、{@code /goal}）没有编号，那是 null。
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
