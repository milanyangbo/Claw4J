package io.github.claw4j.app;

import io.github.claw4j.agent.model.ModelRoutingException;
import io.github.claw4j.channel.http.HttpChatController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 模型这一轮没产出可用结果时，回 503 而不是 500（票 14）。
 *
 * <p>为什么放在 app：{@code ModelRoutingException} 在 agent 侧，HTTP 状态码在 channel 侧，
 * 两边互不依赖（ADR-0010 的依赖图），只有 app 同时认识两者。放在这里不破坏方向。
 *
 * <p>为什么不是 500：500 是"服务端有 bug"，客户端与网关都不会重试；而这里是上游这一轮给不出
 * 可用结果，<b>换个时间再发一次就可能好</b>。503 恰好表达这件事。真正的 bug 仍然走 500——
 * 这条 advice 只认这一个类型，不要扩成 {@code RuntimeException}。
 *
 * <p>body 里不放内部异常原文：那里面有供应商名、状态码与栈信息，对调用方没用，
 * 对攻击者有用。原文进日志，配 {@code runId} 可查。
 */
@RestControllerAdvice
public class ModelFailureAdvice {

    private static final Logger log = LoggerFactory.getLogger(ModelFailureAdvice.class);

    /** 给调用方看的那句话。措辞是"再发一次"，因为这是唯一有效的动作。 */
    static final String UNAVAILABLE = "模型这一轮没有产出可用的回答，请把这句话再发一次";

    /**
     * 把模型侧失败映射成 503。
     *
     * @param failure 路由抛出的失败，原文进日志
     * @return 503 与一句可读的原因
     */
    @ExceptionHandler(ModelRoutingException.class)
    public ResponseEntity<HttpChatController.ErrorResponse> onModelUnavailable(ModelRoutingException failure) {
        log.warn("模型这一轮没有产出可用结果，按上游不可用回 503：{}", failure.getMessage(), failure);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HttpChatController.ErrorResponse(UNAVAILABLE));
    }
}
