package io.github.claw4j.domain.port;

import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站消息的收尾契约：一次流式回答以「最后一块带 finalChunk」结束，没有第二个结束信号。
 *
 * <p>文本是空的仍然要收尾，文本非空也不代表还能继续——判定只看标记。写歪成「文本空了就是说完」，
 * 表现是模型没吐字时通道一直等到超时；写歪成「有文本就不是结束」，最后几个字会被当成还有下文。
 */
class MessageContractTest {

    private static final Principal READER = Principal.readOnly("u-1");

    private static final String CHAT_ID = "http:u-1";

    @Test
    @DisplayName("一次性回答自带结束标记：通道不必再猜什么时候说完")
    void oneShotAnswerIsFinal() {
        assertTrue(OutboundMessage.of(CHAT_ID, "整段回答").finalChunk());
    }

    @Test
    @DisplayName("流式块默认不是结束：标错的话第一块就把回答截断")
    void streamChunkIsNotFinal() {
        assertFalse(OutboundMessage.chunk(CHAT_ID, "第一块").finalChunk());
    }

    @Test
    @DisplayName("结束只看标记不看文本：空回答也要收尾，否则连接吊到超时")
    void emptyTextIsAnEndingOnlyWhenTheFlagSaysSo() {
        OutboundMessage ending = OutboundMessage.of(CHAT_ID, "");

        assertTrue(ending.finalChunk());
        assertTrue(ending.text().isEmpty());
        // 同样一段空文本，作为流式中间块出现时不是结束
        assertFalse(OutboundMessage.chunk(CHAT_ID, "").finalChunk());
    }

    @Test
    @DisplayName("结束块可以带文本：尾巴放在最后一块时不能被当成空收尾丢掉")
    void endingChunkMayCarryText() {
        OutboundMessage ending = OutboundMessage.of(CHAT_ID, "最后几个字");

        assertTrue(ending.finalChunk());
        assertEquals("最后几个字", ending.text());
    }

    @Test
    @DisplayName("通道没给收到时间也要有一个：超时与去重都靠它计时")
    void inboundAlwaysCarriesReceivedAt() {
        InboundMessage message = new InboundMessage(READER, CHAT_ID, "问题");

        assertNotNull(message.receivedAt());
        // HTTP 通道没有事件 id：留空表示「没有」，不编一个出来让幂等误判成重复投递
        assertNull(message.eventId());
    }
}
