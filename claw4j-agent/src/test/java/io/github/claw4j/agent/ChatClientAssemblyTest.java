package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RealModelSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配点的最小验证：连真实 DeepSeek（ADR-0011）。
 *
 * <p>同步与流式两条路径都测 —— 后面每个自定义 Advisor 都要重复这个双路径验证，
 * 因为只实现同步接口的 Advisor 在流式路径上会被静默跳过（ADR-0007）。
 *
 * <p>断言只写"拿得到回答"与"真是多块"，不钉内容：真模型每次的措辞都不一样，
 * 钉整句只会让它在措辞变化时假红，而这里要抓的是"拿不到回答""流式被降级成一次性"。
 */
class ChatClientAssemblyTest {

    private final ChatClient chatClient = ChatClient.builder(RealModelSupport.chatModel()).build();

    @Test
    void 同步路径能拿到回答() {
        String content = RealModelSupport.eventually(2, "同步拿到回答",
                () -> chatClient.prompt("只回答一个字：好").call().content());

        assertThat(content).isNotBlank();
    }

    @Test
    void 流式路径逐块返回且块数大于一() {
        List<String> chunks = RealModelSupport.eventually(2, "流式拿到多块", () -> {
            List<String> collected = chatClient.prompt("用一句话介绍自己")
                    .stream()
                    .content()
                    .collectList()
                    .block(RealModelSupport.generousTimeout());
            assertThat(collected).isNotNull();
            assertThat(collected.size()).isGreaterThan(1);
            return collected;
        });

        assertThat(String.join("", chunks)).isNotBlank();
    }

    @Test
    void 装配时带上系统提示词不影响用户消息传递() {
        ChatClient client = ChatClient.builder(RealModelSupport.chatModel())
                .defaultSystem("你是助手，用户说什么你就原样复述那一个词，不要加别的")
                .build();

        String content = RealModelSupport.eventually(2, "系统提示词下仍收到用户消息",
                () -> client.prompt("苹果").call().content());

        assertThat(content).contains("苹果");
    }
}
