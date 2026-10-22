package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrincipalTest {

    @Test
    void 表级权限只认白名单里的表() {
        var principal = Principal.of("u-1", "analyst", Set.of("orders", "regions"), Set.of());

        assertTrue(principal.canReadTable("orders"));
        assertFalse(principal.canReadTable("customers"), "不在白名单里的表不可读");
    }

    @Test
    void 通配符放行全部表() {
        var principal = Principal.of("u-2", "admin", Set.of(Principal.WILDCARD), Set.of());

        assertTrue(principal.canReadTable("anything"));
    }

    @Test
    void 动作权限独立判定() {
        var principal = Principal.of("u-3", "ops", Set.of(), Set.of("send_email"));

        assertTrue(principal.canPerform("send_email"));
        assertFalse(principal.canPerform("drop_table"));
    }

    @Test
    void 只读身份不能执行任何动作() {
        var principal = Principal.readOnly("u-4");

        assertTrue(principal.canReadTable("orders"));
        assertFalse(principal.canPerform("send_email"));
    }

    @Test
    void 构造后外部修改集合不影响内部状态() {
        var tables = new HashSet<>(Set.of("orders"));
        var principal = Principal.of("u-5", "analyst", tables, Set.of());

        tables.add("customers");

        assertFalse(principal.canReadTable("customers"), "防御性拷贝失效会被越权读取");
    }

    @Test
    void 返回的集合不可变() {
        var principal = Principal.of("u-6", "analyst", Set.of("orders"), Set.of("send_email"));

        assertThrows(UnsupportedOperationException.class, () -> principal.accessibleTables().add("customers"));
        assertEquals(Set.of("orders"), principal.accessibleTables());
    }
}
