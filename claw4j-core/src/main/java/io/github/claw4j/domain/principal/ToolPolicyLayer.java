package io.github.claw4j.domain.principal;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 策略管线的**一层**：一个标签加三张表（allow / deny / alsoAllow）（ADR-0030 裁决二）。
 *
 * <p>语义照 OpenClaw 的 {@code makeToolPolicyMatcher}，只有三条：
 * <ol>
 *   <li><b>deny 先判</b>：命中即拒，allow 管不到它。反过来写（先 allow）会让
 *       "allow 里有、deny 里也有"变成 allow 赢，而两个相反的意图同时写着时应该卡住而不是放行。</li>
 *   <li><b>allow 为空则全放</b>：空表示这一层不收窄。这条容易做错成"空 = 什么都不放"，
 *       那会让任何一层漏配变成全封，而漏配是最常见的情况。</li>
 *   <li><b>allow 非空则只有命中的才放</b>：这一层是一张白名单，其余全封。</li>
 * </ol>
 *
 * <p>{@code alsoAllow} 与同层的 {@code allow} 合并使用（它的 {@code unionAllow}）：它只影响
 * "allow 非空时哪些能进"，不改变"allow 为空则不收窄"这条。所以把 {@code alsoAllow} 单独写成
 * 一个层是错的——那样它会变成"只放这几个"，与 writer 的"再加几个"相反。
 *
 * <p><b>`applyPatch` 的别名不对称没有照抄</b>：它的规则是"allow 含 {@code write} 时
 * {@code apply_patch} 一并放行，但 {@code deny: ["write"]} 不拦 {@code apply_patch}"。我们没有
 * {@code apply_patch}（{@code Write} 与 {@code Edit} 本来就是两个独立的工具名，不需要别名），
 * 照抄会得到一条永远不生效的规则。见 ADR 缺口。
 *
 * @param label     这一层的名字，进审计与日志（如 {@code sender:id:alice}）
 * @param allow     白名单；空表示这一层不收窄
 * @param deny      黑名单；先判，压过 allow 与 alsoAllow
 * @param alsoAllow 与 allow 合并的显式放宽
 */
public record ToolPolicyLayer(String label, Set<String> allow, Set<String> deny, Set<String> alsoAllow) {

    public ToolPolicyLayer {
        Objects.requireNonNull(label, "label");
        allow = normalize(allow);
        deny = normalize(deny);
        alsoAllow = normalize(alsoAllow);
    }

    /**
     * 只有黑名单的一层。
     *
     * @param label 层标签
     * @param deny  要排除的工具名
     * @return 这一层
     */
    public static ToolPolicyLayer denying(String label, Set<String> deny) {
        return new ToolPolicyLayer(label, Set.of(), deny, Set.of());
    }

    /**
     * 只有白名单的一层。
     *
     * @param label 层标签
     * @param allow 要放行的工具名
     * @return 这一层
     */
    public static ToolPolicyLayer allowing(String label, Set<String> allow) {
        return new ToolPolicyLayer(label, allow, Set.of(), Set.of());
    }

    /** 三张表都空：这一层不做事。 */
    public boolean isEmpty() {
        return allow.isEmpty() && deny.isEmpty() && alsoAllow.isEmpty();
    }

    /**
     * 这一层放不放这个工具。
     *
     * @param toolName 工具名
     * @return 放行为 {@code true}
     */
    public boolean permits(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (deny.contains(toolName)) {
            return false;
        }
        if (allow.isEmpty()) {
            return true;
        }
        return allow.contains(toolName) || alsoAllow.contains(toolName);
    }

    /**
     * 不生效的原因：给审计用的一句话。
     *
     * @param toolName 被挡的工具名
     * @return {@code "deny"} / {@code "not-in-allow"}，没挡时为 {@code null}
     */
    public String reasonFor(String toolName) {
        if (deny.contains(toolName)) {
            return "deny";
        }
        if (!allow.isEmpty() && !allow.contains(toolName) && !alsoAllow.contains(toolName)) {
            return "not-in-allow";
        }
        return null;
    }

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
        return Set.copyOf(cleaned);
    }
}
