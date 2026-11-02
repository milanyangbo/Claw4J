package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.port.ApprovalDecider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/approvals} 的对外形状（P03）。
 *
 * <p>这一份验的是"接口这一侧答得对不对"：结论有没有如实回来、该给的状态码给没给、
 * 以及<b>批的人是从请求头认出来的</b>（请求体里没有"谁"，有一个字就说明身份能被自称）。
 * 名单与自批的判定不在这里——它在 {@code ApprovalService}（那一侧两个入口共用）。
 */
class ApprovalControllerTest {

    private static final String APPROVAL_ID = "ap-7";

    @Test
    @DisplayName("读一条待批请求：编号、工具、摘要、发起人、状态与过期时间都在")
    void aPendingRequestIsReadable() {
        ResponseEntity<ApprovalController.ApprovalView> response =
                controller(new FixedDecider()).view(APPROVAL_ID, "ethan", "read-only");

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
    @DisplayName("批准：200，结论与那句话原样回来；批的人取自请求头，不是请求体")
    void approvingReportsTheVerdictAndWhoDidIt() {
        FixedDecider decider = new FixedDecider();
        ResponseEntity<ApprovalController.DecisionResponse> response = controller(decider)
                .decide(APPROVAL_ID, new ApprovalController.DecisionRequest(true, null), "ethan", "read-only");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("granted", response.getBody().verdict());
        assertEquals("已批准，这件事已经接着往下做了。", response.getBody().detail());
        assertEquals("ethan", decider.seenApprover, "身份从请求头来；请求体里能自称的话，自批只是一句口头约定");
        assertEquals(true, decider.seenApproved);
    }

    @Test
    @DisplayName("拒绝也走同一条路：理由带到决定里，结论是 denied")
    void denyingCarriesTheReasonIntoTheDecision() {
        FixedDecider decider = new FixedDecider();
        decider.verdict = ApprovalVerdict.DENIED;

        ResponseEntity<ApprovalController.DecisionResponse> response = controller(decider)
                .decide(APPROVAL_ID, new ApprovalController.DecisionRequest(false, "这个客户不能发"), "ethan", "read-only");

        assertEquals("denied", response.getBody().verdict());
        assertEquals("这个客户不能发", decider.seenReason);
        assertEquals(false, decider.seenApproved);
    }

    @Test
    @DisplayName("三种不同的失败给三种状态码：404 编号错、403 没资格、409 按钮已经不作数")
    void failuresMapToDistinguishableStatusCodes() {
        assertEquals(HttpStatus.NOT_FOUND, statusFor(ApprovalVerdict.NOT_FOUND));
        assertEquals(HttpStatus.FORBIDDEN, statusFor(ApprovalVerdict.NOT_APPROVER));
        assertEquals(HttpStatus.FORBIDDEN, statusFor(ApprovalVerdict.SELF_APPROVAL));
        assertEquals(HttpStatus.CONFLICT, statusFor(ApprovalVerdict.EXPIRED));
        assertEquals(HttpStatus.CONFLICT, statusFor(ApprovalVerdict.ALREADY_DECIDED));
        assertEquals(HttpStatus.OK, statusFor(ApprovalVerdict.GRANTED));
    }

    @Test
    @DisplayName("没装配审批：一律 404，控制器照样在（端点凭空消失更难查）")
    void withoutADeciderEverythingIs404() {
        assertEquals(HttpStatus.NOT_FOUND,
                controller(null).view(APPROVAL_ID, "ethan", "read-only").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                controller(null).decide(APPROVAL_ID, new ApprovalController.DecisionRequest(true, null),
                        "ethan", "read-only").getStatusCode());
    }

    @Test
    @DisplayName("认人失败回 401：未认人的请求不该知道某个编号存不存在")
    void principalRejectionMapsTo401() {
        ResponseEntity<ApprovalController.ErrorResponse> response =
                controller(new FixedDecider()).onPrincipalRejected(
                        new PrincipalRejectedException("缺少 X-Claw4j-User 请求头"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("缺少 X-Claw4j-User 请求头", response.getBody().error());
    }

    @Test
    @DisplayName("编号不存在：读的时候 404 且没有 body")
    void missingRequestHasNoBody() {
        FixedDecider decider = new FixedDecider();
        decider.pending = Optional.empty();

        ResponseEntity<ApprovalController.ApprovalView> response =
                controller(decider).view(APPROVAL_ID, "ethan", "read-only");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    // ---------------------------------------------------------------- 装配

    private static HttpStatus statusFor(ApprovalVerdict verdict) {
        return ApprovalController.statusOf(verdict);
    }

    /** @param decider 为 null 表示容器里没有审批——那个 bean 缺席时端点仍要在。 */
    private static ApprovalController controller(ApprovalDecider decider) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (decider != null) {
            beans.registerSingleton("approvalDecider", decider);
        }
        return new ApprovalController(beans.getBeanProvider(ApprovalDecider.class),
                new HttpPrincipalResolver(new HttpChannelProperties()));
    }

    /** 只回答"收到的是什么"，不判资格：资格在 {@code ApprovalService} 那一侧。 */
    private static final class FixedDecider implements ApprovalDecider {

        private Optional<Pending> pending = Optional.of(new Pending(APPROVAL_ID, "run-7", "u-9",
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
