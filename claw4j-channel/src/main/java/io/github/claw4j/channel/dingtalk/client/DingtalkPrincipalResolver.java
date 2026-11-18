package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.domain.principal.Principal;

import java.util.Optional;
import java.util.function.Function;

/**
 * 企业内 {@code userId} → {@link Principal}：在白名单里就放进来。
 *
 * <p>它现在只回答"这个人能不能用这条通道"（ADR-0030 裁决三）：{@code claw4j.channel.dingtalk.users}
 * 里没有他就拒绝，有他就给一个只有 {@code userId} 的身份。原来还要按 {@code roles.<角色>} 去共用
 * 授权表里取授权、并在装配期校验角色名对得上——那两件事都随权限登记表整块下线。
 *
 * <p>没有做成"所有通道共用一类解析器"：它们的差别只有键名与报错里那句通道名，而抽出来之后，
 * "某个通道认人失败"在代码里就只剩一个分支。
 */
public final class DingtalkPrincipalResolver implements Function<String, Optional<Principal>> {

    private final DingtalkProperties dingtalk;

    /**
     * @param dingtalk 本通道的登记表（{@code users} 白名单）
     */
    public DingtalkPrincipalResolver(DingtalkProperties dingtalk) {
        this.dingtalk = dingtalk;
    }

    @Override
    public Optional<Principal> apply(String userId) {
        return dingtalk.allows(userId) ? Optional.of(new Principal(userId)) : Optional.empty();
    }
}
