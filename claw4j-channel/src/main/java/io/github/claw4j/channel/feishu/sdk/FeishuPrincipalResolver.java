package io.github.claw4j.channel.feishu.sdk;

import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.domain.principal.Principal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * {@code open_id} → {@link Principal}：把"谁是谁"（本通道的登记表）与"角色能看什么"
 * （两个通道共用的那张授权表）拼起来。
 *
 * <p>为什么单独一个类，而不是 {@link FeishuChannelProperties} 上的方法：properties 只绑定
 * 自己那个前缀，看不到 {@code claw4j.channel.http.roles}。把两份塞进一个 properties 对象，
 * 等于让一个前缀去拥有另一个前缀的键——那正是这次要收掉的形状。
 *
 * <p><b>角色名对不上就启动失败。</b>飞书登记了一个共享表里没有的角色，若只是"查不到授权就当没权限"，
 * 表现是那群人每次提问都收到"没有权限"，而配置看起来完全正常。这种错只能在装配期说清楚。
 */
public final class FeishuPrincipalResolver implements Function<String, Optional<Principal>> {

    private final FeishuChannelProperties feishu;

    private final Map<String, HttpChannelProperties.RoleGrant> grants;

    public FeishuPrincipalResolver(FeishuChannelProperties feishu, HttpChannelProperties authz) {
        this.feishu = feishu;
        this.grants = authz.getRoles();
        requireEveryRegisteredRoleExists();
    }

    @Override
    public Optional<Principal> apply(String openId) {
        return feishu.roleOf(openId).map(role -> {
            HttpChannelProperties.RoleGrant grant = grants.get(role);
            return Principal.of(openId, role, grant.tables(), grant.actions());
        });
    }

    /**
     * 飞书里出现的每个角色名都必须在共享表里有同名定义。缺一个就启动失败，
     * 并把那个角色下登记了几个人一起列出来——"哪个角色写错了"比"某个角色不存在"少一次排查。
     */
    private void requireEveryRegisteredRoleExists() {
        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, FeishuChannelProperties.RoleGrant> entry : feishu.getRoles().entrySet()) {
            if (!grants.containsKey(entry.getKey())) {
                missing.add(entry.getKey() + "（登记了 " + entry.getValue().users().size() + " 人）");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "claw4j.channel.feishu.roles 里的角色在 claw4j.channel.http.roles 里没有同名定义："
                            + String.join("、", missing)
                            + "。飞书只登记人，授权表共用 HTTP 那一份，角色名必须对得上。");
        }
    }
}
