package io.github.claw4j.domain.principal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 一个部署的工具策略：允许到哪一档（{@link ToolTier}）、外加按工具的 allow / deny（P09）。
 *
 * <p>它的产出是**清单**，不是"执行前的拒绝"。切到 {@code messaging} 档之后
 * {@code run_script} 根本不在清单里，检索也检索不到——模型不会先计划再撞墙。
 * 这与"调了才发现被拒"有本质区别：后者白烧一轮上下文，而且模型会以为是自己参数写错了，
 * 换一种写法再试。
 *
 * <p><b>allow 只能收窄，不能放宽</b>：它是在档位筛过之后再做白名单。想"用 messaging 档但要一个脚本"
 * 只能调高档位再 deny 别的——否则 allow 就成了绕过档位的口子，而档位正是这个部署的安全默认。
 *
 * <p><b>三个失败姿势都是启动失败，且都点名</b>：工具没声明档位、同一工具同时在 allow 与 deny 里、
 * 筛完一个都不剩。前两条是配置错误，第三条是"把安全默认配成了没有能力"——
 * 一个工具都没有的部署里，模型连检索工具都看不见，表现出来是"它什么都不肯做"，
 * 而那与"它不会"很难区分。
 *
 * @param tier  这个部署允许到哪一档
 * @param allow 白名单；空表示不额外收窄
 * @param deny  黑名单；在档位与白名单之后生效
 */
public record ToolProfile(ToolTier tier, Set<String> allow, Set<String> deny) {

    public ToolProfile {
        Objects.requireNonNull(tier, "tier");
        allow = normalize(allow);
        deny = normalize(deny);
        // 冲突在构造期就炸，不等 select：没有装任何工具的部署（只引 core 的测试、裁剪过的服务）
        // 永远不会走到 select 那句校验，而配置写错的代价应该是"起不来"，不是"在某些部署里静默无效"。
        Set<String> both = new LinkedHashSet<>(allow);
        both.retainAll(deny);
        if (!both.isEmpty()) {
            throw new IllegalStateException(
                    "工具 " + String.join("、", both) + " 同时出现在 claw4j.tools.allow 与 claw4j.tools.deny 里。"
                            + "两个列表是收窄与排除两条相反的意图，同一件东西同时写在两边时，"
                            + "无论按哪一边生效都会让另一边的作者以为自己的配置没被读到。");
        }
    }

    /** 今天的行为：全部档位、不收窄、不排除。默认值必须是它，否则升级一次就少一批工具。 */
    public static ToolProfile unrestricted() {
        return new ToolProfile(ToolTier.FULL, Set.of(), Set.of());
    }

    /**
     * 按档位与 allow / deny 筛出一份清单。
     *
     * @param allToolNames 组装出来的全部工具名（可以有重复：重名是配置错误，该在清单断言上红，
     *                     不在这里悄悄合并）
     * @param registries   各模块声明的档位登记表
     * @return 保留的工具名，以及挡掉的原因与被配置提到却不存在的名字（后者用于日志告警）
     * @throws IllegalStateException 有工具没声明档位、或筛完一个都不剩
     */
    public Selection select(List<String> allToolNames, List<ToolTierRegistry> registries) {
        Objects.requireNonNull(allToolNames, "allToolNames");
        List<ToolTierRegistry> sources = registries == null ? List.of() : registries;
        List<String> kept = new ArrayList<>();
        List<String> byTier = new ArrayList<>();
        List<String> byAllow = new ArrayList<>();
        List<String> byDeny = new ArrayList<>();
        Set<String> manifest = new LinkedHashSet<>();

        for (String name : allToolNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            manifest.add(name);
            ToolTier required = requiredTier(name, sources);
            if (!tier.allows(required)) {
                byTier.add(name);
            } else if (!allow.isEmpty() && !allow.contains(name)) {
                byAllow.add(name);
            } else if (deny.contains(name)) {
                byDeny.add(name);
            } else {
                kept.add(name);
            }
        }

        if (kept.isEmpty() && !manifest.isEmpty()) {
            throw new IllegalStateException(
                    "claw4j.tools.profile = " + tier + " 把清单筛空了（一共 " + manifest.size()
                            + " 个工具，一个都没留下）。原因分布：档位挡掉 " + byTier.size()
                            + " 个、allow 白名单挡掉 " + byAllow.size() + " 个、deny 排除 " + byDeny.size()
                            + " 个。一个工具都没有的部署里，模型连检索工具都看不见，"
                            + "表现出来是\"它什么都不肯做\"——那与\"它不会\"很难区分。");
        }

        List<String> unused = Stream.concat(allow.stream(), deny.stream())
                .filter(name -> !manifest.contains(name))
                .distinct()
                .sorted()
                .toList();
        return new Selection(List.copyOf(kept), List.copyOf(byTier), List.copyOf(byAllow),
                List.copyOf(byDeny), unused);
    }

    /**
     * 一个工具的档位。查不到就是配置错误，不是"按最宽处理"。
     *
     * <p>报错带工具名而不只是"某个工具"：配置化之后一次会有几十个工具，
     * 说不出是哪一个的话，这条校验只会让人把 profile 调回 {@code full} 了事。
     */
    private ToolTier requiredTier(String toolName, List<ToolTierRegistry> registries) {
        for (ToolTierRegistry registry : registries) {
            var tierOfTool = registry.tierFor(toolName);
            if (tierOfTool.isPresent()) {
                return tierOfTool.get();
            }
        }
        throw new IllegalStateException(
                "工具 " + toolName + " 没有声明能力档位：claw4j.tools.profile = " + tier
                        + " 下无法判断它该不该出现。每个工具都必须在提供它的模块里登记 ToolTier"
                        + "（ToolTierRegistry），否则\"它出现在哪个档\"就是一个没人做过的决定——"
                        + "而默认出现正是这套机制要避免的事。");
    }

    /** 配置里的名字可能带空白（yml 里手写列表），统一收掉；空项直接丢弃，不留一个永远匹配不到的空串。 */
    private static Set<String> normalize(Set<String> names) {
        if (names == null) {
            return Set.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(cleaned::add);
        return Set.copyOf(cleaned);
    }

    /**
     * 一次筛选的结果。
     *
     * <p>挡掉的原因分开记，是为了让启动日志能答出"这个部署是什么档、挡掉了几个、为什么"——
     * 只报一个总数的话，看到"挡掉 6 个"的人分不清是该调 profile 还是该改 deny。
     *
     * @param kept     留下的工具名，保持输入顺序
     * @param byTier   因为档位不够被挡的
     * @param byAllow  因为不在 allow 白名单里被挡的
     * @param byDeny   因为 deny 被排除的
     * @param unused   配置里写了、但清单里不存在的名字。**不一定错**：部署裁剪掉某个模块时，
     *                 针对它的 deny 条目自然落空。但拼错也长这样，所以日志要把它列出来
     */
    public record Selection(List<String> kept, List<String> byTier, List<String> byAllow,
                            List<String> byDeny, List<String> unused) {

        /** 这次筛选有没有真的挡掉东西。没挡掉时启动日志少打一行，免得天天刷。 */
        public boolean droppedAnything() {
            return !byTier.isEmpty() || !byAllow.isEmpty() || !byDeny.isEmpty();
        }
    }
}
