package io.github.claw4j.channel.slack.client;

import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;
import java.util.function.Function;

/**
 * Slack {@code user id} → {@link Principal}：在白名单里就放进来。
 *
 * <p>它现在只回答"这个人能不能用这条通道"（ADR-0030 裁决三）：{@code claw4j.channel.slack.users}
 * 里没有他就拒绝，有他就给一个只有 {@code userId} 的身份。键是 {@code U} 开头的 user id，不是
 * {@code @名字}：显示名会改、能重名，而这里是准入判定。user id 可以从成员资料里取，也可以从那条
 * "未登记"的 WARN 日志里抄（带着 {@code sender=U...}）。
 *
 * <p>角色与共用授权表那一层随权限登记表整块下线，这个类因此只剩一次集合判定。
 */
public final class SlackPrincipalResolver implements Function<String, Optional<Principal>> {

    private final SlackProperties slack;

    /**
     * @param slack 本通道的登记表（{@code users} 白名单）
     */
    public SlackPrincipalResolver(SlackProperties slack) {
        this.slack = slack;
    }

    @Override
    public Optional<Principal> apply(String userId) {
        return slack.allows(userId) ? Optional.of(new Principal(userId)) : Optional.empty();
    }
}
