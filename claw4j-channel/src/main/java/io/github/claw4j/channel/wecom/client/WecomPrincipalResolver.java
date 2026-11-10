package io.github.claw4j.channel.wecom.client;

import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.domain.principal.Principal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 把成员 UserID 映射成 {@link Principal}：把「谁是谁」（本通道的登记表）与
 * 「角色能看什么」（两个通道共用的那张授权表）拼起来。
 *
 * <p>与 {@code FeishuPrincipalResolver} 逐条同构，包括角色名对不上就在构造时失败这一点：
 * 企业微信登记了共享表里没有的角色，若只是
 * 查不到授权就当没权限，表现是那群人每次提问都收到「没有权限」，而配置看起来完全正常。
 *
 * <p>没有抽出一类两个通道共用的解析器：它们的差别只有三个键名与报错里的通道名，
 * 而抽出来之后，「飞书认人失败」与「企微认人失败」在代码里就只剩一个分支。下次谁想给企微
 * 加一条「成员不在通讯录就不要立刻拒绝」的规则，就得先改一个两边都在用的类。
 */
public final class WecomPrincipalResolver implements Function<String, Optional<Principal>> {

    private final WecomProperties wecom;

    private final Map<String, HttpChannelProperties.RoleGrant> grants;

    /**
     * 建一份解析器，同时校验企业微信侧登记的角色名都能在共享表里找到。
     *
     * @param wecom 本通道的登记表
     * @param authz 两个通道共用的授权表
     * @throws IllegalStateException 有角色名在共享表里没有同名定义
     */
    public WecomPrincipalResolver(WecomProperties wecom, HttpChannelProperties authz) {
        this.wecom = wecom;
        this.grants = authz.getRoles();
        requireEveryRegisteredRoleExists();
    }

    @Override
    public Optional<Principal> apply(String userId) {
        return wecom.roleOf(userId).map(role -> {
            HttpChannelProperties.RoleGrant grant = grants.get(role);
            return Principal.of(userId, role, grant.tables(), grant.actions());
        });
    }

    /**
     * 企业微信里出现的每个角色名都必须在共享表里有同名定义。缺一个就启动失败，
     * 并把那个角色下登记了几个人一起列出来，「哪个角色写错了」比「某个角色不存在」少一次排查。
     */
    private void requireEveryRegisteredRoleExists() {
        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, WecomProperties.RoleGrant> entry : wecom.getRoles().entrySet()) {
            if (!grants.containsKey(entry.getKey())) {
                missing.add(entry.getKey() + "（登记了 " + entry.getValue().users().size() + " 人）");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "claw4j.channel.wecom.roles 里的角色在 claw4j.channel.http.roles 里没有同名定义："
                            + String.join("、", missing)
                            + "。企业微信只登记人，授权表共用 HTTP 那一份，角色名必须对得上。");
        }
    }
}
