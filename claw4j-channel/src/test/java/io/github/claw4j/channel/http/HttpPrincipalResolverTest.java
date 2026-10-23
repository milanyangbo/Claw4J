package io.github.claw4j.channel.http;

import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认人规则。通道这边最该被测试的地方：放过一个匿名请求，后面所有鉴权都白做。
 */
class HttpPrincipalResolverTest {

    private final HttpChannelProperties properties = new HttpChannelProperties();

    private final HttpPrincipalResolver resolver = new HttpPrincipalResolver(properties);

    @Test
    void 没有用户标识的请求被拒绝() {
        assertThrows(PrincipalRejectedException.class, () -> resolver.resolve(null, "read-only"));
        assertThrows(PrincipalRejectedException.class, () -> resolver.resolve("   ", "read-only"));
    }

    @Test
    void 不带角色头时按最小权限处理() {
        Principal principal = resolver.resolve("u-1", null);

        assertEquals("u-1", principal.userId());
        assertEquals("read-only", principal.role());
        assertTrue(principal.canReadTable("orders"), "默认角色能看表");
        assertFalse(principal.canPerform("anything"), "默认角色不能执行任何动作");
    }

    @Test
    void 登记过的角色按其授权表生效() {
        properties.setRoles(Map.of(
                "analyst",
                new HttpChannelProperties.RoleGrant(Set.of("orders", "customers"), Set.of("query-sql"))));

        Principal principal = resolver.resolve("u-2", "analyst");

        assertTrue(principal.canReadTable("orders"));
        assertFalse(principal.canReadTable("salaries"), "表不在授权范围内");
        assertTrue(principal.canPerform("query-sql"));
        assertFalse(principal.canPerform("send-email"));
    }

    @Test
    void 未登记的角色一律拒绝而不是给默认权限() {
        assertThrows(PrincipalRejectedException.class, () -> resolver.resolve("u-3", "god"));
    }

    @Test
    void 会话键带上通道前缀且缺省回落到用户标识() {
        assertEquals("http:sess-1", resolver.chatId("u-1", "sess-1"));
        assertEquals("http:u-1", resolver.chatId("u-1", null));
        assertEquals("http:u-1", resolver.chatId("u-1", "  "));
    }
}
