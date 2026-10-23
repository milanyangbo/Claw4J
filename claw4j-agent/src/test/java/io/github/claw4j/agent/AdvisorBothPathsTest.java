package io.github.claw4j.agent;

import io.github.claw4j.agent.model.RealModelSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
 *
 * <p><b>判据是"Advisor 被调用了几次"，不是"回答里有什么"。</b>
 * 换真模型之前（ADR-0011）这里断言的是回答里带一个标记串——那只有在模型是回声时才成立。
 * 真模型不会复述用户消息，所以改成数 Advisor 自己的调用次数：
 * 既不受措辞影响，也更直接地指向"这条路径有没有经过 Advisor"。
 */
class AdvisorBothPathsTest {

    private static final String STAMP = "[ADVISOR-RAN]";

    @Test
    void 双实现的Advisor在两条路径上都生效() {
        BothPathsAdvisor onCall = new BothPathsAdvisor();
        BothPathsAdvisor onStream = new BothPathsAdvisor();

        RealModelSupport.eventually(2, "同步路径经过 Advisor", () -> {
            ChatClient.builder(RealModelSupport.chatModel()).defaultAdvisors(onCall).build()
                    .prompt("只回答一个字：好").call().content();
            assertThat(onCall.beforeCalls().get()).as("同步路径应当经过 Advisor").isEqualTo(1);
            return null;
        });

        RealModelSupport.eventually(2, "流式路径经过 Advisor", () -> {
            ChatClient.builder(RealModelSupport.chatModel()).defaultAdvisors(onStream).build()
                    .prompt("只回答一个字：好").stream().content().collectList()
                    .block(RealModelSupport.generousTimeout());
            assertThat(onStream.beforeCalls().get()).as("流式路径同样应当经过 Advisor").isEqualTo(1);
            return null;
        });
    }

    /**
     * 反面用例：只实现同步接口的 Advisor 在流式路径上被无声丢弃。
     * 这条测试断言的是**当前框架的行为**，不是我们期望的行为——
     * 它存在的意义是：一旦有人删掉 ArchUnit 那条规则，这里会立刻提醒他后果是什么。
     */
    @Test
    void 只实现同步接口的Advisor在流式路径上被静默跳过() {
        CallOnlyAdvisor advisor = new CallOnlyAdvisor();

        RealModelSupport.eventually(2, "同步路径经过只实现 call 的 Advisor", () -> {
            ChatClient.builder(RealModelSupport.chatModel()).defaultAdvisors(advisor).build()
                    .prompt("只回答一个字：好").call().content();
            assertThat(advisor.calls().get()).as("同步路径确实生效").isEqualTo(1);
            return null;
        });

        CallOnlyAdvisor streamAdvisor = new CallOnlyAdvisor();
        List<String> stream = ChatClient.builder(RealModelSupport.chatModel())
                .defaultAdvisors(streamAdvisor)
                .build()
                .prompt("只回答一个字：好")
                .stream()
                .content()
                .collectList()
                .block(RealModelSupport.generousTimeout());

        assertThat(streamAdvisor.calls().get())
                .as("流式路径把它跳过了，而且不报错——这正是坑")
                .isZero();
        assertThat(String.join("", stream))
                .as("被跳过后模型照常回答，所以只看输出看不出问题")
                .isNotBlank();
    }

    private static ChatClientRequest stamp(ChatClientRequest request) {
        // augmentUserMessage(String) 是替换不是追加，所以走 Function 版本把标记拼上去
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(user -> user.mutate().text(STAMP + user.getText()).build()))
                .build();
    }

    /** 正确写法：{@code BaseAdvisor} 同时覆盖 call 与 stream。 */
    static final class BothPathsAdvisor implements BaseAdvisor {

        private final AtomicInteger beforeCalls = new AtomicInteger();

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            beforeCalls.incrementAndGet();
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

        AtomicInteger beforeCalls() {
            return beforeCalls;
        }
    }

    /** 错误写法：流式路径拿不到它。保留在测试里是为了演示后果，不是范例。 */
    static final class CallOnlyAdvisor implements CallAdvisor {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            calls.incrementAndGet();
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

        AtomicInteger calls() {
            return calls;
        }
    }
}
