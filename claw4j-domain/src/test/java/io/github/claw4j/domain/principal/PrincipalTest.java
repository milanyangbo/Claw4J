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

    /**
     * 只读身份**能读**：它能执行 read 动作，执行不了有副作用的动作。
     *
     * <p>反过来说：如果只读身份的动作集是空的，它在业务上就没有任何用途——
     * 连"上周成交额多少"都问不了。空动作集看着更安全，实际会让默认角色变成废角色。
     */
    @Test
    void 只读身份能读不能写() {
        var principal = Principal.readOnly("u-4");

        assertTrue(principal.canReadTable("orders"));
        assertTrue(principal.canUse(ToolPermission.read("orders")), "读自己能看的表是允许的");
        assertFalse(principal.canPerform("send_email"));
        assertFalse(principal.canUse(ToolPermission.of("send_email")), "有副作用的动作不在只读范围内");
    }

    @Test
    void 表级不满足时读动作也不放行() {
        var principal = Principal.of("u-7", "guest", Set.of("region"), Set.of(ToolPermission.READ));

        assertTrue(principal.canUse(ToolPermission.read("region")));
        assertFalse(principal.canUse(ToolPermission.read("sales_order")), "表不在白名单里，读也不行");
        assertFalse(principal.canUse(ToolPermission.read("region", "sales_order")),
                "工具会碰到的表只要有一张越权，整个调用就越权");
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
