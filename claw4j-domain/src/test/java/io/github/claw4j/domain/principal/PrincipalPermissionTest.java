package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份能不能碰某张表、能不能调某个工具。
 *
 * <p>这里守的都是"放行"方向上的静默失效：多给了一张表不会报错，只会在某个用户
 * 问出一张不该看的表时才被发现。所以每条断言都在钉"不能"，而不是"能"。
 */
class PrincipalPermissionTest {

    @Test
    @DisplayName("不在白名单里的表不可读，通配符放行全部")
    void 表级白名单() {
        var analyst = Principal.of("u-1", "analyst", Set.of("orders", "regions"), Set.of());
        var admin = Principal.of("u-2", "admin", Set.of(Principal.WILDCARD), Set.of());

        assertTrue(analyst.canReadTable("orders"));
        assertFalse(analyst.canReadTable("customers"), "不在白名单里的表不可读");
        assertTrue(admin.canReadTable("anything"));
    }

    @Test
    @DisplayName("动作权限独立于表权限判定")
    void 动作权限() {
        var ops = Principal.of("u-3", "ops", Set.of(), Set.of("send_email"));

        assertTrue(ops.canPerform("send_email"));
        assertFalse(ops.canPerform("drop_table"));
    }

    @Test
    @DisplayName("只读身份能读不能写：空动作集会让默认角色变成废角色")
    void 只读身份能读不能写() {
        var guest = Principal.readOnly("u-4");

        assertTrue(guest.canReadTable("orders"));
        assertTrue(guest.canUse(ToolPermission.read("orders")), "读自己能看的表是允许的");
        assertFalse(guest.canPerform("send_email"));
        assertFalse(guest.canUse(ToolPermission.of("send_email")), "有副作用的动作不在只读范围内");
    }

    @Test
    @DisplayName("工具会碰到的表里只要有一张越权，整个调用就拒绝")
    void 多表任一越权即整体拒绝() {
        var guest = Principal.of("u-7", "guest", Set.of("region"), Set.of(ToolPermission.READ));

        assertTrue(guest.canUse(ToolPermission.read("region")));
        assertFalse(guest.canUse(ToolPermission.read("sales_order")), "表不在白名单里，读也不行");
        assertFalse(guest.canUse(ToolPermission.read("region", "sales_order")), "一张越权即整体越权");
        assertFalse(ToolPermission.read("sales_order", "customer").grantedTo(guest),
                "权限对象自己判定时，结论必须与 canUse 一致");
    }

    @Test
    @DisplayName("通配符身份在工具权限上同样放行")
    void 通配符放行工具() {
        var admin = Principal.of("u-2", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

        assertTrue(ToolPermission.read("anything").grantedTo(admin));
        assertTrue(ToolPermission.of("send_email").grantedTo(admin));
    }

    @Test
    @DisplayName("未登记的工具查不到权限，消费方按拒绝处理")
    void 未登记的工具查不到权限() {
        var registry = ToolPermissionRegistry.of(Map.of("querySales", ToolPermission.read("sales_order")));

        assertTrue(registry.permissionFor("querySales").isPresent());
        assertFalse(registry.permissionFor("exportData").isPresent());
    }

    @Test
    @DisplayName("构造后外部改集合不影响内部状态")
    void 防御性拷贝() {
        var tables = new HashSet<>(Set.of("orders"));
        var principal = Principal.of("u-5", "analyst", tables, Set.of());

        tables.add("customers");

        assertFalse(principal.canReadTable("customers"), "防御性拷贝失效会被越权读取");
    }

    @Test
    @DisplayName("对外暴露的集合不可变：改它就等于改权限")
    void 集合不可变() {
        var principal = Principal.of("u-6", "analyst", Set.of("orders"), Set.of("send_email"));
        var permission = ToolPermission.read("sales_order");

        assertThrows(UnsupportedOperationException.class, () -> principal.accessibleTables().add("customers"));
        assertThrows(UnsupportedOperationException.class, () -> permission.tables().add("customer"));
        assertEquals(Set.of("orders"), principal.accessibleTables());
        assertEquals(Set.of("sales_order"), permission.tables());
    }
}
