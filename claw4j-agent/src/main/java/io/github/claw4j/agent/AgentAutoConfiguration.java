package io.github.claw4j.agent;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.agent.advisor.PiiRedactionAdvisor;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

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
                                 ObjectProvider<ToolCallback> toolCallbacks,
                                 ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                 ObjectProvider<ObservationRegistry> observationRegistry) {
        ObservationRegistry observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        // 工具循环的 manager 必须显式塞进来：走单参的 ChatClient.builder(chatModel)
        // 拿到的是框架内建的那个，用的是 40/150 的默认值，这里定的预算根本不生效
        // （细节见 ToolCallBudget 的类注释）。
        var toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallBudget.toolCallingManager(observations));
        return ChatClient.builder(chatModel, observations, null, null, toolCallingAdvisor)
                .defaultSystem(SYSTEM_PROMPT)
                // 容器里所有 Advisor 按 order 挂上来。脱敏、记忆后续都只是加一个 bean（ADR-0007）。
                // 顺序由各自的 order 决定，这里不排：鉴权要落在工具循环内部，那条约束写在它自己身上。
                .defaultAdvisors(advisors.orderedStream().toList())
                // 工具同理：容器里所有来源都挂上，这里不点名任何工具类。
                // 没有工具时是空数组，工具循环照常工作（只是无工具可调）。
                .defaultToolCallbacks(collectToolCallbacks(toolCallbacks, toolCallbackProviders))
                .build();
    }

    /**
     * 把容器里所有工具来源收成一个列表。
     *
     * <p><b>两种注册形态都要收</b>：{@code ToolCallbackProvider} 导出"一组工具"，
     * 单个 {@code ToolCallback} 只导出"一个工具"。只收 provider 的那版会让
     * 单工具形态**静默不进清单**——不报错、不告警，只是模型看不见那个工具，
     * 直到有人问"为什么它不知道有这个能力"。
     *
     * <p>委派那一层（{@code SubagentAutoConfiguration}）也用这一个方法
     * 给子 Agent 凑工具集：那里漏收一种形态的表现是"子 Agent 白名单点名了却说没有这个工具"，
     * 是同一个坑的第三次出现。收工具这件事只该有一处实现。
     *
     * <p>顺序不影响行为：工具是按名字被模型和鉴权认领的，清单里谁先谁后都一样。
     * 重名不在这里去重——两个来源导出同名工具是配置错误，该在清单断言上红，
     * 而不是被这里悄悄吞掉一个。
     */
    public static List<ToolCallback> collectToolCallbacks(ObjectProvider<ToolCallback> callbacks,
                                                          ObjectProvider<ToolCallbackProvider> providers) {
        List<ToolCallback> collected = new ArrayList<>();
        callbacks.orderedStream().forEach(collected::add);
        providers.orderedStream()
                .forEach(provider -> collected.addAll(List.of(provider.getToolCallbacks())));
        return List.copyOf(collected);
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
     * 出站脱敏：order 排在工具循环**外面**（见 {@link PiiRedactionAdvisor#ORDER}），
     * 整通对话只在出站前做一次。它不认识任何具体通道，也不改调用方——只是多加一个 bean。
     */
    @Bean
    @ConditionalOnMissingBean(PiiRedactionAdvisor.class)
    public PiiRedactionAdvisor piiRedactionAdvisor() {
        return new PiiRedactionAdvisor();
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
