package io.github.claw4j.model.config;

import io.github.claw4j.model.routing.RoutingChatModel;
import io.github.claw4j.model.usage.MeteredChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把装好的真实模型排成主备，对外只暴露一个 {@code ChatModel}。
 *
 * <p>单独成一个自动配置类是为了顺序：它必须在 {@link ModelConfiguration} 之后，才知道装了几家。
 * 没有 mock 兜底（ADR-0011）：一家都没装上时 {@link ModelConfiguration} 自己就 fail fast。
 *
 * <p>不再有"名字 → bean 名"的适配器表（{@code ADAPTERS}）：协议在 {@code api} 字段里，
 * 供应商数量与代码无关。这里只负责排序与包装。
 *
 * <p>路由做在 ChatModel 层而不是"具名 ChatClient.Builder"：Advisor 链、工具调用只有一份，
 * 挂在 ChatClient 上；分流的实体若是 ChatClient，等于把 Advisor 链复制两份。
 */
@AutoConfiguration(after = ModelConfiguration.class)
public class RoutingModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RoutingModelConfiguration.class);

    public static final String ROUTING_BEAN = "routingChatModel";

    /**
     * 业务侧（ChatClient、AgentPort）按类型注入 {@code ChatModel}，容器里同时有各家模型和这个路由，
     * 必须用 {@code @Primary} 指定谁是被注入的那个。
     *
     * @param properties    模型配置，取 {@code claw4j.model.primary}
     * @param models        已建好的模型，键是供应商 id
     * @param meterRegistry 可选，有才给每条 route 包一层计量
     * @return 主备路由模型
     */
    @Bean(ROUTING_BEAN)
    @Primary
    public RoutingChatModel routingChatModel(ModelProperties properties,
                                             ModelConfiguration.ProviderModels models,
                                             ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        Map<String, ChatModel> available = models.byProvider();
        String primary = properties.getPrimary();
        if (!available.containsKey(primary)) {
            // 拼错的 id 与"id 对但没给钥匙"是两种不同的错，分开说才不用猜。
            throw new IllegalStateException("claw4j.model.primary=" + primary
                    + " 不在 claw4j.model.providers 里，表里配的是 " + available.keySet() + "。");
        }

        List<RoutingChatModel.Route> routes = new ArrayList<>();
        // 每条 route 各包一层计量：route 这个标签就是供应商 id，
        // 于是"降级了几次"= 非主那条 route 的调用数，路由类里不需要再有第二个计数器。
        routes.add(new RoutingChatModel.Route(primary, metered(available.get(primary), primary, registry)));
        available.forEach((id, model) -> {
            if (!id.equals(primary)) {
                routes.add(new RoutingChatModel.Route(id, metered(model, id, registry)));
            }
        });

        log.info("模型路由：主={}，备={}。主模型调用失败时降级到备；流式只在首块之前降级。",
                primary, routes.size() > 1
                        ? routes.subList(1, routes.size()).stream().map(RoutingChatModel.Route::name).toList()
                        : List.of());

        return new RoutingChatModel(routes);
    }

    /**
     * 有 registry 才包。没有 registry 表示没装 actuator——包一层也没地方导出，
     * 只是多一层调用，原样返回。
     *
     * @param model    被包的模型
     * @param route    路由名，作为计量标签
     * @param registry 可能为 null
     * @return 包了计量的模型，或原模型
     */
    private static ChatModel metered(ChatModel model, String route, MeterRegistry registry) {
        return registry == null ? model : new MeteredChatModel(model, route, registry);
    }
}
