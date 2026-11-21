package io.github.claw4j.model.config;

import io.github.claw4j.model.catalog.ModelCatalog;
import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.failover.CooldownStore;
import io.github.claw4j.model.failover.ProbeThrottle;
import io.github.claw4j.model.routing.ModelRouter;
import io.github.claw4j.model.routing.RouteGate;
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
import java.util.LinkedHashMap;
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
     * <p>候选链三段：requested（主）→ 其余（按目录顺序）→ 主再兜一次（去重后不会重复调）。
     *
     * @param catalog       目录，给主模型与候选顺序
     * @param models        已建好的模型，键是 {@code provider/model}
     * @param cooldowns     冷却表
     * @param gate          准入闸
     * @param probes        探测节流
     * @param meterRegistry 可选，有才给每条候选包一层计量
     * @return 带候选链的路由
     */
    @Bean(ROUTING_BEAN)
    @Primary
    public ModelRouter routingChatModel(ModelCatalog catalog,
                                        ModelProperties properties,
                                        ModelConfiguration.ProviderModels models,
                                        CooldownStore cooldowns,
                                        RouteGate gate,
                                        ProbeThrottle probes,
                                        ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        Map<ModelRef, ChatModel> available = models.byRef();
        ModelRef primaryRef = catalog.primary();
        if (!available.containsKey(primaryRef)) {
            throw new IllegalStateException("主模型 " + primaryRef + " 没有对应的模型实例，"
                    + "现在建好的有 " + available.keySet() + "。");
        }

        ModelRouter.Candidate primary = new ModelRouter.Candidate(primaryRef,
                metered(available.get(primaryRef), primaryRef.toString(), registry),
                ModelRouter.Origin.REQUESTED);
        List<ModelRouter.Candidate> fallbacks = new ArrayList<>();
        Map<ModelRef, Integer> capacities = new LinkedHashMap<>();
        Map<ModelRef, String> endpoints = new LinkedHashMap<>();
        for (ModelRef ref : catalog.refs()) {
            capacities.put(ref, catalog.require(ref).effectiveContextTokens());
            endpoints.put(ref, properties.require(ref.provider()).getBaseUrl());
            if (!ref.equals(primaryRef)) {
                fallbacks.add(new ModelRouter.Candidate(ref,
                        metered(available.get(ref), ref.toString(), registry),
                        ModelRouter.Origin.CONFIGURED_FALLBACK));
            }
        }

        List<ModelRouter.Candidate> chain = ModelRouter.chain(primary, fallbacks, primary);
        log.info("模型路由：候选={}。主模型调用失败时按顺序换；流式只在首块之前降级。",
                chain.stream().map(candidate -> candidate.ref().toString()).toList());
        return new ModelRouter(chain, capacities, endpoints, cooldowns, gate, probes);
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
