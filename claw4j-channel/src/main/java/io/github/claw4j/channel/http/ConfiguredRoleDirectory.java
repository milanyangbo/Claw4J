package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;
import java.util.Optional;

/**
 * 角色表的读者：{@code claw4j.channel.http.roles} → 此刻的 {@link Principal}（P05）。
 *
 * <p><b>为什么在通道这一侧</b>：角色表现在是部署配置，而那份配置的两个读者
 * （HTTP 与飞书）都住在这个模块里。放进 agent 的话，Agent 就要认识一个它管不着的配置文件；
 * 放进 domain 则更是把域名绑到了某一种配置源上。这里只是把"谁能读什么"
 * 翻译成一个端口，谁读配置是通道的事。
 *
 * <p><b>与 {@link HttpPrincipalResolver} 的区别不是重复</b>：那个是"这一次请求是谁"——
 * 用户标识来自请求头，角色可能也来自请求头，人工指定；这里是"这个已知身份此刻还有什么权限"——
 * 用户标识来自库里那行任务，角色是创建时记下的那个。同一个动作（查角色表）在两个时刻被问，
 * 一个从请求来、一个从库来。
 *
 * <p><b>角色被删掉就返回空，不抛</b>：那是"从现在起不能跑了"，不是这个进程坏了。
 * 抛出去会让一次查表失败变成一条无人值守任务的崩溃。
 */
public class ConfiguredRoleDirectory implements RoleDirectory {

    private final HttpChannelProperties properties;

    public ConfiguredRoleDirectory(HttpChannelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Optional<Principal> current(String userId, String role) {
        if (userId == null || userId.isBlank() || role == null || role.isBlank()) {
            return Optional.empty();
        }
        HttpChannelProperties.RoleGrant grant = properties.getRoles().get(role);
        if (grant == null) {
            return Optional.empty();
        }
        return Optional.of(Principal.of(userId, role, grant.tables(), grant.actions()));
    }
}
