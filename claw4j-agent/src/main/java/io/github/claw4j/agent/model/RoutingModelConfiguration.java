package io.github.claw4j.agent.model;

import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.model.RoutingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把装好的真实模型排成主备，对外只暴露一个 {@code ChatModel}。
 *
 * <p>单独成一个自动配置类是为了顺序：它必须在 {@link ModelConfiguration} 之后
 * （才知道装了几家，才能排主备）。没有 mock 兜底了（ADR-0011）：
 * 一家都没装上时 {@link ModelConfiguration} 自己就会 fail fast，轮不到这里兜。
 *
 * <p>为什么路由做在 ChatModel 层而不是 ADR-0006 里设想的"具名 ChatClient.Builder"：
 * Advisor 链、工具调用这些只有一份，挂在 ChatClient 上；分流的实体若是 ChatClient，
 * 就等于把 Advisor 链复制两份，鉴权与脱敏会各配一次、各错一次。
 * 真正需要"换一家"的时刻是模型调用失败那一刻，那正好是 ChatModel 的职责。
 */
@AutoConfiguration(after = ModelConfiguration.class)
public class RoutingModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RoutingModelConfiguration.class);

    public static final String ROUTING_BEAN = "routingChatModel";

    /**
     * 业务侧（ChatClient、AgentPort）按类型注入 {@code ChatModel}，
     * 容器里现在同时有各家模型和这个路由，必须用 @Primary 指定谁是被注入的那个。
     */
    @Bean(ROUTING_BEAN)
    @Primary
    @Conditional(AnyRealModelCondition.class)
    public RoutingChatModel routingChatModel(ModelProperties properties, ApplicationContext context) {
        Map<String, ChatModel> available = new LinkedHashMap<>();
        putIfPresent(available, context, ModelProperties.DEEPSEEK, ModelConfiguration.DEEPSEEK_BEAN);
        putIfPresent(available, context, ModelProperties.QWEN, ModelConfiguration.QWEN_BEAN);

        String primary = properties.getPrimary();
        if (!primary.equals(ModelProperties.DEEPSEEK) && !primary.equals(ModelProperties.QWEN)) {
            // 拼错的 supplier 名字与"名字对但没给 key"是两种不同的错，分开说才不用猜。
            throw new IllegalStateException("claw4j.model.primary=" + primary + " 不是已知的供应商，"
                    + "可选值为 " + ModelProperties.DEEPSEEK + " / " + ModelProperties.QWEN + "。");
        }
        if (!available.containsKey(primary)) {
            // 主模型那家没配 key 就启动失败，而不是悄悄拿备用的顶上：
            // 悄悄顶上的话，"主模型其实一直没配好"这件事永远不会暴露。
            throw new IllegalStateException("claw4j.model.primary=" + primary + " 没有配置 key，"
                    + "当前已配置 key 的有 " + available.keySet() + "。"
                    + "要让主模型可用，需要配 " + primary.toUpperCase() + "_API_KEY；"
                    + "或者把 primary 改成已配 key 的那一家。");
        }

        List<RoutingChatModel.Route> routes = new ArrayList<>();
        routes.add(new RoutingChatModel.Route(primary, available.get(primary)));
        available.forEach((name, model) -> {
            if (!name.equals(primary)) {
                routes.add(new RoutingChatModel.Route(name, model));
            }
        });

        log.info("模型路由：主={}，备={}。主模型调用失败时降级到备；流式只在首块之前降级。",
                primary, routes.size() > 1
                        ? routes.subList(1, routes.size()).stream().map(RoutingChatModel.Route::name).toList()
                        : List.of());

        return new RoutingChatModel(routes);
    }

    private static void putIfPresent(Map<String, ChatModel> target,
                                     ApplicationContext context,
                                     String name,
                                     String beanName) {
        // 按名字取而不是按类型注入：类型只有一个 ChatModel，届时多家模型都同名同类型，
        // 只能靠"这个 bean 定义有没有被注册"来区分——没注册就是这家没配 key。
        if (context.containsBean(beanName)) {
            target.put(name, context.getBean(beanName, ChatModel.class));
        }
    }

    /**
     * 至少装了一家真实模型才需要路由；一家都没有时这里不注册，
     * 让 {@link MockModelConfiguration} 的 {@code @ConditionalOnMissingBean} 接手。
     *
     * <p>用自定义 Condition 而不是 {@code @ConditionalOnBean}：后者给多个名字时是"全都存在"，
     * 而这里要的是"任一存在"。
     */
    static class AnyRealModelCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean any = context.getBeanFactory() != null
                    && (context.getBeanFactory().containsBeanDefinition(ModelConfiguration.DEEPSEEK_BEAN)
                    || context.getBeanFactory().containsBeanDefinition(ModelConfiguration.QWEN_BEAN));
            return any
                    ? ConditionOutcome.match("至少装配了一家真实模型，需要路由")
                    : ConditionOutcome.noMatch("没有任何真实模型，交给 mock 兜底");
        }
    }
}
