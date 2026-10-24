package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RealModelSupport;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentPort 是通道与 Agent 之间唯一的缝，两条路径都必须测到。
 *
 * <p>这里测的是**包装行为**（会话标识透传、结尾块标记、流式确实分了块），
 * 与模型说什么无关，所以断言不碰具体内容，只要求"非空"——那不是判据，
 * 只是确认这一趟真的走到了模型。真正的判据是 {@code chatId} / {@code finalChunk} /
 * 分块数这三条，它们与措辞无关，也不会因为模型换了说法而失效。
 * （ADR-0011 里"不能只断言非空"针对的是端到端业务链路的用例，不是这一类。）
 */
class AgentPortTest {

    private final AgentPort port =
            new ChatClientAgentPort(ChatClient.builder(RealModelSupport.chatModel()).build());

    private static InboundMessage inbound(String text) {
        return new InboundMessage(Principal.readOnly("u-1"), "session-1", text);
    }

    @Test
    void 同步回答携带同一个会话标识() {
        OutboundMessage out = RealModelSupport.eventually(2, "同步回答非空",
                () -> port.reply(inbound("你好")));

        assertThat(out.chatId()).isEqualTo("session-1");
        assertThat(out.text()).isNotBlank();
        assertThat(out.finalChunk()).isTrue();
    }

    @Test
    void 流式回答逐块回调并以结尾块收尾() {
        List<OutboundMessage> chunks = new ArrayList<>();

        RealModelSupport.eventually(2, "流式回答非空", () -> {
            chunks.clear();
            port.stream(inbound("用一句话介绍自己"), chunks::add);
            assertThat(chunks).isNotEmpty();
            return null;
        });

        var last = chunks.get(chunks.size() - 1);
        assertThat(last.finalChunk()).as("最后一块必须标记为结尾").isTrue();
        assertThat(last.chatId()).isEqualTo("session-1");
        assertThat(chunks.stream().map(OutboundMessage::text).reduce("", String::concat)).isNotBlank();
        assertThat(chunks.size()).isGreaterThan(1);
    }
}
