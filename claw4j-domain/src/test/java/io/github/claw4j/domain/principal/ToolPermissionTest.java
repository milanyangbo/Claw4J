package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPermissionTest {

    @Test
    void 表清单里只要有一张越权就整体拒绝() {
        var principal = Principal.of("u-1", "analyst", Set.of("sales_order"), Set.of(ToolPermission.READ));
        var permission = ToolPermission.read("sales_order", "customer");

        assertFalse(permission.grantedTo(principal), "customer 不可读，整个调用就不该放行");
    }

    @Test
    void 通配符身份放行全部表() {
        var principal = Principal.of("u-2", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

        assertTrue(ToolPermission.read("anything").grantedTo(principal));
        assertTrue(ToolPermission.of("send_email").grantedTo(principal));
    }

    @Test
    void 登记后集合不可被外部改动() {
        var permission = ToolPermission.read("sales_order");

        assertThrows(UnsupportedOperationException.class, () -> permission.tables().add("customer"));
        assertEquals(Set.of("sales_order"), permission.tables());
    }

    @Test
    void 未登记的工具查不到权限() {
        var registry = ToolPermissionRegistry.of(Map.of("querySales", ToolPermission.read("sales_order")));

        assertTrue(registry.permissionFor("querySales").isPresent());
        assertFalse(registry.permissionFor("exportData").isPresent(), "没登记即查不到，消费方按拒绝处理");
    }
}
