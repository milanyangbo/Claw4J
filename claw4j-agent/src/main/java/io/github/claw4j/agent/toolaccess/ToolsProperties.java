package io.github.claw4j.agent.toolaccess;

import io.github.claw4j.domain.principal.ToolProfile;
import io.github.claw4j.domain.principal.ToolTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 工具策略：这套部署选哪个画像，以及逐层的 allow / deny / alsoAllow（P09 / ADR-0030 裁决二）。
 *
 * <p>默认是 {@code full} 加几张空表。升级一次悄悄少一批工具，是这类开关最容易付的代价，
 * 而且通常要等到有人问「它怎么不会查数了」才会发现。
 *
 * <p>四个全局分量各解决一件事，合并不了：
 * <ul>
 *   <li>{@code profile} 是部署决定，改一次要重启；</li>
 *   <li>{@code allow} 是白名单，<b>只能在画像之内</b>继续收窄；</li>
 *   <li>{@code alsoAllow} 是显式放宽，<b>能突破画像</b>——这是它与 allow 的唯一区别；</li>
 *   <li>{@code deny} 是排除，压过前两者。</li>
 * </ul>
 *
 * <p>四个分量都接受 {@code group:*} 简写（{@code group:fs} / {@code group:runtime} /
 * {@code group:web} / {@code group:agents} / {@code group:plugins} / {@code group:openclaw}）。
 *
 * <p>按维度的那几层见 {@code ToolPolicyAssembler}：它们回答的是"这一轮谁在问、用的哪个模型"，
 * 每一层只能在上面的结果上继续收窄。<b>顺序有意义</b>，而顺序写在那个类里，不写在这里。
 *
 * <p><b>键的形状</b>（yaml 里含 {@code :} 的键要加引号，否则解析成嵌套映射）：
 * <pre>{@code
 * claw4j:
 *   tools:
 *     byProvider:
 *       deepseek:                 # 整个 provider
 *       deepseek/deepseek-chat:   # 某一个模型
 *         profile: messaging      # 按模型换画像（第 2 层）
 *         deny: [group:runtime]
 *     toolsBySender:
 *       "id:alice":               # 先看 id
 *       "channel:feishu:bob":     # 通道 + id 的写法更精确，先命中
 *         allow: [querySales]
 *       "*":                      # 兜底
 *         deny: [group:runtime]
 * }</pre>
 *
 * @param profile     选哪个画像，缺省 {@link ToolTier#FULL}
 * @param allow       白名单，只能在画像之内收窄；空表示不额外收窄
 * @param deny        黑名单；压过 allow 与 alsoAllow
 * @param alsoAllow   显式放宽，能突破画像
 * @param byProvider  按模型（第 2、4 层）。键是 {@code provider} 或 {@code provider/model}
 * @param toolsBySender 按发送者（第 8 层）。键是 {@code id:<userId>} / {@code channel:<ch>:<userId>} / {@code *}
 * @param subagents   子 Agent 那一层（第 10 层）
 */
// 只能有<b>一个</b>构造器：{@code @ConfigurationProperties} 的 record 绑定靠它找 canonical constructor，
// 多一个之后 Spring 会去找无参构造，启动时报 "No default constructor found"。按维度的层传 null 即可。
@ConfigurationProperties("claw4j.tools")
public record ToolsProperties(ToolTier profile,
                              List<String> allow,
                              List<String> deny,
                              List<String> alsoAllow,
                              Map<String, PolicyRule> byProvider,
                              Map<String, PolicyRule> toolsBySender,
                              SubagentTools subagents) {

    public ToolsProperties {
        profile = profile == null ? ToolTier.FULL : profile;
        allow = copy(allow);
        deny = copy(deny);
        alsoAllow = copy(alsoAllow);
        byProvider = copyRules(byProvider);
        toolsBySender = copyRules(toolsBySender);
        subagents = subagents == null ? SubagentTools.NONE : subagents;
    }

    /**
     * 全默认：全部画像、不收窄、不排除、不放宽、没有按维度的层。
     *
     * @return 默认策略
     */
    public static ToolsProperties defaults() {
        return new ToolsProperties(null, null, null, null, null, null, null);
    }

    /**
     * 变成领域对象：全局那四样。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code Set.copyOf}：后者不保证顺序，而启动日志里
     * 「挡掉了哪几个」按配置顺序读起来更省事，排查时也更容易对上 yml。
     *
     * @return 对应的 {@link ToolProfile}
     */
    public ToolProfile toProfile() {
        return new ToolProfile(profile, new LinkedHashSet<>(allow), new LinkedHashSet<>(deny),
                new LinkedHashSet<>(alsoAllow));
    }

    /**
     * 一层策略的配置：画像可选（只有按模型那一层用它），三张表的含义与全局一致。
     *
     * @param profile   这一层换成哪个画像；{@code null} 表示不换
     * @param allow     白名单
     * @param deny      黑名单
     * @param alsoAllow 显式放宽
     */
    public record PolicyRule(ToolTier profile, List<String> allow, List<String> deny, List<String> alsoAllow) {

        public PolicyRule {
            allow = copy(allow);
            deny = copy(deny);
            alsoAllow = copy(alsoAllow);
        }
    }

    /**
     * 子 Agent 那一层：配置的白/黑名单。
     *
     * <p>角色带来的固定拒绝清单<b>不在这里</b>——它是内建的一条规则（见
     * {@code ToolPolicyAssembler}），配置只负责在上面加自己的收窄。
     *
     * @param allow 白名单；空表示不加这一层
     * @param deny  黑名单
     */
    public record SubagentTools(List<String> allow, List<String> deny) {

        /** 没配时的那一份：两层都不加。 */
        public static final SubagentTools NONE = new SubagentTools(List.of(), List.of());

        public SubagentTools {
            allow = copy(allow);
            deny = copy(deny);
        }
    }

    private static List<String> copy(List<String> names) {
        return names == null ? List.of() : List.copyOf(names);
    }

    private static Map<String, PolicyRule> copyRules(Map<String, PolicyRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Map.of();
        }
        Map<String, PolicyRule> cleaned = new LinkedHashMap<>();
        rules.forEach((key, rule) -> {
            if (key != null && !key.isBlank() && rule != null) {
                cleaned.put(key.trim(), rule);
            }
        });
        return Map.copyOf(cleaned);
    }
}
