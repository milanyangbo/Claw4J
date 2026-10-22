package io.github.claw4j.agent;

import io.github.claw4j.agent.model.MockChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把"流式路径会静默跳过 Advisor"这个坑钉死在测试里。
 *
 * <p>Spring AI 的 Advisor 链是分路径取的：{@code BaseAdvisorChain} 给流式路径的是
 * {@code getStreamAdvisors()}，只认 {@code StreamAdvisor}。于是一个只实现
 * {@code CallAdvisor} 的 Advisor 在 {@code .stream()} 上**既不报错也不生效**——
 * 鉴权 Advisor 写成这样就等于流式路径完全裸奔，而且从日志上根本看不出来。
 *
 * <p>所以 ADR-0007 要求每个 Advisor 都双实现，并且由 {@code AgentArchitectureTest}
 * 里的 ArchUnit 规则在编译期拦住漏写的。这里的两条测试负责说明为什么要有那条规则。
 */
class AdvisorBothPathsTest {

    private static final String STAMP = "[ADVISOR-RAN]";

    @Test
    void 双实现的Advisor在两条路径上都生效() {
        ChatClient chatClient = ChatClient.builder(new MockChatModel())
                .defaultAdvisors(new BothPathsAdvisor())
                .build();

        String call = chatClient.prompt("ping").call().content();
        List<String> stream = chatClient.prompt("ping").stream().content().collectList().block(Duration.ofSeconds(5));

        assertTrue(call.contains(STAMP), "同步路径应当经过 Advisor");
        assertTrue(String.join("", stream).contains(STAMP), "流式路径同样应当经过 Advisor");
    }

    /**
     * 反面用例：只实现同步接口的 Advisor 在流式路径上被无声丢弃。
     * 这条测试断言的是**当前框架的行为**，不是我们期望的行为——
     * 它存在的意义是：一旦有人删掉 ArchUnit 那条规则，这里会立刻提醒他后果是什么。
     */
    @Test
    void 只实现同步接口的Advisor在流式路径上被静默跳过() {
        ChatClient chatClient = ChatClient.builder(new MockChatModel())
                .defaultAdvisors(new CallOnlyAdvisor())
                .build();

        String call = chatClient.prompt("ping").call().content();
        List<String> stream = chatClient.prompt("ping").stream().content().collectList().block(Duration.ofSeconds(5));

        assertTrue(call.contains(STAMP), "同步路径确实生效");
        assertFalse(String.join("", stream).contains(STAMP), "流式路径被跳过了，且不报错——这正是坑");
        assertTrue(String.join("", stream).contains("ping"), "被跳过后模型照常回答，所以只看输出看不出问题");
    }

    private static ChatClientRequest stamp(ChatClientRequest request) {
        // augmentUserMessage(String) 是替换不是追加，所以走 Function 版本把标记拼上去
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(user -> user.mutate().text(STAMP + user.getText()).build()))
                .build();
    }

    /** 正确写法：{@code BaseAdvisor} 同时覆盖 call 与 stream。 */
    static final class BothPathsAdvisor implements BaseAdvisor {

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            return stamp(request);
        }

        @Override
        public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            return response;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    /** 错误写法：流式路径拿不到它。保留在测试里是为了演示后果，不是范例。 */
    static final class CallOnlyAdvisor implements CallAdvisor {

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(stamp(request));
        }

        @Override
        public String getName() {
            return "callOnly";
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
