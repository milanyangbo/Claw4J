package io.github.claw4j.agent.question;

import io.github.claw4j.domain.port.OutboundDelivery;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import io.github.claw4j.domain.question.QuestionGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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
 * <p>工具名沿用库的 {@code AskUserQuestionTool}（不是 OpenClaw 那个 {@code ask_user}）：权限表、
 * 档位表、清单断言三处都按这个名字认领，换名字的收益只是"看起来更像"，代价是三处一起改。
 * 工具实现已经不是库的了（票 35：库那份的答案键是题目原文，见 {@link AskUserTool}）。
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

    /** 工具名。只为不打破现有引用而留着——真名叫 {@link AskUserTool#TOOL_NAME}，两处各写一份迟早会分叉。 */
    public static final String TOOL_NAME = AskUserTool.TOOL_NAME;

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
        return MethodToolCallbackProvider.builder()
                .toolObjects(new AskUserTool(gate))
                .build()
                .getToolCallbacks()[0];
    }

    /**
     * 画像：全部（{@link ToolTier#all()}）。
     *
     * <p>提问不碰文件、不碰表、不出网，只决定这一轮要不要停一下。任何画像里少掉它都是一个坏的
     * 部署：模型想确认一件事却问不出来，只能猜，而猜错比停下来问一句贵得多。
     *
     * @return 这一层的画像登记表
     */
    @Bean
    public ToolTierRegistry askUserTierRegistry() {
        return ToolTierRegistry.allProfilesFor(List.of(TOOL_NAME));
    }
}
