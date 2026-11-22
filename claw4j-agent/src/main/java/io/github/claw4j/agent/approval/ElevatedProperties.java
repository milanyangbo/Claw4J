package io.github.claw4j.agent.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 提权的配置，键前缀 {@code claw4j.tools.elevated}（ADR-0030 裁决六）。
 *
 * <p>默认<b>关</b>。这一档能把命令从沙箱里放出来，还能跳过审批，所以它的默认值只能是"不许"，
 * 而不是"看情况"。要开就得在部署里写两样：{@code enabled: true} 与 {@code allow-from} 里
 * 至少一个发送者。
 *
 * <p>为什么 {@code allow-from} 是必需而不是可选：{@code /elevated full} 的效果是"这一段
 * 不再问人"。如果所有能发消息的人都发得出来，那它等于把审批整层关掉——而那层正是
 * ADR-0030 的核心。所以名单为空时<b>谁都不许</b>，不是"谁都许"。
 *
 * @param enabled   开不开这一族命令；默认关
 * @param allowFrom 允许使用的发送者（{@code userId}）；空表示谁都不许
 * @param ttl       一次提权保持多久；到点自动回到 {@code off}，默认 {@value #DEFAULT_TTL_MINUTES} 分钟
 */
@ConfigurationProperties("claw4j.tools.elevated")
public record ElevatedProperties(Boolean enabled, List<String> allowFrom, Duration ttl) {

    /** 默认有效期。给一个有限的时长是这条命令的一半安全性：忘了关不会变成永久。 */
    public static final long DEFAULT_TTL_MINUTES = 30;

    public ElevatedProperties {
        enabled = enabled != null && enabled;
        allowFrom = allowFrom == null ? List.of() : List.copyOf(allowFrom);
        ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(DEFAULT_TTL_MINUTES) : ttl;
    }

    /**
     * 什么都不开的那一份。
     *
     * @return 默认配置
     */
    public static ElevatedProperties defaults() {
        return new ElevatedProperties(null, null, null);
    }

    /**
     * 这个人能不能用这一族命令。
     *
     * <p>两件事都要成立：部署开着，且他在名单里。名单为空时返回 {@code false}——
     * "没配"在权限这件事上只能是"不许"。
     *
     * @param userId 发送者标识，可以为 {@code null}
     * @return 允许为 {@code true}
     */
    public boolean allows(String userId) {
        return enabled && userId != null && !userId.isBlank() && allowFrom.contains(userId);
    }
}
