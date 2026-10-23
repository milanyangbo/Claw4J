package io.github.claw4j.agent;

import io.github.claw4j.agent.model.MockChatModel;
import io.github.claw4j.agent.model.RealChatModelCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 兜底：没有任何真实模型时装配 mock。
 *
 * <p>单独成一个自动配置类并用 {@code after} 排序，是为了让
 * {@code @ConditionalOnMissingBean} 的判定稳定 —— 它依赖 bean 定义的注册顺序，
 * 写在同一个类里靠方法顺序不够可靠。排在 {@link RoutingModelConfiguration} 之后，
 * 是因为路由 bean 也是 {@code ChatModel}：只要装了任何一家真实模型（不管有没有路由），
 * 这里都不该再插一个 mock 进去。
 */
@AutoConfiguration(after = {ModelConfiguration.class, RoutingModelConfiguration.class})
public class MockModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MockModelConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public MockChatModel mockChatModel() {
        log.warn("未提供 {} 中的任何一个，降级到 mock 模型：回答是回声，不会真的调用大模型。"
                        + "要让本地演示或 CI 稳定走 mock，显式设 CLAW4J_MODEL_MOCK=true。",
                RealChatModelCondition.API_KEY_PROPERTIES);
        return new MockChatModel();
    }
}
