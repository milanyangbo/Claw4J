package io.github.claw4j.domain.principal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 一个部署的工具策略：选哪个画像（{@link ToolTier}），外加按工具的 allow / deny / alsoAllow
 * （P09 / ADR-0030 裁决一、裁决二）。
 *
 * <p>它的产出是清单，不是"执行前的拒绝"。切到 {@code messaging} 画像之后 {@code Bash}
 * 根本不在清单里，检索也检索不到，模型不会先计划再撞墙。这与"调了才发现被拒"有本质区别：
 * 后者白烧一轮上下文，而且模型会以为是自己参数写错了，换一种写法再试。
 *
 * <p>三个配置分量的角色不同，别混：
 * <ul>
 *   <li>{@code allow}——<b>只能在画像之内继续收窄</b>。画像里没有的工具写进 allow 不生效，
 *       只打一条告警（{@code widenBlocked}）。让 allow 放宽画像的话，画像就不再是安全默认，
 *       而变成一句"我大致开了这些"。</li>
 *   <li>{@code alsoAllow}——<b>显式放宽，能突破画像</b>。这是照 OpenClaw 的口子：
 *       想"messaging 画像但要跑一条命令"就写 {@code alsoAllow: [Bash]}，
 *       而不是把整个部署调成 coding 再 deny 一大串。只有显式写出来才放宽，这就是
 *       "不再隐式放宽"那条（它在 issue #47487 里专门把隐式行为改成了告警）。</li>
 *   <li>{@code deny}——排除，最后生效，压过前两者。</li>
 * </ul>
 *
 * <p>一条反直觉但照抄：<b>只配 {@code alsoAllow}、不配 {@code allow} 的那一层等于不设限</b>。
 * {@code allow} 为空本来表示"不收窄"，此时再加上 {@code alsoAllow} 如果变成"只放这几个"，
 * 那 writer 的意思明明是"再加几个"，结果把其余全封了。OpenClaw 的处理是补一个 {@code *}
 * （那条 {@code IMPLICIT_ALLOW_ALL_FROM_ALSO_ALLOW}），我们按空 allow 处理，效果一致。
 *
 * <p>三个失败姿势都是启动失败，且都点名：工具没声明画像、同一工具同时在 allow 与 deny 里、
 * 筛完一个都不剩。前两条是配置错误，第三条是"把安全默认配成了没有能力"——一个工具都没有的
 * 部署里，模型连检索工具都看不见，表现出来是"它什么都不肯做"，而那与"它不会"很难区分。
 *
 * @param profile  这个部署选的画像
 * @param allow    白名单，只能在画像之内收窄；空表示不额外收窄
 * @param deny     黑名单；压过 allow 与 alsoAllow
 * @param alsoAllow 显式放宽，能突破画像
 */
public record ToolProfile(ToolTier profile, Set<String> allow, Set<String> deny, Set<String> alsoAllow) {

    public ToolProfile {
        Objects.requireNonNull(profile, "profile");
        allow = normalize(allow);
        deny = normalize(deny);
        alsoAllow = normalize(alsoAllow);
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

    /**
     * 不带 {@code alsoAllow} 的构造：它默认空。
     *
     * @param profile 画像
     * @param allow   白名单
     * @param deny    黑名单
     */
    public ToolProfile(ToolTier profile, Set<String> allow, Set<String> deny) {
        this(profile, allow, deny, Set.of());
    }

    /**
     * 全开：全部画像、不收窄、不排除、不放宽。
     *
     * <p>默认值必须是它，否则升级一次就少一批工具。
     *
     * @return 不做任何收窄的策略
     */
    public static ToolProfile unrestricted() {
        return new ToolProfile(ToolTier.FULL, Set.of(), Set.of(), Set.of());
    }

    /**
     * 按画像与 allow / deny / alsoAllow 筛出一份清单。
     *
     * <p>{@code group:*} 在这里展开（见 {@link ToolGroups}），所以配置里能写
     * {@code deny: [group:plugins]} 一次关掉所有外部工具。
     *
     * @param allToolNames 组装出来的全部工具名（可以有重复：重名是配置错误，该在清单断言上红，
     *                     不在这里悄悄合并）
     * @param registries   各模块声明的画像登记表
     * @return 保留的工具名，以及挡掉的原因
     * @throws IllegalStateException 有工具没声明画像、或筛完一个都不剩
     */
    public Selection select(List<String> allToolNames, List<ToolTierRegistry> registries) {
        Objects.requireNonNull(allToolNames, "allToolNames");
        List<ToolTierRegistry> sources = registries == null ? List.of() : registries;

        // 两份：order 保留原始顺序与重复（重名是配置错误，该在清单断言上红，不在这里合并），
        // manifest 是去重后的名字集，供 group 展开与 unused 判存在用。
        List<String> order = new ArrayList<>();
        Set<String> manifest = new LinkedHashSet<>();
        for (String name : allToolNames) {
            if (name != null && !name.isBlank()) {
                order.add(name);
                manifest.add(name);
            }
        }
        Set<String> external = new LinkedHashSet<>();
        sources.forEach(registry -> external.addAll(registry.externalToolNames()));

        Set<String> allowSet = ToolGroups.expandAll(allow, manifest, external);
        Set<String> denySet = ToolGroups.expandAll(deny, manifest, external);
        Set<String> alsoSet = ToolGroups.expandAll(alsoAllow, manifest, external);

        List<String> kept = new ArrayList<>();
        List<String> byTier = new ArrayList<>();
        List<String> byAllow = new ArrayList<>();
        List<String> byDeny = new ArrayList<>();
        List<String> widenBlocked = new ArrayList<>();
        // 画像之后的 allow / deny 走 {@link ToolPolicyLayer} 的单层语义：那套语义只有一处实现，
        // 管线（ADR-0030 裁决二的其余各层）与这里共用它。两处各写一遍的话，"deny 先判"迟早会
        // 在一边写成"allow 先判"，而那种分叉不会报错，只会让某层安静地少挡一个工具。
        ToolPolicyLayer global = new ToolPolicyLayer("global", allowSet, denySet, alsoSet);

        for (String name : order) {
            // 未声明校验与画像无关，永远要做：full 是"不筛"，不是"不要求声明"。
            // 少了这一句，默认档下所有没登记画像的工具都会静默通过——那正是这套机制要避免的。
            Set<ToolTier> declared = declaredProfiles(name, sources);
            boolean inProfile = profile == ToolTier.FULL
                    || declared.contains(profile)
                    // alsoAllow 是显式放宽：它能突破画像，这是它与 allow 的唯一区别。
                    || alsoSet.contains(name);
            if (!inProfile) {
                byTier.add(name);
                if (allowSet.contains(name)) {
                    // 写进 allow 却被画像挡住——只告警，不放宽。要放宽得用 alsoAllow。
                    widenBlocked.add(name);
                }
                continue;
            }
            String reason = global.reasonFor(name);
            if ("deny".equals(reason)) {
                byDeny.add(name);
            } else if ("not-in-allow".equals(reason)) {
                byAllow.add(name);
            } else {
                kept.add(name);
            }
        }

        if (kept.isEmpty() && !manifest.isEmpty()) {
            throw new IllegalStateException(
                    "claw4j.tools.profile = " + profile + " 把清单筛空了（一共 " + manifest.size()
                            + " 个工具，一个都没留下）。原因分布：画像挡掉 " + byTier.size()
                            + " 个、allow 白名单挡掉 " + byAllow.size() + " 个、deny 排除 " + byDeny.size()
                            + " 个。一个工具都没有的部署里，模型连检索工具都看不见，"
                            + "表现出来是\"它什么都不肯做\"——那与\"它不会\"很难区分。");
        }

        List<String> unused = Stream.of(allowSet, denySet, alsoSet)
                .flatMap(Set::stream)
                .filter(name -> !manifest.contains(name))
                .distinct()
                .sorted()
                .toList();
        return new Selection(List.copyOf(kept), List.copyOf(byTier), List.copyOf(byAllow),
                List.copyOf(byDeny), List.copyOf(widenBlocked), unused);
    }

    /**
     * 一个工具声明的画像集合。查不到就是配置错误，不是"按最宽处理"。
     *
     * <p>多个登记表声明同一个工具时取<b>并集</b>而不是"第一个命中的赢"：顺序依赖会让
     * "某个模块多声明了一个画像"变成靠装配顺序决定的行为。重名本身该在清单断言上红。
     *
     * <p>报错带工具名而不只是"某个工具"：配置化之后一次会有几十个工具，
     * 说不出是哪一个的话，这条校验只会让人把 profile 调回 {@code full} 了事。
     */
    private Set<ToolTier> declaredProfiles(String toolName, List<ToolTierRegistry> registries) {
        Set<ToolTier> declared = new LinkedHashSet<>();
        for (ToolTierRegistry registry : registries) {
            registry.profilesFor(toolName).ifPresent(declared::addAll);
        }
        if (declared.isEmpty()) {
            throw new IllegalStateException(
                    "工具 " + toolName + " 没有声明能力画像：claw4j.tools.profile = " + profile
                            + " 下无法判断它该不该出现。每个工具都必须在提供它的模块里登记 ToolTier"
                            + "（ToolTierRegistry），否则\"它出现在哪个画像\"就是一个没人做过的决定——"
                            + "而默认出现正是这套机制要避免的事。");
        }
        return declared;
    }

    /** 配置里的名字可能带空白（yml 里手写列表），统一收掉；空项直接丢弃，不留一个永远匹配不到的空串。 */
    private static Set<String> normalize(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(cleaned::add);
        return Collections.unmodifiableSet(cleaned);
    }

    /**
     * 一次筛选的结果。
     *
     * <p>挡掉的原因分开记，是为了让启动日志能答出"这个部署是什么画像、挡掉了几个、为什么"——只报
     * 一个总数的话，看到"挡掉 6 个"的人分不清是该调 profile 还是该改 deny。
     *
     * @param kept         留下的工具名，保持输入顺序
     * @param byTier       因为画像里没有它被挡的
     * @param byAllow      因为不在 allow 白名单里被挡的
     * @param byDeny       因为 deny 被排除的
     * @param widenBlocked 写进了 allow、却因为画像里没有它而不生效的。**要放宽得用 alsoAllow**，
     *                     这份清单存在的唯一目的就是把那句话打进启动日志
     * @param unused       配置里写了、但清单里不存在的名字。不一定错：部署裁剪掉某个模块时，针对它
     *                     的 deny 条目自然落空。但拼错也长这样，所以日志要把它列出来
     */
    public record Selection(List<String> kept, List<String> byTier, List<String> byAllow,
                            List<String> byDeny, List<String> widenBlocked, List<String> unused) {

        /** 这次筛选有没有真的挡掉东西。没挡掉时启动日志少打一行，免得天天刷。 */
        public boolean droppedAnything() {
            return !byTier.isEmpty() || !byAllow.isEmpty() || !byDeny.isEmpty();
        }
    }
}
