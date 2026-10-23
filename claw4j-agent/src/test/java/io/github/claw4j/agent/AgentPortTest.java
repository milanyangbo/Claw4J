package io.github.claw4j.agent;

import io.github.claw4j.agent.model.MockChatModel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentPort 是通道与 Agent 之间唯一的缝，两条路径都必须测到。
 */
class AgentPortTest {

    private final AgentPort port = new ChatClientAgentPort(ChatClient.builder(new MockChatModel()).build());

    private static InboundMessage inbound(String text) {
        return new InboundMessage(Principal.readOnly("u-1"), "session-1", text);
    }

    @Test
    void 同步回答携带同一个会话标识() {
        OutboundMessage out = port.reply(inbound("你好"));

        assertEquals("session-1", out.chatId());
        assertEquals(MockChatModel.PREFIX + "你好", out.text());
        assertTrue(out.finalChunk());
    }

    @Test
    void 流式回答逐块回调并以结尾块收尾() {
        List<OutboundMessage> chunks = new ArrayList<>();

        port.stream(inbound("上周华东区成交额多少"), chunks::add);

        var last = chunks.get(chunks.size() - 1);
        assertTrue(last.finalChunk(), "最后一块必须标记为结尾");
        assertEquals("session-1", last.chatId());
        assertEquals(MockChatModel.PREFIX + "上周华东区成交额多少",
                chunks.stream().map(OutboundMessage::text).reduce("", String::concat));
        assertTrue(chunks.size() > 2, "内容必须切成多块；只剩一块说明流式被降级了");
    }
}
