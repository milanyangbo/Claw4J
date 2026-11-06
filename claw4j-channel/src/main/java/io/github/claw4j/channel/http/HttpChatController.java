package io.github.claw4j.channel.http;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.channel.spi.TooBusyException;

/**
 * HTTP+SSE 的传输层。它只做三件事：读头部、认人、把消息交给 {@link HttpChatChannel}。
 *
 * <p>刻意不在这里写任何编排逻辑——换通道时这一段必然重写，写得越少越好。
 * 所有"消息怎么变成回答"都在 Agent 侧，这里连 AgentPort 都不直接碰。
 */
@RestController
@RequestMapping("/api/chat")
public class HttpChatController {

    private static final Logger log = LoggerFactory.getLogger(HttpChatController.class);

    /**
     * 本通道的标识，与 {@code OutboundDelivery#channel()} 同口径（A05）。
     *
     * <p>HTTP 不能主动推——请求一答完连接就断了。它在这里是为了让审计里
     * "这一轮从哪个通道来"有值可查，不是为了推送：结果只从这次请求里回去。
     */
    static final String CHANNEL = "http";

    private final HttpChatChannel channel;

    private final HttpPrincipalResolver principalResolver;

    private final HttpChannelProperties properties;

    public HttpChatController(HttpChatChannel channel,
                              HttpPrincipalResolver principalResolver,
                              HttpChannelProperties properties) {
        this.channel = channel;
        this.principalResolver = principalResolver;
        this.properties = properties;
    }

    /**
     * 同步问答：一句话进、一整段回答出。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AskResponse ask(@RequestBody AskRequest request,
                           @RequestHeader(value = HttpPrincipalResolver.USER_HEADER, required = false) String user,
                           @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER, required = false) String role,
                           @RequestHeader(value = HttpPrincipalResolver.CHAT_ID_HEADER, required = false) String chatId) {
        OutboundMessage answer = channel.ask(inbound(request, user, role, chatId));
        // runId 跟着回答一起出去：它是"这一轮"的编号，用来串日志（ADR-0027）。
        // <b>它不是续跑令牌</b>：拿它回来接不上任何东西——连续性靠会话，不靠这个 id。
        // 命令类回执没有编号，那是 null。
        return new AskResponse(answer.chatId(), answer.text(), answer.runId());
    }

    /**
     * 流式问答：立刻返回一个 {@code SseEmitter}，逐块推 {@code chunk} 事件，最后推 {@code done}。
     *
     * <p>关键在于不在请求线程上等 Agent——{@code channel.stream} 是异步的，
     * 所以这里的方法体执行完就把 emitter 交回容器，Tomcat 线程随即释放。
     *
     * <p>两件事要配对：
     * <ul>
     *   <li><b>结束信号由 finalChunk 驱动</b>：{@code done} 事件在收到 finalChunk 那一块时发出，
     *       而 SSE 连接本身由 future 收尾。future 只负责异常路径，不再重复发结束事件——
     *       两套结束信号并存的话，照着 {@code AgentPort} 契约实现的下一个通道必然踩坑。</li>
     *   <li><b>客户端消失要切断上游</b>：超时或断开时 {@code cancel(true)} 中断模型调用。
     *       emitter 死了还让 {@code blockLast()} 跑完整条流，是白烧 token 加钉死线程。
     *       中断能生效的前提写在 {@code AgentPort#stream} 的契约里。</li>
     * </ul>
     */
    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter stream(@RequestBody AskRequest request,
                              @RequestHeader(value = HttpPrincipalResolver.USER_HEADER, required = false) String user,
                              @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER, required = false) String role,
                              @RequestHeader(value = HttpPrincipalResolver.CHAT_ID_HEADER, required = false) String chatId) {
        InboundMessage inbound = inbound(request, user, role, chatId);

        SseEmitter emitter = new SseEmitter(properties.getStreamTimeout().toMillis());
        CompletableFuture<Void> agent;
        try {
            agent = channel.stream(inbound, chunk -> sendChunk(emitter, chunk));
        } catch (RejectedExecutionException exception) {
            // 池满是同步拒绝：提交那一刻就抛，emitter 还没交出去，按过载而不是按请求错误处理
            log.warn("流式线程池已满，拒绝 chatId={}", inbound.chatId());
            throw new TooBusyException();
        }

        emitter.onTimeout(() -> agent.cancel(true));
        emitter.onError(error -> agent.cancel(true));
        emitter.onCompletion(() -> agent.cancel(true));

        agent.whenComplete((ignored, error) -> {
            if (error instanceof CancellationException) {
                return; // 我们自己取消的（客户端已走），emitter 已由容器收尾
            }
            if (error != null) {
                log.warn("流式回答失败，chatId={}", inbound.chatId(), error);
                emitter.completeWithError(error);
                return;
            }
            emitter.complete();
        });

        return emitter;
    }

    /**
     * 认人失败是"不该进来"，回 401；不给 500，也不在响应里暴露系统里登记了哪些角色。
     */
    @ExceptionHandler(PrincipalRejectedException.class)
    public ResponseEntity<ErrorResponse> onPrincipalRejected(PrincipalRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(exception.reason()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> onInvalidRequest(InvalidRequestException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(exception.getMessage()));
    }

    /** 服务过载而不是请求错：流式线程池满时同步拒绝。 */
    @ExceptionHandler(TooBusyException.class)
    public ResponseEntity<ErrorResponse> onTooBusy(TooBusyException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(exception.getMessage()));
    }

    private InboundMessage inbound(AskRequest request, String user, String role, String chatId) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new InvalidRequestException("text 不能为空");
        }
        var principal = principalResolver.resolve(user, role);
        return new InboundMessage(
                principal,
                principalResolver.chatId(principal.userId(), chatId),
                request.text(),
                Instant.now(),
                null,
                // 通道名进去之后，审批卡片与结果才知道"送回哪儿"（ADR-0027）。
                // HTTP 自己不能推（连接早断了）：它的结果由轮询取回去。
                CHANNEL,
                notifyPolicyOf(request));
    }

    /**
     * 请求里点名的通知策略。
     *
     * <p>拼错就 400，不静默退回默认：{@code silent} 与 {@code done_only} 的差别是
     * "要不要去找人"，静默退回的那个方向是"该说的一次没说"，
     * 那种错在事后表现为"结果去哪了"，没人会想到是请求里少写一个字母。
     */
    private static NotifyPolicy notifyPolicyOf(AskRequest request) {
        String asked = request == null ? null : request.notifyPolicy();
        if (asked == null || asked.isBlank()) {
            return NotifyPolicy.DEFAULT;
        }
        try {
            return NotifyPolicy.fromColumn(asked.strip());
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException("未知的 notifyPolicy：" + asked.strip()
                    + "（可选 done_only / silent / state_changes）");
        }
    }

    private static void sendChunk(SseEmitter emitter, OutboundMessage chunk) {
        if (chunk.finalChunk()) {
            // 契约：finalChunk 是唯一正常结束信号（文本可能为空），done 事件在这里发。
            // 连接本身交给 future 收尾，异常路径同样由 future 负责
            sendEvent(emitter, "done", "");
            return;
        }
        if (!chunk.text().isEmpty()) {
            sendEvent(emitter, "chunk", chunk.text());
        }
    }

    /**
     * 客户端可能已经断开：那时 send 会抛异常。抛出去会让后续块全部中断、
     * 连接吊到超时，所以这里吞掉并结束 emitter——连接已经没了，没有更好的处理。
     */
    private static void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    /**
     * <p><b>没有 {@code runId}</b>（ADR-0027）：客户端不能"带着上一轮的 id 回来接着做"。
     * 那种能力要求一件事能跨请求活着，而 OpenClaw 里根本不存在——连续性靠会话键，
     * 同一段对话自然有上下文。留着这个字段的话，它会静默变成一个没人读的摆设，
     * 下一个人会以为"带上它就能续"。
     *
     * @param text         这一轮说的话
     * @param notifyPolicy 这一轮的通知策略（A05），不写走 {@link NotifyPolicy#DEFAULT}。
     *                     定时任务那一类"跑完放着"的事传 {@code silent}
     */
    public record AskRequest(String text, String notifyPolicy) {

        public AskRequest(String text) {
            this(text, null);
        }
    }

    /** @param runId 这一轮的编号（ADR-0027）。串日志用，<b>但不能用来续跑</b> */
    public record AskResponse(String chatId, String text, String runId) {
    }

    public record ErrorResponse(String error) {
    }

    /** 请求体本身不合法，与"身份不合法"分开处理，便于调用方区分。 */
    public static class InvalidRequestException extends RuntimeException {

        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
