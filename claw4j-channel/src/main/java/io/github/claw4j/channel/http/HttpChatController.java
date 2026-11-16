package io.github.claw4j.channel.http;

import io.github.claw4j.domain.NotifyPolicy;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.question.QuestionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
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

    /**
     * 交互提问的登记表。HTTP 没有推送面（连接是请求-响应的），所以这里的两个接口是它唯一的答复方式：
     * 同步请求挂着的时候，另一个连接先 {@code GET /questions} 看有没有人在等，再 {@code POST /answer}。
     *
     * <p>用 {@code ObjectProvider} 而不是直接注入：没有提问设施（关了 {@code claw4j.tools.ask.enabled}）
     * 时这两个接口回 404 语义的空答案，而不是让整个 HTTP 通道起不来。
     */
    private final ObjectProvider<QuestionGate> questions;

    public HttpChatController(HttpChatChannel channel,
                              HttpPrincipalResolver principalResolver,
                              HttpChannelProperties properties,
                              ObjectProvider<QuestionGate> questions) {
        this.channel = channel;
        this.principalResolver = principalResolver;
        this.properties = properties;
        this.questions = questions;
    }

    /**
     * 同步问答：一句话进、一整段回答出。
     *
     * @param request 这一轮说的话与通知策略
     * @param user 请求头里的用户标识，允许缺失（缺失即 401）
     * @param role 请求头里的角色名，允许缺失
     * @param chatId 请求头里的会话标识，允许缺失
     * @return 这一轮的编号、会话标识与整段回答
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AskResponse ask(@RequestBody AskRequest request,
                           @RequestHeader(value = HttpPrincipalResolver.USER_HEADER, required = false) String user,
                           @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER, required = false) String role,
                           @RequestHeader(value = HttpPrincipalResolver.CHAT_ID_HEADER, required = false) String chatId) {
        OutboundMessage answer = channel.ask(inbound(request, user, role, chatId));
        // runId 跟着回答一起出去：它是这一轮的编号，用来串日志（ADR-0027）。
        // 它不是续跑令牌：拿它回来接不上任何东西，连续性靠会话，不靠这个 id。
        // 命令类回执没有编号，那是 null。
        return new AskResponse(answer.chatId(), answer.text(), answer.runId());
    }

    /**
     * 流式问答：立刻返回一个 {@code SseEmitter}，逐块推 {@code chunk} 事件，最后推 {@code done}。
     *
     * <p>不在请求线程上等 Agent：{@code channel.stream} 是异步的，方法体执行完就把 emitter
     * 交回容器，Tomcat 线程随即释放。
     *
     * <p>两件事要配对。结束信号由 finalChunk 驱动：{@code done} 在收到 finalChunk 那一块时发出，
     * SSE 连接本身由 future 收尾，future 只负责异常路径；两套结束信号并存的话，
     * 照着 {@code AgentPort} 契约实现的下一个通道必然踩坑。客户端消失要切断上游：超时或断开时
     * {@code cancel(true)} 中断模型调用，中断能生效的前提写在 {@code AgentPort#stream} 的契约里。
     *
     * @param request 这一轮说的话与通知策略
     * @param user 请求头里的用户标识，允许缺失（缺失即 401）
     * @param role 请求头里的角色名，允许缺失
     * @param chatId 请求头里的会话标识，允许缺失
     * @return 已挂上超时与取消回调的 emitter
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
     * 这段对话里有没有正在等人回答的问题。
     *
     * <p>它是 HTTP 这一侧的"提问面板"：{@code /ask} 那一轮正挂在模型调用里等答案，客户端在另一个
     * 连接上问「有没有要我问的」，拿到之后再 {@code POST /answer}。
     *
     * @param user 请求头里的用户标识
     * @param role 请求头里的角色名
     * @param chatId 请求头里的会话标识
     * @return 正在等的问题；一条都没有时是空表
     */
    @GetMapping(path = "/questions", produces = MediaType.APPLICATION_JSON_VALUE)
    public PendingQuestionsResponse pendingQuestions(
            @RequestHeader(value = HttpPrincipalResolver.USER_HEADER, required = false) String user,
            @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER, required = false) String role,
            @RequestHeader(value = HttpPrincipalResolver.CHAT_ID_HEADER, required = false) String chatId) {
        var principal = principalResolver.resolve(user, role);
        String chatRef = principalResolver.chatId(principal.userId(), chatId);
        List<PendingQuestion> pending = gate().pending(CHANNEL, chatRef).stream()
                .flatMap(ask -> ask.questions().stream())
                .map(question -> new PendingQuestion(question.header(), question.text(),
                        question.options(), question.multiSelect()))
                .toList();
        return new PendingQuestionsResponse(pending);
    }

    /**
     * 回答一个正在等的问题。
     *
     * <p>与 IM 那条路走同一个 {@link QuestionGate#answer}：认人、比对权限、解析答案都在登记表里，
     * 这里只做传输。答复的人与提问的人权限不一致时返回 403——那句话不会被当成答案。
     *
     * @param request 说了什么
     * @param user 请求头里的用户标识
     * @param role 请求头里的角色名
     * @param chatId 请求头里的会话标识
     * @return 204：这个答复已被那一轮收下。409：这段对话没有在等答案的问题
     */
    @PostMapping(path = "/answer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> answer(@RequestBody AnswerRequest request,
                                       @RequestHeader(value = HttpPrincipalResolver.USER_HEADER, required = false) String user,
                                       @RequestHeader(value = HttpPrincipalResolver.ROLE_HEADER, required = false) String role,
                                       @RequestHeader(value = HttpPrincipalResolver.CHAT_ID_HEADER, required = false) String chatId) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new InvalidRequestException("text 不能为空");
        }
        var principal = principalResolver.resolve(user, role);
        String chatRef = principalResolver.chatId(principal.userId(), chatId);
        boolean consumed = gate().answer(CHANNEL, chatRef, principal, request.text());
        return consumed ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /** 没有提问设施时按"没有人在等"处理，两个接口都不会因此 500。 */
    private QuestionGate gate() {
        QuestionGate gate = questions == null ? null : questions.getIfAvailable();
        return gate == null ? QuestionGate.unsupported() : gate;
    }

    /**
     * 认人失败是「不该进来」，回 401；不给 500，也不在响应里暴露系统里登记了哪些角色。
     *
     * @param exception 认人被拒的原因
     * @return 401 与拒绝原因
     */
    @ExceptionHandler(PrincipalRejectedException.class)
    public ResponseEntity<ErrorResponse> onPrincipalRejected(PrincipalRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(exception.reason()));
    }

    /**
     * 请求体不合法，回 400。
     *
     * @param exception 不合法之处
     * @return 400 与原因
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> onInvalidRequest(InvalidRequestException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(exception.getMessage()));
    }

    /**
     * 服务过载而不是请求错，回 503。
     *
     * @param exception 过载信号
     * @return 503 与原因
     */
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
     * <p>拼错就 400，不静默退回默认：{@code silent} 与 {@code done_only} 的差别是要不要去找人，
     * 静默退回的方向是该说的一次没说，而那种错事后表现为结果去哪了，
     * 没人会想到是请求里少写一个字母。
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
     * 客户端可能已经断开，那时 send 会抛异常。抛出去会让后续块全部中断、连接吊到超时，
     * 所以这里吞掉并结束 emitter——连接已经没了，没有更好的处理。
     */
    private static void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    /**
     * 一轮问答的请求体。
     *
     * <p>没有 {@code runId}（ADR-0027）：客户端不能带着上一轮的 id 回来接着做。那种能力要求
     * 一件事能跨请求活着，而连续性靠会话：同一段对话自然有上下文（OpenClaw 也按会话聚合，
     * {@code docs/concepts/session.md:141}）。留着这个字段的话，它会静默变成一个没人读的摆设，
     * 下一个人会以为带上它就能续。
     *
     * @param text 这一轮说的话
     * @param notifyPolicy 这一轮的通知策略（A05）；不写就走 {@link NotifyPolicy#DEFAULT}，
     *     定时任务那一类跑完放着的事传 {@code silent}
     */
    public record AskRequest(String text, String notifyPolicy) {

        public AskRequest(String text) {
            this(text, null);
        }
    }

    /**
     * 一轮问答的回答。
     *
     * @param chatId 这轮所属的会话标识
     * @param text 回答的整段文本
     * @param runId 这一轮的编号（ADR-0027），串日志用，不能用来续跑
     */
    public record AskResponse(String chatId, String text, String runId) {
    }

    /**
     * 出错的响应体。
     *
     * @param error 错误原因
     */
    public record ErrorResponse(String error) {
    }

    /**
     * 一个正在等答案的问题。不带宽的 {@code creator}：客户端不需要知道是谁在问，
     * 它自己就是那个人。
     *
     * @param header 短标签
     * @param question 完整问句
     * @param options 可选项
     * @param multiSelect 能不能多选
     */
    public record PendingQuestion(String header, String question, List<QuestionGate.Option> options,
                                  boolean multiSelect) {
    }

    /**
     * 待答问题清单。
     *
     * @param questions 正在等的问题，可能为空
     */
    public record PendingQuestionsResponse(List<PendingQuestion> questions) {
    }

    /**
     * 回答的请求体。
     *
     * @param text 说了什么：数字、选项名，或者自由文本
     */
    public record AnswerRequest(String text) {
    }

    /** 请求体本身不合法，与"身份不合法"分开处理，便于调用方区分。 */
    public static class InvalidRequestException extends RuntimeException {

        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
