package io.github.claw4j.agent;

import io.github.claw4j.agent.model.MockChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Agent 的最小装配：一个系统提示词加一条（当前还为空的）Advisor 链。
 *
 * <p>Advisor 链是 2.x 的统一抽象，鉴权、脱敏、记忆后续都挂在这条链上（ADR-0007）。
 * M0 阶段链上还没有成员，但装配点已经固定，后续加 Advisor 不需要改调用方。
 *
 * <p>注册为自动配置而不是显式 @Configuration，是为了让其它模块引入依赖即可生效，
 * 不必要求调用方把 io.github.claw4j.agent 加进组件扫描路径。
 */
@AutoConfiguration
public class AgentAutoConfiguration {

    private static final String SYSTEM_PROMPT = """
            你是 Claw4J，运行在企业 IM 上的业务助手。
            回答业务问题前先确认口径；不确定时向用户提问，不要猜。
            """;

    /**
     * 默认生效，拿到真实 key 后把 claw4j.model.mock 置为 false 即切换。
     * 标记为 @Primary 是为了在真实模型也存在时不产生歧义：测试与本地演示一律走 mock。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "claw4j.model", name = "mock", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(MockChatModel.class)
    public MockChatModel mockChatModel() {
        return new MockChatModel();
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
