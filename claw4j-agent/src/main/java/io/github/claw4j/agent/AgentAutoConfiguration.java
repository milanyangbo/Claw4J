package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Agent 的最小装配：一个系统提示词加一条 Advisor 链。
 *
 * <p>Advisor 链是 2.x 的统一抽象，鉴权已在其上（M1-03），脱敏与记忆后续也挂在这里
 * （ADR-0007）。装配点已经固定，后续加 Advisor 只是加一个 bean，不需要改调用方。
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
     * 具体用哪个 ChatModel 由 ModelConfiguration 与 MockModelConfiguration 决定：
     * 有 key 装真实模型，没 key 兜底 mock。这里只管装配，不关心模型从哪来。
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatModel chatModel,
                                 ObjectProvider<Advisor> advisors,
                                 ObjectProvider<ToolCallbackProvider> toolCallbackProviders) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                // 容器里所有 Advisor 按 order 挂上来。脱敏、记忆后续都只是加一个 bean（ADR-0007）。
                // 顺序由各自的 order 决定，这里不排：鉴权要落在工具循环内部，那条约束写在它自己身上。
                .defaultAdvisors(advisors.orderedStream().toList())
                // 工具同理：容器里所有 ToolCallbackProvider 都挂上，这里不点名任何工具类。
                // 没有工具时是空数组，工具循环照常工作（只是无工具可调）。
                .defaultToolCallbacks(toolCallbackProviders.orderedStream().toArray(ToolCallbackProvider[]::new))
                .build();
    }

    /**
     * 鉴权 Advisor：容器里所有 {@link ToolPermissionRegistry} 都由它消费，
     * 也就是"工具需要什么权限"由提供工具的模块自己声明，Agent 不认识任何具体工具。
     * 一个都没声明时它照样在链上，只是每个工具都因"未登记权限"被拒——
     * 失败的姿势是拒绝而不是放行（ADR-0007）。
     */
    @Bean
    @ConditionalOnMissingBean(AuthorizationAdvisor.class)
    public AuthorizationAdvisor authorizationAdvisor(ObjectProvider<ToolPermissionRegistry> registries) {
        return new AuthorizationAdvisor(registries.orderedStream().toList());
    }

    /**
     * Agent 对外的门，通道只认这个类型（ADR-0010）。
     * 注册在这里意味着任何模块只要拿到 AgentPort 就能驱动 Agent，不需要知道 ChatClient 的存在。
     */
    @Bean
    @ConditionalOnMissingBean(AgentPort.class)
    public AgentPort agentPort(ChatClient chatClient) {
        return new ChatClientAgentPort(chatClient);
    }
}
