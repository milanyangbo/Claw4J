package io.github.claw4j.channel.wecom.client;

import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;
import java.util.function.Function;

/**
 * 成员 UserID → {@link Principal}：在企业微信的白名单里就放进来。
 *
 * <p>它现在只回答"这个人能不能用这条通道"（ADR-0030 裁决三）：{@code claw4j.channel.wecom.users}
 * 里没有他就拒绝，有他就给一个只有 {@code userId} 的身份。原来还要按 {@code roles.<角色>} 去共用
 * 授权表里取 {@code tables} / {@code actions}，并在这个类里校验"角色名在授权表里存在"——那两件事
 * 都随权限登记表整块下线，于是这个类只剩一次集合判定。
 *
 * <p>与 {@code FeishuPrincipalResolver} 逐条同构：五个通道的差别只有键名与报错里的通道名。
 * 不抽成一个共用类，是因为抽出来之后"企微认人失败"与"飞书认人失败"在代码里就只剩一个分支——
 * 下次谁想给其中一个加规则，就得先改一个几家都在用的类。
 */
public final class WecomPrincipalResolver implements Function<String, Optional<Principal>> {

    private final WecomProperties wecom;

    /**
     * @param wecom 本通道的登记表（{@code users} 白名单）
     */
    public WecomPrincipalResolver(WecomProperties wecom) {
        this.wecom = wecom;
    }

    @Override
    public Optional<Principal> apply(String userId) {
        return wecom.allows(userId) ? Optional.of(new Principal(userId)) : Optional.empty();
    }
}
