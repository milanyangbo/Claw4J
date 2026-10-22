package io.github.claw4j.agent;

import io.github.claw4j.agent.model.MockChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配点的最小验证：不联网、不烧 token（ADR-0006）。
 *
 * <p>同步与流式两条路径都测 —— 后面每个自定义 Advisor 都要重复这个双路径验证，
 * 因为只实现同步接口的 Advisor 在流式路径上会被静默跳过（ADR-0007）。
 */
class ChatClientAssemblyTest {

    private final ChatClient chatClient = ChatClient.builder(new MockChatModel()).build();

    @Test
    void 同步路径能拿到回答() {
        String content = chatClient.prompt("上周华东区成交额多少").call().content();

        assertEquals(MockChatModel.PREFIX + "上周华东区成交额多少", content);
    }

    @Test
    void 流式路径同样能拿到完整回答() {
        List<String> chunks = chatClient.prompt("ping")
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(List.of(MockChatModel.PREFIX + "ping"), chunks);
    }

    @Test
    void 装配时带上系统提示词不影响用户消息传递() {
        ChatClient client = ChatClient.builder(new MockChatModel())
                .defaultSystem("你是助手")
                .build();

        String content = client.prompt("你好").call().content();

        assertTrue(content.contains("你好"), "用户消息应当原样送到模型");
    }
}
