package io.github.claw4j.model.config;

import io.github.claw4j.model.catalog.ModelCatalog;
import io.github.claw4j.model.catalog.ModelRef;
import io.github.claw4j.model.failover.CooldownStore;
import io.github.claw4j.model.failover.ProbeThrottle;
import io.github.claw4j.model.catalog.ModelRole;
import io.github.claw4j.model.routing.ModelRoles;
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
     * 按角色建链：main 一条，utility 按四态解析（explicit / disabled / auto / 继承）。
     *
     * <p>每条角色链独立排候选，但<b>共享</b>冷却、闸与探测：限流是供应商的事，不因为换了个角色
     * 就不算数。
     *
     * @param catalog       目录
     * @param properties    配置（角色那一段）
     * @param models        已建好的模型
     * @param cooldowns     冷却表
     * @param gate          准入闸
     * @param probes        探测节流
     * @param meterRegistry 可选
     * @return 角色 → 链
     */
    @Bean
    public ModelRoles modelRoles(ModelCatalog catalog,
                                 ModelProperties properties,
                                 ModelConfiguration.ProviderModels models,
                                 CooldownStore cooldowns,
                                 RouteGate gate,
                                 ProbeThrottle probes,
                                 ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        ModelRef mainRef = catalog.primary();
        List<ModelRef> others = catalog.refs().stream().filter(ref -> !ref.equals(mainRef)).toList();

        ModelRouter main = routerFor(ModelRole.main, mainRef, others, catalog, models,
                cooldowns, gate, probes, registry);

        Map<ModelRole, org.springframework.ai.chat.model.ChatModel> byRole = new LinkedHashMap<>();
        byRole.put(ModelRole.main, main);
        // 独立链时它到底指到哪一行：只打「独立链」等于让人回去翻配置。
        ModelRef utilityPrimary = null;

        ModelProperties.RoleSpec spec = properties.getRoles().get(ModelRole.utility.name());
        if (spec == null) {
            // 完全没配：继承 main。这是最常见的一档，"摘要用主模型"在成本上可以接受。
            byRole.put(ModelRole.utility, main);
        }
        else if (spec.disabled()) {
            // 空串 = 关掉。不是继承：静默继承会让"我明明关了"与"它还在花钱"同时成立。
            byRole.put(ModelRole.utility, new ModelRoles.Disabled(ModelRole.utility));
        }
        else if (spec.getPrimary() == null || spec.getPrimary().isBlank()) {
            if (Boolean.TRUE.equals(spec.getAuto())) {
                ModelRef cheapest = cheapestOf(catalog, mainRef.provider());
                utilityPrimary = cheapest;
                byRole.put(ModelRole.utility, routerFor(ModelRole.utility, cheapest, List.of(), catalog,
                        models, cooldowns, gate, probes, registry));
            }
            else {
                byRole.put(ModelRole.utility, main);
            }
        }
        else {
            ModelRef explicit = ModelRef.parse(spec.getPrimary());
            utilityPrimary = explicit;
            List<ModelRef> fallbacks = spec.getFallbacks() == null ? List.of()
                    : spec.getFallbacks().stream().map(ModelRef::parse).toList();
            byRole.put(ModelRole.utility, routerFor(ModelRole.utility, explicit, fallbacks, catalog,
                    models, cooldowns, gate, probes, registry));
        }

        log.info("模型角色：main={}；utility={}", mainRef,
                byRole.get(ModelRole.utility) == main ? "继承 main"
                        : byRole.get(ModelRole.utility) instanceof ModelRoles.Disabled ? "关闭"
                                : "独立链 " + utilityPrimary);
        return new ModelRoles(byRole);
    }

    /**
     * 业务侧（ChatClient、AgentPort）按类型注入 {@code ChatModel}：{@code @Primary} 就是 main 那条链。
     *
     * @param roles 角色表
     * @return main 的候选链
     */
    @Bean(ROUTING_BEAN)
    @Primary
    public org.springframework.ai.chat.model.ChatModel routingChatModel(ModelRoles roles) {
        return roles.forRole(ModelRole.main);
    }

    private ModelRouter routerFor(ModelRole role, ModelRef primaryRef, List<ModelRef> fallbacks,
                                  ModelCatalog catalog, ModelConfiguration.ProviderModels models,
                                  CooldownStore cooldowns, RouteGate gate, ProbeThrottle probes,
                                  MeterRegistry registry) {
        Map<ModelRef, ChatModel> available = models.byRef();
        if (!available.containsKey(primaryRef)) {
            throw new IllegalStateException("角色 " + role + " 的主模型 " + primaryRef + " 没有对应的模型实例，"
                    + "现在建好的有 " + available.keySet() + "。");
        }
        ModelRouter.Candidate requested = candidate(role, primaryRef, ModelRouter.Origin.REQUESTED,
                models, registry);
        List<ModelRouter.Candidate> fallbackCandidates = new ArrayList<>();
        for (ModelRef ref : fallbacks) {
            fallbackCandidates.add(candidate(role, ref, ModelRouter.Origin.CONFIGURED_FALLBACK,
                    models, registry));
        }
        List<ModelRouter.Candidate> chain = ModelRouter.chain(requested, fallbackCandidates, requested);
        return new ModelRouter(chain, capacitiesOf(catalog), endpointsOf(catalog), cooldowns, gate, probes);
    }

    private ModelRouter.Candidate candidate(ModelRole role, ModelRef ref, ModelRouter.Origin origin,
                                            ModelConfiguration.ProviderModels models, MeterRegistry registry) {
        ChatModel model = models.byRef().get(ref);
        if (model == null) {
            throw new IllegalStateException("模型 " + ref + " 没有对应的实例，现在建好的有 "
                    + models.byRef().keySet() + "。");
        }
        return new ModelRouter.Candidate(ref,
                metered(model, ref.toString(), role.name(), registry), origin);
    }

    private static Map<ModelRef, Integer> capacitiesOf(ModelCatalog catalog) {
        Map<ModelRef, Integer> capacities = new LinkedHashMap<>();
        for (ModelRef ref : catalog.refs()) {
            capacities.put(ref, catalog.require(ref).effectiveContextTokens());
        }
        return capacities;
    }

    private static Map<ModelRef, String> endpointsOf(ModelCatalog catalog) {
        // 端点只用于闸的键：目录不知道它，留给 ModelRouter 的构造函数从配置补。
        Map<ModelRef, String> endpoints = new LinkedHashMap<>();
        for (ModelRef ref : catalog.refs()) {
            endpoints.put(ref, ref.provider());
        }
        return endpoints;
    }

    /** 主供应商里最便宜的一行（按 input 单价；没写价格的排最后）。 */
    private static ModelRef cheapestOf(ModelCatalog catalog, String provider) {
        return catalog.refs().stream()
                .filter(ref -> ref.provider().equals(provider))
                .min(java.util.Comparator.comparingDouble(ref -> {
                    var cost = catalog.require(ref).getCost();
                    return cost == null || cost.getInput() == null ? Double.MAX_VALUE : cost.getInput();
                }))
                .orElseThrow(() -> new IllegalStateException("主供应商 " + provider + " 没有可用的模型行"));
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
    private static ChatModel metered(ChatModel model, String route, String role, MeterRegistry registry) {
        return registry == null ? model : new MeteredChatModel(model, route, role, registry);
    }
}
