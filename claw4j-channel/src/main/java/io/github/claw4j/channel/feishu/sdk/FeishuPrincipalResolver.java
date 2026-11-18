package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;
import java.util.function.Function;

/**
 * {@code open_id} → {@link Principal}：在飞书的白名单里就放进来。
 *
 * <p>它现在只回答"这个人能不能用这条通道"（ADR-0030 裁决三）：{@code claw4j.channel.feishu.users}
 * 里没有他就拒绝，有他就给一个只有 {@code userId} 的身份。原来还要按 {@code roles.<角色>} 去
 * {@code claw4j.channel.http.roles} 取 {@code tables} / {@code actions}，并在装配期校验角色名
 * 对得上——那两件事都随权限登记表整块下线。
 */
public final class FeishuPrincipalResolver implements Function<String, Optional<Principal>> {

    private final FeishuChannelProperties feishu;

    /**
     * @param feishu 本通道的登记表（{@code users} 白名单）
     */
    public FeishuPrincipalResolver(FeishuChannelProperties feishu) {
        this.feishu = feishu;
    }

    @Override
    public Optional<Principal> apply(String openId) {
        return feishu.allows(openId) ? Optional.of(new Principal(openId)) : Optional.empty();
    }
}
