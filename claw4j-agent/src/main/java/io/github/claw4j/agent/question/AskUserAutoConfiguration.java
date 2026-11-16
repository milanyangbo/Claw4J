package io.github.claw4j.agent.question;

import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.github.claw4j.domain.question.QuestionGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * 交互提问的装配：一个登记表 + 一个工具。
 *
 * <p>工具名是库的（{@code AskUserQuestionTool}），不是 {@code ask_user}：这一层没有自己写工具，
 * 名字就只能跟着库走，而权限表、档位表、清单断言三处都按这个名字认领。
 *
 * <p>登记表里没有「有没有开关」这件事：开关只有一个（{@code claw4j.tools.ask.enabled}），
 * 关掉就没有工具。留着空的登记表也不会有人调它——加第二个开关只会多出一种"开了但没人问"的组合。
 *
 * <p>为什么这个装配在 agent 而不是能力层：它要 {@code OutboundDelivery}（把问题推到那段对话）与
 * 模型侧的 {@code ToolContext}（通道、会话地址、身份）。前者是 core 的端口、实现在通道模块，
 * 后者只有 Agent 侧有；放能力层就得让能力层认识这两样，而它按 ADR-0010 只该认识 core。
 */
@AutoConfiguration
@EnableConfigurationProperties(AskProperties.class)
public class AskUserAutoConfiguration {

    /** 工具名，取自库的 {@code @Tool(name = …)}。 */
    public static final String TOOL_NAME = "AskUserQuestionTool";

    /**
     * 问一句人话所需的动作名。
     *
     * <p>与 {@code read} 分开：提问不读任何数据，它只决定"这一轮要不要停下来等人"。
     * 给不给这个动作，是一行配置能回答的问题。
     */
    public static final String ACTION = "ask_user";

    /**
     * 进程内的登记表。
     *
     * <p>没有推送面的通道（HTTP）也能用：它的客户端在请求挂着的时候来轮询，所以这里不按
     * "有没有 OutboundDelivery"一刀切，判断在登记表里。
     *
     * @param deliveries 各通道的推送实现
     * @param properties 等待时长
     * @param registry 指标注册表，可能不存在
     * @return 登记表
     */
    @Bean
    @ConditionalOnMissingBean
    public QuestionGate questionGate(ObjectProvider<OutboundDelivery> deliveries,
                                     AskProperties properties,
                                     ObjectProvider<MeterRegistry> registry) {
        return new PendingQuestions(deliveries.orderedStream().toList(), properties.timeout(),
                registry.getIfAvailable());
    }

    /**
     * 工具本身。
     *
     * @param gate 登记表
     * @return 名为 {@link #TOOL_NAME} 的工具回调
     */
    @Bean
    @ConditionalOnProperty(prefix = "claw4j.tools.ask", name = "enabled", matchIfMissing = true)
    public ToolCallback askUserToolCallback(QuestionGate gate) {
        return AskUserTool.callback(gate);
    }

    /**
     * 权限登记：动作 {@code ask_user}，业务表一张都没有。
     *
     * <p>没登记的表现是每次调用都被拒（{@code outcome:denied}），而不是静默放行。
     *
     * @return 这一层的权限表
     */
    @Bean
    public ToolPermissionRegistry askUserPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(TOOL_NAME, ToolPermission.of(ACTION)));
    }

    /**
     * 档位：{@link ToolTier#MINIMAL}。
     *
     * <p>提问不碰文件、不碰表、不出网，只决定这一轮要不要停一下。连"只想让它能聊天"的部署也用得上——
     * 那正是它最常用的场合。
     *
     * @return 这一层的档位表
     */
    @Bean
    public ToolTierRegistry askUserTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(TOOL_NAME), ToolTier.MINIMAL);
    }
}
