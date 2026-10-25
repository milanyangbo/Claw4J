package io.github.claw4j.domain.principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个完整功能：一次工具调用的权限判定。
 *
 * <p>从"角色配置变成 Principal"进来，到"这次调用放不放行"出去，中间不拆开测。
 * 这条链只有三站——登记表查权限、动作判定、表判定——但三站的失效姿势完全不同：
 * 动作漏判等于任何人可发邮件，表漏判等于任何人可查全部业务数据，而两者都不会抛异常。
 */
class AuthorizationDecisionTest {

    /** 与 application.yml 的 roles 同形：admin 全通。 */
    private static Principal admin() {
        return Principal.of("u-admin", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));
    }

    /** read-only 的语义是"能读不能写"，它的动作集里有 read、没有 run_script。 */
    private static Principal readOnly() {
        return Principal.readOnly("u-ro");
    }

    /** guest 只配了 region 一张表，动作里没有 draft_script。 */
    private static Principal guest() {
        return Principal.of("u-guest", "guest",
                Set.of("region"), Set.of("read", "use_skill", "delegate_task", "read_system"));
    }

    @Test
    @DisplayName("通配符同时覆盖两个轴：动作 * 与表 * 各自生效")
    void wildcardsCoverBothAxes() {
        assertTrue(ToolPermission.read("sales_order").grantedTo(admin()));
        assertTrue(ToolPermission.of("run_script").grantedTo(admin()));
        assertTrue(ToolPermission.of("draft_script").grantedTo(admin()));
    }

    @Test
    @DisplayName("只读角色能读任意表：它的动作集不是空的")
    void readOnlyCanReadAnyTable() {
        assertTrue(ToolPermission.read("sales_order").grantedTo(readOnly()));
        assertFalse(readOnly().allowedActions().isEmpty(), "空动作集等于连查数都不行，那就不是一个可用的身份");
    }

    @Test
    @DisplayName("只读角色跑不了脚本：表全通也拦得住")
    void readOnlyCannotRunScript() {
        assertFalse(ToolPermission.of("run_script").grantedTo(readOnly()));
        assertFalse(ToolPermission.of("draft_script").grantedTo(readOnly()));
    }

    @Test
    @DisplayName("表维度独立生效：guest 读得到 region")
    void guestReadsItsOwnTable() {
        assertTrue(ToolPermission.read("region").grantedTo(guest()));
    }

    @Test
    @DisplayName("表维度独立生效：guest 读不到 sales_order")
    void guestCannotReadForeignTable() {
        assertFalse(ToolPermission.read("sales_order").grantedTo(guest()));
    }

    @Test
    @DisplayName("工具碰两张表而其中一张越权：整次调用拒绝，不是只挡那一张")
    void oneForbiddenTableRejectsTheWholeCall() {
        assertFalse(ToolPermission.read("region", "sales_order").grantedTo(guest()),
                "任一表越权即拒绝，否则一个联表查询就成了只读部分数据的合法通道");
    }

    @Test
    @DisplayName("动作不通过时，表通不通都不放行")
    void actionIsCheckedEvenWhenTablesAreOpen() {
        Principal reader = Principal.of("u-read", "reader", Set.of(Principal.WILDCARD), Set.of("read"));
        assertFalse(ToolPermission.of("send_email").grantedTo(reader),
                "动作是独立的一轴：能读全部表不代表能发邮件");
    }

    @Test
    @DisplayName("没有任何动作的身份什么都不能做")
    void identityWithoutActionsCanDoNothing() {
        Principal nobody = Principal.of("u-none", "none", Set.of(Principal.WILDCARD), Set.of());
        assertFalse(ToolPermission.read("region").grantedTo(nobody));
        assertFalse(nobody.canPerform("read"));
    }

    @Test
    @DisplayName("没登记的工具按拒绝处理")
    void unregisteredToolIsDenied() {
        ToolPermissionRegistry registry = ToolPermissionRegistry.of(
                Map.of("querySales", ToolPermission.read("sales_order")));

        // 消费方（鉴权 Advisor）的写法就是这一句：查不到即拒。
        boolean allowed = registry.permissionFor("unknownTool")
                .map(permission -> permission.grantedTo(admin()))
                .orElse(false);

        assertFalse(allowed, "没登记说明没人评估过它能碰到什么，放行比拒绝危险");
        assertTrue(registry.permissionFor("querySales").isPresent());
    }

    @Test
    @DisplayName("登记表在构造时就定格：改原 map 塞不进新工具")
    void registrySnapshotIsImmutable() {
        Map<String, ToolPermission> declared = new HashMap<>();
        declared.put("querySales", ToolPermission.read("sales_order"));
        ToolPermissionRegistry registry = ToolPermissionRegistry.of(declared);

        declared.put("dropEverything", ToolPermission.of("run_script"));

        assertTrue(registry.permissionFor("dropEverything").isEmpty(),
                "运行期往登记表里塞一条权限，等于绕过评审就给自己开了口子");
    }
}
