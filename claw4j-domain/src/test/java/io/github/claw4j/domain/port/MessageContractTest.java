package io.github.claw4j.domain.port;

import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个完整功能：两端消息的契约。
 *
 * <p>这不是"给两个 record 配一份测试"——它守的是一条会让整条流式链路静默坏掉的规则：
 * 通道靠 {@code finalChunk} 收尾，而 {@code finalChunk} 在构造器里是个普通 boolean。
 * 谁把 of 与 chunk 的默认值写反，编译过、单测里的其它断言也过，表现只有一个：
 * 客户端一直等结束信号，或者一句话被当成"还没说完"攒起来。
 */
class MessageContractTest {

    private static final Principal USER = Principal.readOnly("u-1");

    @Test
    @DisplayName("一次性回答是结束块")
    void singleReplyIsTerminal() {
        assertTrue(OutboundMessage.of("c-1", "答案").finalChunk());
    }

    @Test
    @DisplayName("流式中间块不是结束块")
    void streamChunkIsNotTerminal() {
        assertFalse(OutboundMessage.chunk("c-1", "半句").finalChunk());
    }

    @Test
    @DisplayName("一段流式回答有且只有一个结束块，且在最后")
    void streamHasExactlyOneTerminalChunkAtTheEnd() {
        List<OutboundMessage> chunks = new ArrayList<>();
        chunks.add(OutboundMessage.chunk("c-1", "先说"));
        chunks.add(OutboundMessage.chunk("c-1", "再说"));
        chunks.add(OutboundMessage.of("c-1", ""));

        assertEquals(1, chunks.stream().filter(OutboundMessage::finalChunk).count(),
                "结束信号是通道收尾的唯一依据：多一个会提前收尾，少一个就永远不收尾");
        assertTrue(chunks.getLast().finalChunk(), "结束块必须是最后一块");
    }

    @Test
    @DisplayName("不带时间戳的构造用当前时间：超时与去重都靠它")
    void inboundGetsCurrentTimestamp() {
        Instant before = Instant.now();
        InboundMessage message = new InboundMessage(USER, "c-1", "你好");
        Instant after = Instant.now();

        assertNotNull(message.receivedAt());
        assertFalse(message.receivedAt().isBefore(before));
        assertFalse(message.receivedAt().isAfter(after));
        assertNull(message.eventId(), "没有幂等需求时 eventId 可以为空，它不是必填字段");
    }
}
