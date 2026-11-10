package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.principal.Principal;

import java.util.Objects;
import java.util.Optional;

/**
 * 角色表的读者：{@code claw4j.channel.http.roles} 到此刻的 {@link Principal}（P05）。
 *
 * <p>放在通道这一侧，是因为角色表是部署配置，而它的两个读者（HTTP 与飞书）都住在这个模块。
 * 放进 agent，Agent 就要认识一个它管不着的配置文件；放进 domain，则把领域绑到了某一种配置源上。
 * 这里只把「谁能读什么」翻译成一个端口，谁读配置是通道的事。
 *
 * <p>与 {@link HttpPrincipalResolver} 不重复：那个回答这一次请求是谁，用户标识来自请求头，
 * 角色可能也来自请求头、由人指定；这里回答一个已知身份此刻还有什么权限，用户标识来自库里
 * 那行任务，角色是创建时记下的那个。同一个查表动作，一个从请求来，一个从库来。
 *
 * <p>角色被删掉就返回空，不抛：那是从现在起不能跑了，不是这个进程坏了。抛出去会让一次查表失败
 * 变成一条无人值守任务的崩溃。
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
