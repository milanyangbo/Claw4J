package io.github.claw4j.channel.http;

import io.github.claw4j.domain.ApprovalChoice;
import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.Approvals;
import io.github.claw4j.domain.port.AuditQuery;
import io.github.claw4j.domain.port.AuditRunInspect;
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
 * HTTP 那一侧非聊天端点的对外形状：{@code /api/approvals}（P03）。
 *
 * <p>它问的是接口这一侧答得对不对：状态码是不是分得开、该给的字段在不在、没装配时端点
 * 是不是还在。属于它自己那半的「谁能批」在 {@code ApprovalService} 里。
 *
 * <p>没有按 id 取一轮结果的端点（ADR-0027）：一轮的编号是句柄，答完即作废，没有任何东西
 * 可以按它取回。要回答「那一轮说了什么」，读通道里的回答与 {@code audit_event}。
 *
 * <p>判据两条：没装配时端点仍要在（404 而不是端点消失，后者在客户端看来是路径写错了，
 * 排查方向完全不同），以及认人失败一律 401。
 */
class HttpEndpointsTest {

    // ---------------------------------------------------------------- 审批（P03）

    private static final String APPROVAL_ID = "ap-7";

    @Test
    @DisplayName("审批：读一条待批请求，编号、工具、摘要、发起人、状态与过期时间都在")
    void aPendingRequestIsReadable() {
        ResponseEntity<ApprovalController.ApprovalView> response =
                approvalController(new FixedDecider()).view(APPROVAL_ID, "ethan");

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
                .decide(APPROVAL_ID, new ApprovalController.DecisionRequest(true, null), "ethan");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("granted", response.getBody().verdict());
        assertEquals("已批准，这件事已经接着往下做了。", response.getBody().detail());
        assertEquals("ethan", decider.seenApprover, "身份从请求头来；请求体里能自称的话，自批只是一句口头约定");
        assertEquals(true, decider.seenApproved);
    }

    @Test
    @DisplayName("审批：请求体里再带一个 always，传下去的就是「以后都放行」那一档")
    void theAlwaysFlagTravelsAsThePersistentChoice() {
        FixedDecider decider = new FixedDecider();
        ResponseEntity<ApprovalController.DecisionResponse> response = approvalController(decider)
                .decide(APPROVAL_ID, new ApprovalController.DecisionRequest(true, true, null),
                        "ethan");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ApprovalChoice.ALLOW_ALWAYS, decider.seenChoice,
                "两个入口传的必须是同一件事：否则同一张请求在飞书里批不了长期、在接口里能批");
    }

    @Test
    @DisplayName("审批：拒绝也走同一条路，理由带到决定里，结论是 denied")
    void denyingCarriesTheReasonIntoTheDecision() {
        FixedDecider decider = new FixedDecider();
        decider.verdict = ApprovalVerdict.DENIED;

        ResponseEntity<ApprovalController.DecisionResponse> response = approvalController(decider)
                .decide(APPROVAL_ID, new ApprovalController.DecisionRequest(false, "这个客户不能发"), "ethan");

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
                approvalController(null).view(APPROVAL_ID, "ethan").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                approvalController(null).decide(APPROVAL_ID, new ApprovalController.DecisionRequest(true, null),
                        "ethan").getStatusCode());
    }

    @Test
    @DisplayName("审批：编号不存在时 404 且没有 body")
    void aMissingApprovalHasNoBody() {
        FixedDecider decider = new FixedDecider();
        decider.pending = Optional.empty();

        ResponseEntity<ApprovalController.ApprovalView> response =
                approvalController(decider).view(APPROVAL_ID, "ethan");

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

    /**
     * 审计的只读端点（ADR-0037 票 03）。
     *
     * <p>判两件事：一页按 {@code sequence} 倒序带出去、{@code nextCursor} 原样返回；没装配查询时
     * 回 404 而不是 500——后者在客户端看来像"库坏了"，前者才是"这个部署没开"。
     */
    @Test
    @DisplayName("审计：一页带出 nextCursor（字符串）；没装配时 404")
    void anAuditPageIsReadable() {
        AuditQuery fixed = filter -> {
            assertEquals(2, filter.limit());
            assertEquals("run-1", filter.runId());
            assertEquals("inbound", filter.direction(), "方向过滤要传下去");
            assertEquals(Long.valueOf(1000L), filter.after(), "时间窗下界要传下去");
            assertEquals(Long.valueOf(9000L), filter.before(), "时间窗上界要传下去");
            return new AuditQuery.Page(List.of(row(7L, "tool_action", "tool_action", null)), 7L);
        };

        ResponseEntity<?> response = auditController(fixed)
                .list("run-1", null, null, null, "inbound", null, null, 1000L, 9000L, null, 2);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AuditController.AuditView page = (AuditController.AuditView) response.getBody();
        assertNotNull(page);
        assertEquals(1, page.events().size());
        assertEquals("7", page.nextCursor(), "游标与 OpenClaw 同形：字符串，不是数字");
        assertEquals("call-7", page.events().getFirst().toolCallId(),
                "调用编号要带得出去（票 06）：没有它，同参数两次调用在客户端看来是同一行");
        assertEquals(HttpStatus.NOT_FOUND,
                auditController(null)
                        .list(null, null, null, null, null, null, null, null, null, null, 10)
                        .getStatusCode());
    }

    /**
     * 四个变体靠 {@code eventType} 判别，**外围那五格必填字段一个不能少**（ADR-0037 第三批）。
     *
     * <p>OpenClaw 的变体是闭对象，{@code commonProperties} 里 {@code schemaVersion} /
     * {@code eventId} / {@code sequence} / {@code sourceSequence} / {@code occurredAt} /
     * {@code redaction} 全必填（{@code audit-activity.ts:105-112}）。少一格，严格客户端解析不了；
     * 多一格（比如非消息行上的 {@code channel}、我们的 {@code argsDigest}）同样解析不了——
     * 所以这条同时钉"该来的在"与"不该来的不在"。
     */
    @Test
    @DisplayName("审计：四种变体各自的必填格都在，空字段不出现（第三批）")
    void anAuditPageCarriesTheVariantDiscriminator() {
        List<AuditQuery.Row> rows = List.of(
                row(4L, "agent_run", "agent_run", null),
                row(3L, "tool_action", "tool_action", null),
                row(2L, "message", "inbound_message",
                        new AuditQuery.MessageRow("inbound", "completed", null, "direct", null, null,
                                12, 1, "acc", "conv", "msg", "target")),
                row(1L, "message", "outbound_message",
                        new AuditQuery.MessageRow("outbound", "sent", null, "direct", "text", null,
                                30, 1, "acc", "conv", "msg", "target")));
        List<AuditEventView> views = rows.stream().map(AuditEventView::of).toList();

        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(new AuditController.AuditView(views, "4"));

        assertTrue(json.contains("\"eventType\":\"agent_run\""), json);
        assertTrue(json.contains("\"eventType\":\"tool_action\""), json);
        assertTrue(json.contains("\"eventType\":\"inbound_message\""), json);
        assertTrue(json.contains("\"eventType\":\"outbound_message\""), json);
        assertTrue(json.contains("\"schemaVersion\":1"), "版本号是必填：" + json);
        assertTrue(json.contains("\"sequence\":4"), "序号改名成 sequence：" + json);
        assertTrue(json.contains("\"sourceSequence\":1"), json);
        assertTrue(json.contains("\"occurredAt\":" + OCCURRED_AT.toEpochMilli()),
                "时间要给 Unix 毫秒整数，不是 ISO 字符串：" + json);
        assertTrue(json.contains("\"redaction\":\"metadata_only\""), json);
        assertTrue(json.contains("\"actor\":{\"type\":\"agent\",\"id\":\"ops\"}"),
                "actor 是对象，不是字符串：" + json);
        assertTrue(json.contains("\"actor\":{\"type\":\"channel_sender\",\"id\":\"acc\"}"),
                "入站消息的执行者是通道里那个人（假名）：" + json);
        assertTrue(json.contains("\"kind\":\"message\""),
                "kind 是过滤用的那一格，判别字段替不掉它：" + json);
        assertTrue(json.contains("\"direction\":\"outbound\""), json);
        assertTrue(!json.contains(":null"),
                "空字段必须整格省掉（闭对象里多一格就是多一个非法字段）：" + json);
        assertTrue(!json.contains("argsDigest") && !json.contains("approvalId"),
                "扩展列不进报文，否则就不是它那份 schema 的实例了：" + json);
    }

    /**
     * 判别字段是推出来的，就不许和 {@code kind} + 方向说两套话（票 07）。
     *
     * <p>这一条防的是一种不会报错的脏数据：一行 {@code kind=message} 却自称
     * {@code eventType=tool_action}，客户端会按工具那份 schema 去解析，缺的字段一律当空。
     */
    @Test
    @DisplayName("审计：eventType 与 kind/方向对不上的行造不出来（票 07）")
    void aRowCannotClaimTheWrongVariant() {
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> row(1L, "message", "tool_action",
                        new AuditQuery.MessageRow("inbound", "completed", null, "direct", null, null,
                                1, 1, "acc", "conv", "msg", "target")));

        assertTrue(failure.getMessage().contains("eventType"),
                "要说清是哪一格推错了，而不是一句「参数不合法」：" + failure.getMessage());
    }

    /**
     * 自相矛盾的过滤组合要 400（ADR-0037 第三批，照 {@code findAuditActivityFilterConflict}，
     * {@code audit-activity.ts:30-53}）。
     *
     * <p>静默返回空页是最坏的一种"对齐"：字段名对了，行为还是两套。客户端看到空页会以为
     * "这段时间没有审计"，而真相是"这两个参数不该一起用"。
     */
    @Test
    @DisplayName("审计：消息/非消息的参数组合互斥，冲突时 400 而不是空页（第三批）")
    void conflictingFiltersAreRejected() {
        AuditQuery never = filter -> {
            throw new AssertionError("参数冲突时不该查库：" + filter);
        };
        AuditController controller = auditController(never);

        ResponseEntity<?> toolWithChannel = controller.list(null, null, null, "tool_action", null,
                null, "http", null, null, null, 10);
        assertEquals(HttpStatus.BAD_REQUEST, toolWithChannel.getStatusCode());
        assertTrue(((AuditController.Problem) toolWithChannel.getBody()).error()
                .contains("direction 或 channel"));

        ResponseEntity<?> messageWithSession = controller.list(null, "sk", null, "message", null,
                null, null, null, null, null, 10);
        assertEquals(HttpStatus.BAD_REQUEST, messageWithSession.getStatusCode());
        assertTrue(((AuditController.Problem) messageWithSession.getBody()).error()
                .contains("sessionKey"));

        ResponseEntity<?> sessionAndChannel = controller.list(null, "sk", null, null, null, null,
                "http", null, null, null, 10);
        assertEquals(HttpStatus.BAD_REQUEST, sessionAndChannel.getStatusCode(), "没指定 kind 时两边互斥");
    }

    /** 游标是上一页给的那个字符串；认不出来的要给 400，而不是当成"从头查"。 */
    @Test
    @DisplayName("审计：cursor 认不出来时 400，不当成从头查（第三批）")
    void anUnknownCursorIsRejected() {
        AuditQuery never = filter -> {
            throw new AssertionError("游标不合法时不该查库");
        };

        ResponseEntity<?> response = auditController(never)
                .list(null, null, null, null, null, null, null, null, null, "not-a-cursor", 10);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(((AuditController.Problem) response.getBody()).error().contains("cursor"));
    }

    /** 这一批夹具用的时刻：毫秒断言靠它算，不写死数字。 */
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-22T12:00:00Z");

    /** 一行审计的夹具：只填这一批断言用得到的格子，其余留空。 */
    private static AuditQuery.Row row(long seq, String kind, String eventType,
                                      AuditQuery.MessageRow message) {
        boolean messageRow = message != null;
        return new AuditQuery.Row(seq, 1, "e-" + seq, 1, eventType, kind,
                kind + ".some.action", "started", null, "metadata_only",
                "run-1", messageRow ? null : "sk", messageRow ? null : "s-1", "ops",
                messageRow ? "channel_sender" : "agent", messageRow ? "acc" : "ops",
                messageRow ? "http" : null,
                messageRow ? null : "call-" + seq, null, null, null, OCCURRED_AT, message);
    }

    private static AuditController auditController(AuditQuery query) {
        return auditController(query, null);
    }

    private static AuditController auditController(AuditQuery query, AuditRunInspect inspect) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (query != null) {
            beans.registerSingleton("auditQuery", query);
        }
        if (inspect != null) {
            beans.registerSingleton("auditRunInspect", inspect);
        }
        return new AuditController(beans.getBeanProvider(AuditQuery.class),
                beans.getBeanProvider(AuditRunInspect.class));
    }

    private static ApprovalController approvalController(Approvals decider) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (decider != null) {
            beans.registerSingleton("approvalDecider", decider);
        }
        return new ApprovalController(beans.getBeanProvider(Approvals.class),
                new HttpPrincipalResolver(allowlist()));
    }

    /** 这个文件用的那份通道配置：名单里只有 {@code ethan}。空的默认名单意味着谁都进不来。 */
    private static HttpChannelProperties allowlist() {
        HttpChannelProperties properties = new HttpChannelProperties();
        properties.setUsers(java.util.Set.of("ethan"));
        return properties;
    }

    /** 只回答"收到的是什么"，不判资格：资格在 {@code ApprovalService} 那一侧。 */
    private static final class FixedDecider implements Approvals {

        private Optional<Pending> pending = Optional.of(new Pending(APPROVAL_ID, "turn-7", "u-9",
                "sendEmail", "to=张三", "pending", true, Instant.parse("2026-11-02T13:00:00Z")));

        private ApprovalVerdict verdict = ApprovalVerdict.GRANTED;

        private String seenApprover;

        private String seenReason;

        private Boolean seenApproved;

        @Override
        public Result decide(String approvalId, String approver, ApprovalChoice choice,
                             String reason) {
            // 这一层验的是入口：请求体说"以后都放行"时，传下去的那一档必须是 ALLOW_ALWAYS。
            seenChoice = choice;
            return decide(approvalId, approver, choice != ApprovalChoice.DENY, reason);
        }

        private ApprovalChoice seenChoice;

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

        @Override
        public Optional<Grant> redeem(String owner, String toolName, String argsDigest) {
            return Optional.empty();
        }

        @Override
        public Optional<String> ask(Ask ask) {
            return Optional.of(APPROVAL_ID);
        }
    }
}
