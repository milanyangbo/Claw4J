package io.github.claw4j.agent.schedule;

import io.github.claw4j.agent.advisor.AuthorizationAdvisor;
import io.github.claw4j.domain.port.RoleDirectory;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 判断一条定时任务此刻还跑不跑得起来：建的时候问一次，每次到点之前再问一次（P05）。
 *
 * <p>两个调用点问的是同一个问题。分开写的话两边迟早不一致，最常见的是建的时候查权限、
 * 到点只查身份，于是权限被收回之后任务照跑，而那时已经没人看着。
 *
 * <p>到点重算而不是复用建时的快照：定时任务比一次对话活得久，权限又比任务变得快。
 * 用快照等于让收权对唯一的无人值守入口失效。
 *
 * <p>两种拒绝的话要分开说。身份没了是角色已不在授权表里，要找人把角色加回来；
 * 工具没了是角色还能干活、但干不了这一件，要改那一行配置。合成一句「没有权限」，
 * 收到的人只会重复问同一件事。
 */
public class ScheduleAuthorization {

    /** 角色表。为 null 表示这个部署没有装（agent 当库使、或者只跑了扫描器那一半）。 */
    private final RoleDirectory roles;

    private final List<ToolPermissionRegistry> registries;

    public ScheduleAuthorization(RoleDirectory roles, List<ToolPermissionRegistry> registries) {
        this.roles = roles;
        this.registries = registries == null ? List.of() : List.copyOf(registries);
    }

    /**
     * 取这个用户在这个角色下此刻的身份。
     *
     * <p>角色已被删除、这个部署没有角色表、{@code userId} 为空，三种都返回空。
     * 空的意思是不该继续跑：猜一个身份去跑，比停在那儿更糟。
     *
     * @param userId 任务主人的用户 id
     * @param role 任务主人建任务时的角色
     * @return 此刻的身份；空表示认不出来
     */
    public Optional<Principal> currentOwner(String userId, String role) {
        if (roles == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return roles.current(userId, role);
    }

    /**
     * 按这份身份判断任务声明的工具现在是否都还能用。
     *
     * <p>要审批的工具在这里就被挡下。一条任务每天在自己醒来的时候要求有人点按钮，
     * 结果是每天挂一条没人看见的待批请求，而不是每天办成一件事：这类工具不属于
     * 「一次授权、多次执行」。
     *
     * @param owner 任务主人此刻的身份
     * @param requiredTools 任务自己声明的工具名，可以为空（纯模型回答、不碰工具）
     * @return 空表示能跑；否则是一句拒绝理由，建的时候交给模型，到点的时候写进日志与通知
     */
    public Optional<String> refusalFor(Principal owner, List<String> requiredTools) {
        Objects.requireNonNull(owner, "owner");
        for (String name : requiredTools == null ? List.<String>of() : requiredTools) {
            Optional<ToolPermission> permission = permissionFor(name);
            if (permission.isEmpty()) {
                return Optional.of("工具 " + name + " 没登记权限（没人评估过它会碰到什么，"
                        + "名字也可能是拼错的）");
            }
            if (permission.get().needsApproval()) {
                return Optional.of("工具 " + name + " 每执行一次都要有人批准，"
                        + "而到点那一刻没有人能点那个按钮");
            }
            if (!owner.canUse(permission.get())) {
                return Optional.of(denialOf(owner, name, permission.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * 拒绝话术与 {@link AuthorizationAdvisor} 共用同一个前缀：同一件事在两处被拒，
     * 措辞不该有两种，否则模型会当成两条不同的规矩。
     */
    private static String denialOf(Principal owner, String toolName, ToolPermission required) {
        if (!owner.canPerform(required.action())) {
            return AuthorizationAdvisor.DENIAL_PREFIX + "：角色 " + owner.role()
                    + " 不允许执行 " + required.action() + " 动作（工具 " + toolName + "）";
        }
        List<String> forbidden = required.tables().stream()
                .filter(table -> !owner.canReadTable(table))
                .sorted()
                .toList();
        return AuthorizationAdvisor.DENIAL_PREFIX + "：角色 " + owner.role()
                + " 不允许访问表 " + String.join("、", forbidden) + "（工具 " + toolName + "）";
    }

    /** 与 Advisor 同一条查法：挨个登记表问，命中即用；都查不到按未登记处理。 */
    private Optional<ToolPermission> permissionFor(String toolName) {
        for (ToolPermissionRegistry registry : registries) {
            Optional<ToolPermission> permission = registry.permissionFor(toolName);
            if (permission.isPresent()) {
                return permission;
            }
        }
        return Optional.empty();
    }
}
