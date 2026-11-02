package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.port.RunQuery;
import io.github.claw4j.domain.port.RunView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/runs/{id}}：按 id 回收一件事（A05，ADR-0018）。
 *
 * <p>它存在是因为结果不再只活在"那一次请求"里：定时任务跑完时没有人在等，
 * 审批放行之后的结果要给发起审批的人，子 Agent 跑得久、父那一轮早答完了。
 * 三种情况下"谁问的就回给谁"都送不出去，于是结果必须能<b>被按 id 取走</b>。
 *
 * <p><b>认的是人，不是会话</b>：同一个 runId，在飞书里开的那件事可以在 HTTP 上查到。
 * 这不是"放宽权限"，是"run 本来就绑 principal，不绑通道"——换通道不该换一件事。
 * 反过来说，换了人就一定查不到：不属于你的与没有这个 run 回同一个 404，
 * 因为分成 403 与 404 等于替对方确认"这个 id 存在，只是不是你的"。
 *
 * <p><b>轮询是 HTTP 这一侧的唯一方式，不是妥协</b>：HTTP 没有可推的地址
 * （连接早就断了），所以它<b>不提供</b> {@code OutboundDelivery} 实现。
 * 延迟从"立刻"变成"下一次轮询"，这个代价要写进 {@code capability-tests.md}，
 * 别让人误以为流式坏了。
 */
@RestController
@RequestMapping("/api/runs")
public class RunQueryController {

    private static final Logger log = LoggerFactory.getLogger(RunQueryController.class);

    private final ObjectProvider<RunQuery> runs;

    private final HttpPrincipalResolver principalResolver;

    public RunQueryController(ObjectProvider<RunQuery> runs, HttpPrincipalResolver principalResolver) {
        this.runs = runs;
        this.principalResolver = principalResolver;
    }

    /**
     * @return 200 + 这件事的形状；404 表示查不到（没有这个 run、不是你的、或没装配 run 存储）
     */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunView> run(@PathVariable("id") String id,
                                       @RequestHeader(value = HttpPrincipalResolver.USER_HEADER,
                                               required = false) String user,
                                       @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER,
                                               required = false) String role) {
        // 用 ObjectProvider 而不是把整个控制器做成 @ConditionalOnBean：
        // 条件判定在自动配置之间比顺序，而"run 的装配"与"通道的装配"在两个 jar 里——
        // 判早一步的表现是端点凭空消失，那种故障查起来没有痕迹。
        RunQuery query = runs.getIfAvailable();
        if (query == null) {
            log.debug("没有装配 run 存储，/api/runs 一律 404：runId={}", id);
            return ResponseEntity.notFound().build();
        }
        return query.find(id, principalResolver.resolve(user, role))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 认人失败是"不该进来"，回 401；与聊天那一侧同一个口径。 */
    @ExceptionHandler(PrincipalRejectedException.class)
    public ResponseEntity<ErrorResponse> onPrincipalRejected(PrincipalRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(exception.reason()));
    }

    public record ErrorResponse(String error) {
    }
}
