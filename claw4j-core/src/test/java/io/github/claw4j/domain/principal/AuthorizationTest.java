package io.github.claw4j.domain.principal;

import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.ApproverRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鉴权的两个分量：谁能做（决策）与谁能批（名单）。
 *
 * <p>它们是同一个问题的两端，合并在一处是因为两者错起来的形态相同：不抛异常、不打日志。判定
 * 错了是某次越权被放过去，名单错了是审批等于没装，两者都只会在事后审计里露出来。
 *
 * <p>一次工具调用的判定从工具名查登记表开始，再走动作轴、表轴。放行那一侧刻意不从"给定一个
 * 权限对象"开始，而是从工具名查起，这样"根本没查登记表"的实现无法通过。
 *
 * <p>谁能批有三个方向要钉住，因为错法各有一条：漏了名单则谁都能批（审批等于没装）；漏了自批则
 * 审批人给自己盖章（审批等于没装，且更隐蔽）；把两者合成一句，名单里的人会收到"你不能批"，
 * 他去翻自己的名字有没有拼错，而真正的原因是这一条该别人批。
 *
 * <p>用 JUnit 的断言而不是 AssertJ：core 不依赖 AssertJ（它连 Spring 都不依赖，见
 * {@code claw4j-core/pom.xml} 的 enforcer）。
 */
class AuthorizationTest {

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

    /**
     * 加了第三个分量之后，默认放行面一个都不该变（P01）。
     *
     * <p>这条挡的是"顺手收紧"：把默认值写成 {@code REQUIRED}，或者让某个工厂偷偷带上审批，所有
     * 工具会在一夜之间全都要求人点头，而上线时看到的只是"这个系统怎么每句话都卡住"。审批只加在
     * 真正有副作用的动作上，而且是被人显式写出来的一处。
     */
    @Test
    @DisplayName("两个工厂出来的权限都不带审批：加了第三个分量，默认放行面不变")
    void permissionFactoriesDefaultToNoApproval() {
        assertFalse(ToolPermission.read("sales_order").needsApproval());
        assertFalse(ToolPermission.of("send_email").needsApproval());
        // 显式要审批的那一档才带：否则上面两条断言只是在验一个常数，
        // 分不出「默认关着」与「这一档根本不存在」。
        assertTrue(ToolPermission.requiringApproval("send_email").needsApproval());
    }

    /**
     * 审批不替代鉴权（P01 最要紧的一条）。
     *
     * <p>答案必须与"没有审批这一档"时完全一样：没这个动作就是不授予。
     * 一旦 {@code grantedTo} 把审批也算进来（比如"要审批的就先放行，等审批兜底"），
     * 越权调用会以"等审批"的样子排进队列，审批人看到的是"这个人能不能发邮件"，
     * 而真正该回答的问题"他的角色有没有这个动作"已经被悄悄跳过了。
     */
    @Test
    @DisplayName("标了要审批也不改变鉴权结论：审批是第二道门，不是替代品")
    void approvalDoesNotChangeWhoIsGranted() {
        ToolPermission heavy = ToolPermission.requiringApproval("send_email", "customer");

        assertFalse(analyst().canUse(heavy));
        // 反方向也要验：有动作授权的人，这条权限本身是授予的——审批在更后面才拦。
        Principal mailer = Principal.of("u-mailer", "mailer", Set.of("customer"), Set.of("send_email"));
        assertTrue(mailer.canUse(heavy));
    }

    // ---------------------------------------------------------------- 谁能批（原 ApproverRosterTest）

    @Test
    @DisplayName("不在名单里的人批不了任何一条：与「这一条有什么问题」是两件事")
    void someoneOutsideTheRosterCannotApproveAnything() {
        ApproverRoster roster = new ApproverRoster(Set.of("ethan"));

        assertEquals(Optional.of(ApprovalVerdict.NOT_APPROVER), roster.refuse("wang", "ethan"));
        assertEquals(Optional.of(ApprovalVerdict.NOT_APPROVER), roster.refuse(null, "ethan"),
                "没有身份也是不在名单里");
    }

    @Test
    @DisplayName("名单是空的时候谁都批不了：忘配置的后果是卡住，不是谁都能点头")
    void anEmptyRosterLetsNobodyApprove() {
        ApproverRoster roster = new ApproverRoster(Set.of());

        assertTrue(roster.nobody());
        assertEquals(Optional.of(ApprovalVerdict.NOT_APPROVER), roster.refuse("ethan", "wang"));
    }

    @Test
    @DisplayName("提问人批自己的请求被拒，而且这一档与「不是审批人」分开：他可能就在名单里")
    void theRequesterCannotApproveTheirOwnRequest() {
        ApproverRoster roster = new ApproverRoster(Set.of("ethan", "wang"));

        assertEquals(Optional.of(ApprovalVerdict.SELF_APPROVAL), roster.refuse("ethan", "ethan"));
        assertEquals(Optional.empty(), roster.refuse("wang", "ethan"),
                "同一条请求换个人就批得了——否则这条用例只是把审批关掉了");
    }

    @Test
    @DisplayName("空白项不算数、两边空白会被裁掉：从聊天里复制来的名字不该让门变宽或变窄")
    void blankEntriesAreDroppedAndNamesAreTrimmed() {
        ApproverRoster roster = new ApproverRoster(new LinkedHashSet<>(List.of("ethan", " ", "")));

        assertEquals(Set.of("ethan"), roster.approvers());
        assertEquals(Optional.empty(), roster.refuse(" ethan ", "wang"), "裁掉空白之后仍然是同一个人");
        assertTrue(new ApproverRoster(Set.of(" ", "")).nobody(), "只填了空白的名单等于没填");
        assertFalse(new ApproverRoster(Set.of("ethan")).nobody());
    }
}
