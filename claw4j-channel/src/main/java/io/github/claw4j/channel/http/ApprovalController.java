package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.ApprovalVerdict;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.ApprovalDecider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 审批的 HTTP 入口（P03）：读一条待批请求、以及替它下结论。
 *
 * <p><b>与飞书卡片走的是同一条服务端逻辑</b>：两者都只做"认出谁在点"，
 * 然后把这件事交给 {@link ApprovalDecider#decide}。资格判定（名单、不能批自己的）
 * 在那边做一次——写在两个入口上的话，第二个入口必然漏，而漏掉的表现是
 * "同一个按钮换个通道就能自己批自己"。
 *
 * <p><b>为什么这个接口必须存在，不能"飞书就够了"</b>：定时任务、CI、值班脚本都可能需要批一步，
 * 而它们不在飞书里；反过来只跑 HTTP 的部署也不该因为"没配飞书"就没有审批。
 * 卡片是"让人顺手点一下"，接口是"让系统能批"。
 *
 * <p><b>状态码就是结论</b>：结论本身在 body 里（{@code verdict} + {@code detail}），
 * HTTP 状态码只回答"这次请求该怎么处置"——404 编号错了、403 你没资格、
 * 409 这个按钮已经不作数（过期或被别人点过）。全用 200 会逼调用方解析文案。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ObjectProvider<ApprovalDecider> deciders;

    private final HttpPrincipalResolver principalResolver;

    public ApprovalController(ObjectProvider<ApprovalDecider> deciders,
                              HttpPrincipalResolver principalResolver) {
        this.deciders = deciders;
        this.principalResolver = principalResolver;
    }

    /**
     * 读一条请求现在的样子：卡片渲染、以及"我刚才批的生效了吗"。
     *
     * @return 200 + 这条请求；404 表示没有（编号错了、或者这个部署没装审批）；
     *         没有装配审批时也回 404——那是能力不在场，不是这个通道坏了
     */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApprovalView> view(@PathVariable("id") String id,
                                             @RequestHeader(value = HttpPrincipalResolver.USER_HEADER,
                                                     required = false) String user,
                                             @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER,
                                                     required = false) String role) {
        // 认人放在最前面：未认人的请求不该知道某个编号存不存在。
        principalResolver.resolve(user, role);
        ApprovalDecider decider = deciders.getIfAvailable();
        if (decider == null) {
            log.debug("没有装配审批，/api/approvals 一律 404：id={}", id);
            return ResponseEntity.notFound().build();
        }
        return decider.find(id).map(ApprovalView::of).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 下结论。
     *
     * <p>请求体里只有两件事：批还是拒、以及理由（拒绝时回灌给模型）。
     * <b>没有"谁"</b>：点按钮的人由通道认出来（HTTP 是请求头，飞书是卡片事件的 operator），
     * 让调用方自己报身份的话，自批那一条就只是一句口头约定。
     */
    @PostMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DecisionResponse> decide(@PathVariable("id") String id,
                                                   @RequestBody DecisionRequest request,
                                                   @RequestHeader(value = HttpPrincipalResolver.USER_HEADER,
                                                           required = false) String user,
                                                   @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER,
                                                           required = false) String role) {
        Principal approver = principalResolver.resolve(user, role);
        ApprovalDecider decider = deciders.getIfAvailable();
        if (decider == null) {
            log.debug("没有装配审批，/api/approvals 一律 404：id={}", id);
            return ResponseEntity.notFound().build();
        }
        ApprovalDecider.Result result =
                decider.decide(id, approver.userId(), request.approved(), request.reason());
        return ResponseEntity.status(statusOf(result.verdict()))
                .body(new DecisionResponse(result.verdict().name().toLowerCase(),
                        result.detail()));
    }

    /** 结论 → HTTP 状态码。这个映射只在这一处，卡片那一侧用的是同一张表（toast 文案）。 */
    static HttpStatus statusOf(ApprovalVerdict verdict) {
        return switch (verdict) {
            case GRANTED, DENIED -> HttpStatus.OK;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_APPROVER, SELF_APPROVAL -> HttpStatus.FORBIDDEN;
            case EXPIRED, ALREADY_DECIDED -> HttpStatus.CONFLICT;
        };
    }

    /** 认人失败是"不该进来"，回 401；与聊天那一侧同一个口径。 */
    @ExceptionHandler(PrincipalRejectedException.class)
    public ResponseEntity<ErrorResponse> onPrincipalRejected(PrincipalRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(exception.reason()));
    }

    /**
     * @param approved true 批准，false 拒绝
     * @param reason   拒绝的理由。批准时可以不给
     */
    public record DecisionRequest(boolean approved, String reason) {
    }

    /**
     * @param verdict 小写档位名（{@code granted / denied / expired / not_found / …}），
     *                与库里 {@code approval_request.status} 不是一回事：那个只有三种
     * @param detail  给人看的一句话
     */
    public record DecisionResponse(String verdict, String detail) {
    }

    /**
     * 一条请求的样子。字段与 {@link ApprovalDecider.Pending} 一一对应，
     * 除了 <b>{@code argsDigest} 不在里面</b>——指纹是给机器比的，人要看 {@code argsSummary}。
     */
    public record ApprovalView(String id, String runId, String owner, String toolName,
                               String argsSummary, String status, boolean expired, Instant expiresAt) {

        static ApprovalView of(ApprovalDecider.Pending pending) {
            return new ApprovalView(pending.id(), pending.runId(), pending.owner(), pending.toolName(),
                    pending.argsSummary(), pending.status(), pending.expired(), pending.expiresAt());
        }
    }

    public record ErrorResponse(String error) {
    }
}
