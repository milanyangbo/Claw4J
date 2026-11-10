package io.github.claw4j.channel.dingtalk.client;

import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.domain.principal.Principal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 企业内 {@code userId} 到 {@link Principal} 的映射：把"谁是谁"（本通道的登记表）与
 * "角色能看什么"（各通道共用的授权表，{@code claw4j.channel.http.roles}）拼起来。
 *
 * <p>角色名对不上就启动失败。钉钉登记了一个共享表里没有的角色，若只是"查不到授权就当没权限"，
 * 表现是那群人每次提问都收到"没有权限"，而配置看起来完全正常。
 *
 * <p>没有做成"所有通道共用一类解析器"：它们的差别只有键名与报错里那句通道名，
 * 而抽出来之后，"某个通道认人失败"在代码里就只剩一个分支——下次谁想给钉钉加一条
 * "外部联系人不要立刻拒绝"的规则，就得先改一个几家都在用的类。
 */
public final class DingtalkPrincipalResolver implements Function<String, Optional<Principal>> {

    private final DingtalkProperties dingtalk;

    private final Map<String, HttpChannelProperties.RoleGrant> grants;

    public DingtalkPrincipalResolver(DingtalkProperties dingtalk, HttpChannelProperties authz) {
        this.dingtalk = dingtalk;
        this.grants = authz.getRoles();
        requireEveryRegisteredRoleExists();
    }

    @Override
    public Optional<Principal> apply(String userId) {
        return dingtalk.roleOf(userId).map(role -> {
            HttpChannelProperties.RoleGrant grant = grants.get(role);
            return Principal.of(userId, role, grant.tables(), grant.actions());
        });
    }

    /**
     * 钉钉里出现的每个角色名都必须在共享表里有同名定义。缺一个就启动失败，
     * 并把那个角色下登记了几个人一起列出来——"哪个角色写错了"比"某个角色不存在"少一次排查。
     */
    private void requireEveryRegisteredRoleExists() {
        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, DingtalkProperties.RoleGrant> entry : dingtalk.getRoles().entrySet()) {
            if (!grants.containsKey(entry.getKey())) {
                missing.add(entry.getKey() + "（登记了 " + entry.getValue().users().size() + " 人）");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "claw4j.channel.dingtalk.roles 里的角色在 claw4j.channel.http.roles 里没有同名定义："
                            + String.join("、", missing)
                            + "。钉钉只登记人，授权表共用 HTTP 那一份，角色名必须对得上。");
        }
    }
}
