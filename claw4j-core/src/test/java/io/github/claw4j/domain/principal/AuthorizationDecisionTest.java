package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次工具调用的权限判定：工具名查登记表 → 动作轴 → 表轴。
 *
 * <p>判定错了不会抛异常、不会打日志，只是某次越权调用被放过去（或者某次该跑的查询被拦住），
 * 所以这几条都得钉住。放行那一侧刻意不从「给定一个权限对象」开始，而是从工具名查起，
 * 这样「根本没查登记表」的实现无法通过。
 */
class AuthorizationDecisionTest {

    /** 能读两张业务表，动作只有读。 */
    private static Principal analyst() {
        return Principal.of("u-analyst", "analyst", Set.of("sales_order", "region"), Set.of("read"));
    }

    /** 工具名 → 权限，与生产上「工具模块自己登记」的形状一致。 */
    private static ToolPermissionRegistry registry() {
        return ToolPermissionRegistry.of(Map.of(
                "sales_query", ToolPermission.read("sales_order"),
                "send_mail", ToolPermission.of("send_email"),
                "two_table_export", ToolPermission.read("sales_order", "payroll")));
    }

    @Test
    @DisplayName("登记过的工具按名字查到权限，范围内就放行")
    void registeredToolInScopeIsAllowed() {
        ToolPermissionRegistry registry = registry();

        Optional<ToolPermission> permission = registry.permissionFor("sales_query");

        assertTrue(permission.isPresent());
        assertTrue(analyst().canUse(permission.orElseThrow()));
        // 换个没登记的名字就查不到：上面那次放行确实按工具名查过表，
        // 而不是随便拿一条权限来判定
        assertTrue(registry.permissionFor("sales_query_v2").isEmpty());
    }

    @Test
    @DisplayName("动作没授权就拒：表能读不代表能发邮件")
    void actionNotGrantedIsRejected() {
        ToolPermission sendMail = registry().permissionFor("send_mail").orElseThrow();

        assertFalse(analyst().canUse(sendMail));
    }

    @Test
    @DisplayName("表越权就拒：读动作被授权也不行")
    void tableOutsideScopeIsRejected() {
        assertFalse(analyst().canUse(ToolPermission.read("payroll")));
    }

    @Test
    @DisplayName("碰到多张表时一张越权整次调用就拒：按「任一可读」判会把数据带出去")
    void oneOutOfScopeTableRejectsTheWholeCall() {
        ToolPermission export = registry().permissionFor("two_table_export").orElseThrow();

        assertFalse(analyst().canUse(export));
    }

    @Test
    @DisplayName("不碰表的动作只看动作：表清单为空不能被当成越权")
    void tablelessActionIsDecidedByActionAlone() {
        // 只有动作授权的身份：若把「每张表都可读」写成「至少一张可读」，空表清单会被误拒
        Principal mailer = Principal.of("u-mailer", "mailer", Set.of(), Set.of("send_email"));

        assertTrue(mailer.canUse(ToolPermission.of("send_email")));
    }

    @Test
    @DisplayName("通配授权覆盖任意表与任意动作")
    void wildcardCoversAnyTableAndAnyAction() {
        Principal admin = Principal.of("u-admin", "admin",
                Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));

        assertTrue(admin.canUse(ToolPermission.read("any_table")));
        assertTrue(admin.canUse(ToolPermission.of("drop_everything")));
    }

    @Test
    @DisplayName("只读身份能读任意表，但执行不了有副作用的动作")
    void readOnlyCanReadAnyTableButCannotWrite() {
        Principal reader = Principal.readOnly("u-reader");

        assertTrue(reader.canUse(ToolPermission.read("sales_order")));
        assertTrue(reader.canUse(ToolPermission.read("never_heard_of")));
        assertFalse(reader.canUse(ToolPermission.of("send_email")));
    }

    @Test
    @DisplayName("没登记的工具查不到权限：兜底成默认放行等于没人评估过也能跑")
    void unregisteredToolHasNoPermission() {
        assertTrue(registry().permissionFor("rm_rf").isEmpty());
    }

    @Test
    @DisplayName("登记表建好后源映射再改也进不来：运行期塞一条授权不算数")
    void registrationIsASnapshotOfTheGivenMap() {
        Map<String, ToolPermission> source = new HashMap<>();
        source.put("sales_query", ToolPermission.read("payroll"));
        ToolPermissionRegistry registry = ToolPermissionRegistry.of(source);

        // 登记之后把已有工具放大成「全都能干」，再塞一条谁都没评估过的工具
        source.put("sales_query", ToolPermission.of("*"));
        source.put("rm_rf", ToolPermission.of("*"));

        assertFalse(analyst().canUse(registry.permissionFor("sales_query").orElseThrow()));
        assertTrue(registry.permissionFor("rm_rf").isEmpty());
    }
}
