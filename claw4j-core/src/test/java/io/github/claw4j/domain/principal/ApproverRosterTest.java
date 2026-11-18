package io.github.claw4j.domain.principal;

import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.ApproverRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 谁能批：审批人名单的三条方向。
 *
 * <p>这个文件原来叫 {@code AuthorizationTest}，测两件并列的事——"谁能做"（动作轴与表轴）
 * 与"谁能批"。"谁能做"那一半随权限登记表在 ADR-0030 里整块下线，
 * ADR-0030 里整块下线，于是只剩后半，文件也改成它现在测的东西的名字。
 *
 * <p>三个方向各有一条错法：漏了名单则谁都能批（审批等于没装）；漏了自批则审批人给自己盖章
 * （审批等于没装，且更隐蔽）；把两者合成一句，名单里的人会收到"你不能批"，他去翻自己的名字
 * 有没有拼错，而真正的原因是这一条该别人批。
 *
 * <p>用 JUnit 的断言而不是 AssertJ：core 不依赖 AssertJ（它连 Spring 都不依赖，见
 * {@code claw4j-core/pom.xml} 的 enforcer）。
 */
class ApproverRosterTest {

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
