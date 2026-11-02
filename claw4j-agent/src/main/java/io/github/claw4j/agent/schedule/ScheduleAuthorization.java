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
 * "这条任务现在还跑得起来吗"——建的时候问一次，每次到点之前再问一次（P05）。
 *
 * <p><b>为什么是一个单独的类，而不是两个调用点各写一遍</b>：这是<b>同一个问题</b>
 * 在两个时刻的样子。分开写的话，两处早晚不一致——最常见的不一致是建的时候查了权限、
 * 到点的时候只查身份，于是"权限被收回但任务照跑"这条缝只在第二次问的时候存在，
 * 而那时已经没人看着了。
 *
 * <p><b>到点为什么必须重算而不是用快照</b>：定时任务的价值在于它比一次对话活得久，
 * 而权限会比任务先变。用快照跑的意思是"他被收权之后，那条任务还带着旧权限跑"——
 * 收权这件事对唯一的无人值守入口失效。
 *
 * <p><b>两种拒绝的含义完全不同，话要说清</b>：身份没了是"这个角色已经不在授权表里"，
 * 要找人把角色加回来；工具没了是"这个角色还能干活，但干不了这一件"，要改那一行配置。
 * 合成一句"没有权限"的话，收到它的人只会去问同一件事。
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
     * 这个身份此刻的权限。
     *
     * @return 空 = 角色已经被删掉（或者这个部署根本没有角色表）。两种都不该继续跑：
     *         猜一个身份去跑，比停在那儿更糟
     */
    public Optional<Principal> currentOwner(String userId, String role) {
        if (roles == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return roles.current(userId, role);
    }

    /**
     * 按这份身份，这条任务要用的工具还都能用吗。
     *
     * <p><b>要审批的工具在这里就被挡下</b>：一条任务每天在自己醒来的时候要求有人点按钮，
     * 结果是每天挂一条没人看见的待批请求，而不是每天办成一件事。这类工具不属于
     * "一次授权、多次执行"的场景——那正是这张票的标题。
     *
     * @param requiredTools 任务自己声明的工具名，可以为空（纯模型回答、不碰工具）
     * @return 空 = 能跑；否则是给模型（建的时候）或给日志与通知（到点的时候）的一句话
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
     * 拒绝话术与鉴权 Advisor 共用同一个前缀：同一件事（角色不允许）在两处被拒，
     * 措辞不该有两种——模型看到两种说法，会以为是两条不同的规矩。
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

    /** 与 Advisor 同一条查法：挨个登记表问，谁有就用谁的。查不到按"没登记"处理。 */
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
