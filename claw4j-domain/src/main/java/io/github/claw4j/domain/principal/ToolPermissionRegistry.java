package io.github.claw4j.domain.principal;

import java.util.Map;
import java.util.Optional;

/**
 * 工具名 → 权限的登记表。由**提供工具的模块**实现，由 Agent 侧的鉴权 Advisor 消费。
 *
 * <p>为什么登记表跟着工具走而不是跟着 Agent 走：只有工具自己知道它要去碰哪张表。
 * Agent 若自己维护这份映射，就等于把"工具的实现细节"抄了一份在别处，
 * 工具改了 SQL 而映射没改，鉴权就在保护一个已经不存在的边界。
 *
 * <p>查不到即 {@link Optional#empty()}，消费方必须按**拒绝**处理：
 * 一个没登记权限的工具，说明没人评估过它能碰到什么，放行比拒绝危险。
 */
public interface ToolPermissionRegistry {

    Optional<ToolPermission> permissionFor(String toolName);

    /** 用一张固定映射表实现。登记之后不可变，避免运行期被人塞进去一条。 */
    static ToolPermissionRegistry of(Map<String, ToolPermission> permissions) {
        Map<String, ToolPermission> declared = Map.copyOf(permissions);
        return toolName -> Optional.ofNullable(declared.get(toolName));
    }
}
