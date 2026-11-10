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
 * 审批的 HTTP 入口（P03）：读一条待批请求，以及替它下结论。
 *
 * <p>与飞书卡片走同一条服务端逻辑：两者都只负责认出谁在点，然后把事情交给
 * {@link ApprovalDecider#decide}。资格判定（名单、不能批自己）只在那边做一次；
 * 写在两个入口上，第二个必然漏，而漏掉的表现是同一个按钮换个通道就能自己批自己。
 *
 * <p>接口必须存在，不能只靠卡片：定时任务、CI、值班脚本都可能需要批一步，而它们不在飞书里；
 * 反过来，只跑 HTTP 的部署也不该因为没配飞书就没有审批。卡片是让人顺手点一下，接口是让系统能批。
 *
 * <p>状态码回答的是这次请求该怎么处置，结论本身在 body 里（{@code verdict} 与 {@code detail}）：
 * 404 编号错了、403 没资格、409 这个按钮已经不作数（过期或被别人点过）。全用 200 会逼调用方
 * 解析文案。
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
     * 读一条请求现在的样子，供卡片渲染与「我刚才批的生效了吗」这类确认。
     *
     * <p>没有装配审批时也回 404：那是能力不在场，不是这个通道坏了。
     *
     * @param id 审批编号
     * @param user 请求头里的用户标识，允许缺失
     * @param role 请求头里的角色名，允许缺失
     * @return 200 加这条请求；编号不存在时 404
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
     * 替一条请求下结论。
     *
     * <p>请求体里只有两件事：批还是拒、以及理由（拒绝时回灌给模型）。没有「谁」：
     * 点按钮的人由通道认出来（HTTP 是请求头，飞书是卡片事件的 operator），
     * 让调用方自己报身份的话，自批那一条就只是一句口头约定。
     *
     * @param id 审批编号
     * @param request 决定与理由
     * @param user 请求头里的用户标识，允许缺失
     * @param role 请求头里的角色名，允许缺失
     * @return 状态码由 {@link #statusOf} 决定，body 是状态名与一句话
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

    /** 结论到 HTTP 状态码的映射。只在这一处，卡片那一侧用的是同一张表的 toast 文案。 */
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
     * 一次决定的请求体。
     *
     * @param approved true 批准，false 拒绝
     * @param reason 拒绝的理由，批准时可以不给
     */
    public record DecisionRequest(boolean approved, String reason) {
    }

    /**
     * 一次决定的结果。
     *
     * @param verdict 小写档位名（{@code granted}、{@code denied}、{@code expired}、
     *                {@code not_found} 等），与库里 {@code approval_request.status} 不是一回事：
     *                那个只有三种
     * @param detail 给人看的一句话
     */
    public record DecisionResponse(String verdict, String detail) {
    }

    /**
     * 一条请求对外露出的样子。字段与 {@link ApprovalDecider.Pending} 一一对应，
     * 只少 {@code argsDigest}：指纹是给机器比的，人要看 {@code argsSummary}。
     */
    public record ApprovalView(String id, String runId, String owner, String toolName,
                               String argsSummary, String status, boolean expired, Instant expiresAt) {

        static ApprovalView of(ApprovalDecider.Pending pending) {
            return new ApprovalView(pending.id(), pending.runId(), pending.owner(), pending.toolName(),
                    pending.argsSummary(), pending.status(), pending.expired(), pending.expiresAt());
        }
    }

    /**
     * 认人失败时的响应体。
     *
     * @param error 拒绝原因
     */
    public record ErrorResponse(String error) {
    }
}
