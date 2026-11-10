package io.github.claw4j.domain.principal;

import java.util.Set;

/**
 * 发起请求的人。一次工具调用能不能发生由它决定，不由模型决定（ADR-0007）。
 *
 * <p>它是内置在进程里的极简身份，不是登录系统的产物：本项目不做认证体系，
 * Principal 由通道解析入站消息后交给上层（ADR-0009 的边界）。
 *
 * @param userId 通道侧的用户标识
 * @param role 角色，用于静态映射出权限
 * @param accessibleTables 可访问的表，"*" 表示全部；仅对业务数据工具生效
 * @param allowedActions 可执行的动作名，与工具名对应
 */
public record Principal(String userId, String role, Set<String> accessibleTables, Set<String> allowedActions) {

    /** 通配值，出现在表清单或动作清单里表示"全部"。 */
    public static final String WILDCARD = "*";

    public Principal {
        accessibleTables = Set.copyOf(accessibleTables);
        allowedActions = Set.copyOf(allowedActions);
    }

    /**
     * 用四个字段构造一个身份。
     *
     * @param userId   通道侧的用户标识
     * @param role     角色名
     * @param tables   可访问的表
     * @param actions  可执行的动作名
     * @return 身份
     */
    public static Principal of(String userId, String role, Set<String> tables, Set<String> actions) {
        return new Principal(userId, role, tables, actions);
    }

    /**
     * 只读身份：能看全部表，也能执行读动作，但执行不了任何有副作用的动作。
     *
     * <p>注意它拿到的是 {@link ToolPermission#READ} 而不是空集合：空的动作集意味着
     * "连查数都不行"，那这个身份在业务上就没有用途了，而"只读"的语义本来就是"能读不能写"。
     */
    public static Principal readOnly(String userId) {
        return new Principal(userId, "read-only", Set.of(WILDCARD), Set.of(ToolPermission.READ));
    }

    /**
     * 这张表在不在可访问范围内。
     *
     * @param table 表名
     * @return 可读时为 {@code true}
     */
    public boolean canReadTable(String table) {
        return accessibleTables.contains(WILDCARD) || accessibleTables.contains(table);
    }

    /**
     * 这个动作名在不在可执行范围内。
     *
     * @param action 动作名
     * @return 可执行时为 {@code true}
     */
    public boolean canPerform(String action) {
        return allowedActions.contains(WILDCARD) || allowedActions.contains(action);
    }

    /**
     * 一次工具调用所需的权限是否被授予。鉴权 Advisor 的判定入口（ADR-0007）。
     *
     * @param permission 这次调用要的权限
     * @return 动作与表都满足时为 {@code true}
     */
    public boolean canUse(ToolPermission permission) {
        return permission.grantedTo(this);
    }
}
