package io.github.claw4j.channel.slack.client;

import io.github.claw4j.channel.http.HttpChannelProperties;
import io.github.claw4j.domain.principal.Principal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Slack {@code user id} 到 {@link Principal} 的映射：把"谁是谁"（本通道的登记表）与
 * "角色能看什么"（各通道共用的授权表，{@code claw4j.channel.http.roles}）拼起来。
 *
 * <p>角色名对不上就启动失败。若只是"查不到授权就当没权限"，表现是那群人每次提问都收到
 * "没有权限"，而配置看起来完全正常。
 *
 * <p>键是 {@code U} 开头的 user id，不是 {@code @名字}：显示名会改、能重名，而这里判的是
 * "这个人能看哪些表"，认错人就是把别人的数据给他看。user id 可以从成员资料里取，
 * 也可以从那条"未登记"的 WARN 日志里抄（带着 {@code sender=U...}）。
 */
public final class SlackPrincipalResolver implements Function<String, Optional<Principal>> {

    private final SlackProperties slack;

    private final Map<String, HttpChannelProperties.RoleGrant> grants;

    public SlackPrincipalResolver(SlackProperties slack, HttpChannelProperties authz) {
        this.slack = slack;
        this.grants = authz.getRoles();
        requireEveryRegisteredRoleExists();
    }

    @Override
    public Optional<Principal> apply(String userId) {
        return slack.roleOf(userId).map(role -> {
            HttpChannelProperties.RoleGrant grant = grants.get(role);
            return Principal.of(userId, role, grant.tables(), grant.actions());
        });
    }

    /**
     * Slack 里出现的每个角色名都必须在共享表里有同名定义。缺一个就启动失败，
     * 并把那个角色下登记了几个人一起列出来——"哪个角色写错了"比"某个角色不存在"少一次排查。
     */
    private void requireEveryRegisteredRoleExists() {
        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, SlackProperties.RoleGrant> entry : slack.getRoles().entrySet()) {
            if (!grants.containsKey(entry.getKey())) {
                missing.add(entry.getKey() + "（登记了 " + entry.getValue().users().size() + " 人）");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "claw4j.channel.slack.roles 里的角色在 claw4j.channel.http.roles 里没有同名定义："
                            + String.join("、", missing)
                            + "。Slack 只登记人，授权表共用 HTTP 那一份，角色名必须对得上。");
        }
    }
}
