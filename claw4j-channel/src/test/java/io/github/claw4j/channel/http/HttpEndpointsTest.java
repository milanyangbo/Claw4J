package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.ApprovalDecider;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 那一侧<b>非聊天</b>端点的对外形状：{@code /api/approvals}（P03）。
 *
 * <p><b>它问的是同一件事</b>：<b>接口这一侧答得对不对</b>——状态码是不是分得开、
 * 该给的字段在不在、没装配时端点是不是还在。真正属于它自己那半（谁能批）在
 * {@code ApprovalService} 里。
 *
 * <p><b>没有"按 id 取一轮结果"的端点</b>（ADR-0027）：一轮的编号是句柄，答完即作废，
 * 没有任何东西可以按它取回。要回答"那一轮说了什么"，读通道里的回答与 {@code audit_event}。
 *
 * <p>判据两条：<b>没装配时端点仍要在</b>（{@code 404} 而不是端点消失——
 * 后者在客户端看来是"路径写错了"，排查方向完全不同），以及<b>认人失败一律 401</b>。
 */
class HttpEndpointsTest {

    // ---------------------------------------------------------------- 审批（P03）

    private static final String APPROVAL_ID = "ap-7";

    @Test
    @DisplayName("审批：读一条待批请求，编号、工具、摘要、发起人、状态与过期时间都在")
    void aPendingRequestIsReadable() {
        ResponseEntity<ApprovalController.ApprovalView> response =
                approvalController(new FixedDecider()).view(APPROVAL_ID, "ethan", "read-only");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApprovalController.ApprovalView body = response.getBody();
        assertNotNull(body);
        assertEquals(APPROVAL_ID, body.id());
        assertEquals("sendEmail", body.toolName());
        assertEquals("to=张三", body.argsSummary(), "人要看的是这一次打算干什么，不是一串十六进制");
        assertEquals("u-9", body.owner(), "卡片要写清是谁提的，审批人得能去问一句");
        assertEquals("pending", body.status());
        assertTrue(body.expired());
    }

    @Test
    @DisplayName("审批：批准回 200，结论与那句话原样回来；批的人取自请求头，不是请求体")
    void approvingReportsTheVerdictAndWhoDidIt() {
        FixedDecider decider = new FixedDecider();
        ResponseEntity<ApprovalController.DecisionResponse> response = approvalController(decider)
                .decide(APPROVAL_ID, new ApprovalController.DecisionRequest(true, null), "ethan", "read-only");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("granted", response.getBody().verdict());
        assertEquals("已批准，这件事已经接着往下做了。", response.getBody().detail());
        assertEquals("ethan", decider.seenApprover, "身份从请求头来；请求体里能自称的话，自批只是一句口头约定");
        assertEquals(true, decider.seenApproved);
    }

    @Test
    @DisplayName("审批：拒绝也走同一条路，理由带到决定里，结论是 denied")
    void denyingCarriesTheReasonIntoTheDecision() {
        FixedDecider decider = new FixedDecider();
        decider.verdict = ApprovalVerdict.DENIED;

        ResponseEntity<ApprovalController.DecisionResponse> response = approvalController(decider)
                .decide(APPROVAL_ID, new ApprovalController.DecisionRequest(false, "这个客户不能发"), "ethan", "read-only");

        assertEquals("denied", response.getBody().verdict());
        assertEquals("这个客户不能发", decider.seenReason);
        assertEquals(false, decider.seenApproved);
    }

    @Test
    @DisplayName("审批：三种不同的失败给三种状态码——404 编号错、403 没资格、409 按钮已经不作数")
    void approvalFailuresMapToDistinguishableStatusCodes() {
        assertEquals(HttpStatus.NOT_FOUND, ApprovalController.statusOf(ApprovalVerdict.NOT_FOUND));
        assertEquals(HttpStatus.FORBIDDEN, ApprovalController.statusOf(ApprovalVerdict.NOT_APPROVER));
        assertEquals(HttpStatus.FORBIDDEN, ApprovalController.statusOf(ApprovalVerdict.SELF_APPROVAL));
        assertEquals(HttpStatus.CONFLICT, ApprovalController.statusOf(ApprovalVerdict.EXPIRED));
        assertEquals(HttpStatus.CONFLICT, ApprovalController.statusOf(ApprovalVerdict.ALREADY_DECIDED));
        assertEquals(HttpStatus.OK, ApprovalController.statusOf(ApprovalVerdict.GRANTED));
    }

    @Test
    @DisplayName("审批：没装配审批时一律 404，控制器照样在（端点凭空消失更难查）")
    void withoutADeciderEverythingIs404() {
        assertEquals(HttpStatus.NOT_FOUND,
                approvalController(null).view(APPROVAL_ID, "ethan", "read-only").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                approvalController(null).decide(APPROVAL_ID, new ApprovalController.DecisionRequest(true, null),
                        "ethan", "read-only").getStatusCode());
    }

    @Test
    @DisplayName("审批：编号不存在时 404 且没有 body")
    void aMissingApprovalHasNoBody() {
        FixedDecider decider = new FixedDecider();
        decider.pending = Optional.empty();

        ResponseEntity<ApprovalController.ApprovalView> response =
                approvalController(decider).view(APPROVAL_ID, "ethan", "read-only");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    // ---------------------------------------------------------------- 两条共同的口径

    @Test
    @DisplayName("认人失败回 401，不放匿名请求进来（与聊天那一侧同一个口径）")
    void principalRejectionMapsTo401() {
        PrincipalRejectedException rejected = new PrincipalRejectedException("缺少 X-Claw4j-User 请求头");

        ResponseEntity<ApprovalController.ErrorResponse> approval =
                approvalController(new FixedDecider()).onPrincipalRejected(rejected);

        assertEquals(HttpStatus.UNAUTHORIZED, approval.getStatusCode());
        assertEquals("缺少 X-Claw4j-User 请求头", approval.getBody().error());
    }

    // ---------------------------------------------------------------- 夹具

    /** @param decider 为 null 表示容器里没有审批——那个 bean 缺席时端点仍要在。 */
    private static ApprovalController approvalController(ApprovalDecider decider) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (decider != null) {
            beans.registerSingleton("approvalDecider", decider);
        }
        return new ApprovalController(beans.getBeanProvider(ApprovalDecider.class),
                new HttpPrincipalResolver(new HttpChannelProperties()));
    }

    /** 只回答"收到的是什么"，不判资格：资格在 {@code ApprovalService} 那一侧。 */
    private static final class FixedDecider implements ApprovalDecider {

        private Optional<Pending> pending = Optional.of(new Pending(APPROVAL_ID, "turn-7", "u-9",
                "sendEmail", "to=张三", "pending", true, Instant.parse("2026-11-02T13:00:00Z")));

        private ApprovalVerdict verdict = ApprovalVerdict.GRANTED;

        private String seenApprover;

        private String seenReason;

        private Boolean seenApproved;

        @Override
        public Result decide(String approvalId, String approver, boolean approved, String reason) {
            this.seenApprover = approver;
            this.seenApproved = approved;
            this.seenReason = reason;
            return new Result(verdict, verdict == ApprovalVerdict.GRANTED
                    ? "已批准，这件事已经接着往下做了。"
                    : "已拒绝，理由已经交给模型。");
        }

        @Override
        public Optional<Pending> find(String approvalId) {
            return pending;
        }
    }
}
