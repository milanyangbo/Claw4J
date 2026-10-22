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

    public static final String WILDCARD = "*";

    public Principal {
        accessibleTables = Set.copyOf(accessibleTables);
        allowedActions = Set.copyOf(allowedActions);
    }

    public static Principal of(String userId, String role, Set<String> tables, Set<String> actions) {
        return new Principal(userId, role, tables, actions);
    }

    /** 只读身份：能看全部表，但不能执行任何有副作用的动作。 */
    public static Principal readOnly(String userId) {
        return new Principal(userId, "read-only", Set.of(WILDCARD), Set.of());
    }

    public boolean canReadTable(String table) {
        return accessibleTables.contains(WILDCARD) || accessibleTables.contains(table);
    }

    public boolean canPerform(String action) {
        return allowedActions.contains(WILDCARD) || allowedActions.contains(action);
    }
}
