package io.github.claw4j.agent;

import io.github.claw4j.agent.schedule.ScheduleTools;
import io.github.claw4j.domain.principal.ToolPolicyContext;
import io.github.claw4j.domain.principal.ToolPolicyLayer;
import io.github.claw4j.domain.principal.ToolPolicyPipeline;
import io.github.claw4j.domain.principal.ToolTier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把配置与本轮上下文组装成"用哪个画像 + 还要跑哪几层"（ADR-0030 裁决二）。
 *
 * <p>它是纯函数：不读 Spring、不发请求、不碰工具清单。这样"这一轮该生效哪几层"可以被直接问，
 * 而不必起一个容器再从一个日志行里反推。
 *
 * <p><b>两段，别混</b>：
 * <ol>
 *   <li><b>选画像</b>——全局 {@code profile}（可被上游的人设覆盖）→ 被 {@code byProvider.<key>.profile}
 *       覆盖。画像决定"这个工具属不属于这个部署"，它在 {@code ToolProfile#select} 里做，
 *       因为它还要顺带做"每个工具都必须声明画像"那条校验。</li>
 *   <li><b>跑管线</b>——{@code select} 已经把全局 allow / deny / alsoAllow 算完了，所以这里产出的
 *       层**不含全局那层**（含了就是算两遍，审计里会出现两条一样的记录）。</li>
 * </ol>
 *
 * <p><b>没有实现的三层，连同理由</b>（进 ADR 缺口，不在这里造空层）：
 * <ul>
 *   <li>按 agent（第 5 层）、按 agent × 模型（第 6 层）：我们没有"一个进程里装多个 agent"这件事，
 *       人设只覆盖画像，不覆盖 allow / deny。造出来会是一层永远拿不到 agentId 的配置。</li>
 *   <li>按渠道群（第 7 层）：没有渠道群概念，{@code ToolPolicyContext} 的 {@code groupId} 恒为 null。</li>
 *   <li>沙箱层（第 9 层）：沙箱本体在票 06，{@code sandboxed} 恒为 false，这一层由 06 补。</li>
 * </ul>
 */
public final class ToolPolicyAssembler {

    /**
     * 所有子 Agent 都禁的：定时任务。
     *
     * <p>对位 OpenClaw 的 {@code cron}（它那张表里所有子 Agent 都禁 {@code cron}）。子 Agent 是
     * 一次性的执行体，"到点再干一件事"落在它身上没有意义，而任务表是全局的——一个子 Agent
     * 建出来的任务，谁来认领、谁来通知，都不成立。
     */
    private static final Set<String> SUBAGENT_ALWAYS_DENIED = Set.of(
            ScheduleTools.CREATE, ScheduleTools.LIST, ScheduleTools.CANCEL);

    /**
     * {@code leaf} 角色额外禁的：再开一个执行体。
     *
     * <p>对位它的 {@code subagents} / {@code sessions_spawn}。叶子节点不该有"再派一个"的能力，
     * 否则"派活"可以无限套下去，而每一层都要吃一份预算。
     */
    private static final Set<String> LEAF_DENIED = Set.of("Task");

    private ToolPolicyAssembler() {
    }

    /**
     * 所有子 Agent 都拿不到的那几个工具。
     *
     * <p>暴露出来是因为它不是"配置问题"而是"结构问题"：委派链上再建定时任务，那个任务由谁认领、
     * 结果推给谁都不成立。子 Agent 的定义文件里显式点名时可以不减（见
     * {@code SubagentDefinition#selectTools}）。
     *
     * @return 不可变的工具名集合
     */
    public static Set<String> subagentAlwaysDenied() {
        return SUBAGENT_ALWAYS_DENIED;
    }

    /**
     * 这一轮用哪个画像、还要跑哪几层。
     *
     * @param config 工具策略配置
     * @param context 本轮上下文；用 {@link ToolPolicyContext#anonymous()} 得到"只有全局层"
     * @return 画像与层的列表，层按作用顺序排好
     */
    public static Decision decide(ToolsProperties config, ToolPolicyContext context) {
        ToolPolicyContext ctx = context == null ? ToolPolicyContext.anonymous() : context;
        ToolsProperties rules = config == null ? ToolsProperties.defaults() : config;

        ToolsProperties.PolicyRule byProvider = ruleFor(rules.byProvider(), ctx.providerKey());

        // 第 2 层：按模型换画像。它换的是"这个工具属不属于"，不是一张白/黑名单。
        ToolTier profile = rules.profile();
        if (byProvider != null && byProvider.profile() != null) {
            profile = byProvider.profile();
        }

        List<ToolPolicyLayer> layers = new ArrayList<>();
        // 第 4 层：按模型白/黑。全局那层在 ToolProfile#select 里，这里不再重复。
        addIfPresent(layers, layer("byProvider:" + ctx.providerKey(), byProvider));
        // 第 8 层：按发送者。命中即用——不是把候选链上每一条都叠上去。
        addIfPresent(layers, senderLayer(rules, ctx));
        // 第 10 层：子 Agent（内建角色清单 + 配置的收窄）
        addIfPresent(layers, subagentLayer(rules, ctx));
        // 第 11 层：继承。父会话的策略整层带过来，只在它之上继续收窄。
        addIfPresent(layers, ctx.inherited());

        return new Decision(profile, List.copyOf(layers));
    }

    /**
     * 一轮的结论。
     *
     * @param profile 这一轮用哪个画像（可能被 {@code byProvider} 换过）
     * @param layers  在全局那层之后还要跑的层，按作用顺序
     */
    public record Decision(ToolTier profile, List<ToolPolicyLayer> layers) {

        /**
         * 对一份已经过画像与全局筛选的清单继续收窄。
         *
         * @param kept 已经留下的工具名
         * @return 收窄结果
         */
        public ToolPolicyPipeline.Outcome narrow(List<String> kept) {
            return new ToolPolicyPipeline(layers).apply(kept);
        }
    }

    /**
     * 按模型那一层的规则。
     *
     * <p>两种键都合法：{@code provider} 与 {@code provider/model}。**精确的更优先**——同时配了
     * {@code deepseek} 与 {@code deepseek/deepseek-chat} 时，用的是后者。反过来（先查短键）会让
     * "给某个模型单独收紧"这条配置永远不生效，而它看起来是生效的。
     */
    private static ToolsProperties.PolicyRule ruleFor(Map<String, ToolsProperties.PolicyRule> rules,
                                                      String providerKey) {
        if (providerKey == null || providerKey.isBlank() || rules.isEmpty()) {
            return null;
        }
        ToolsProperties.PolicyRule exact = rules.get(providerKey);
        if (exact != null) {
            return exact;
        }
        int slash = providerKey.indexOf('/');
        if (slash > 0) {
            return rules.get(providerKey.substring(0, slash));
        }
        return null;
    }

    /**
     * 按发送者那一层。
     *
     * <p>候选顺序照它的 {@code resolveToolsBySender}：{@code channel:<ch>:<id>} → {@code id:} →
     * {@code *}，**命中即用**——不是把三条合并。合并的话，写了一条 {@code *} 的兜底 deny 之后，
     * 给某个人单独写 allow 就再也放不开了，而那是这个功能最常见的用法。
     */
    private static ToolPolicyLayer senderLayer(ToolsProperties rules, ToolPolicyContext ctx) {
        if (rules.toolsBySender().isEmpty()) {
            return null;
        }
        String sender = ctx.senderId();
        String channel = ctx.channel();
        if (sender != null && !sender.isBlank() && channel != null && !channel.isBlank()) {
            ToolsProperties.PolicyRule exact = rules.toolsBySender().get("channel:" + channel + ":" + sender);
            if (exact != null) {
                return layer("sender:channel:" + channel + ":" + sender, exact);
            }
        }
        if (sender != null && !sender.isBlank()) {
            ToolsProperties.PolicyRule byId = rules.toolsBySender().get("id:" + sender);
            if (byId != null) {
                return layer("sender:id:" + sender, byId);
            }
        }
        ToolsProperties.PolicyRule wildcard = rules.toolsBySender().get("*");
        return wildcard == null ? null : layer("sender:*", wildcard);
    }

    /**
     * 子 Agent 那一层：内建角色清单 + 配置的收窄。
     *
     * <p>**显式 {@code allow} 能压过内建清单**：写了 {@code subagents.allow: [Task]} 的部署，
     * 就该拿到 {@code Task}，即使它的角色是 {@code leaf}。这条是照它的
     * {@code mergeConfiguredSubagentAllow}——那条规则的存在理由是"配置比角色的默认更懂这个部署"。
     * 做成硬拒的话，某些部署永远配不出想要的多层委派，而它们会以为是配置写错了。
     */
    private static ToolPolicyLayer subagentLayer(ToolsProperties rules, ToolPolicyContext ctx) {
        String role = ctx.subagentRole();
        if (role == null || role.isBlank()) {
            return null;
        }
        Set<String> allow = new LinkedHashSet<>(rules.subagents().allow());
        Set<String> deny = new LinkedHashSet<>(SUBAGENT_ALWAYS_DENIED);
        if ("leaf".equals(role)) {
            deny.addAll(LEAF_DENIED);
        }
        deny.addAll(rules.subagents().deny());
        // 显式点名放行的，从内建清单里摘掉。
        deny.removeAll(allow);
        return new ToolPolicyLayer("subagent:" + role, allow, deny, Set.of());
    }

    private static ToolPolicyLayer layer(String label, ToolsProperties.PolicyRule rule) {
        if (rule == null) {
            return null;
        }
        return new ToolPolicyLayer(label,
                new LinkedHashSet<>(rule.allow()), new LinkedHashSet<>(rule.deny()),
                new LinkedHashSet<>(rule.alsoAllow()));
    }

    private static void addIfPresent(List<ToolPolicyLayer> layers, ToolPolicyLayer layer) {
        if (layer != null && !layer.isEmpty()) {
            layers.add(layer);
        }
    }
}
