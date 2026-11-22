package io.github.claw4j.channel.http;

import io.github.claw4j.domain.audit.MessageReason;
import io.github.claw4j.channel.spi.ChatChannel;
import io.github.claw4j.domain.audit.MessageOutcome;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.MessageAudit;
import io.github.claw4j.domain.port.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * HTTP+SSE 通道：M0 唯一能保证演示的通道，不依赖任何外部账号。
 *
 * <p>它自己没有连接要维护——端点由 servlet 容器挂载，所以 {@link #start()} 只记一行日志。
 * 真正属于它的是"入站怎么交出去"这件事，尤其是流式：{@link AgentPort#stream} 是阻塞的
 * （domain 不依赖 Reactor，见 ADR-0010），一次 Text-to-SQL 可能十几秒，
 * 绝不能把它跑在 Tomcat 的请求线程上。所以 {@link #stream} 把活交给业务线程池，
 * 立即返回一个 {@code CompletableFuture}，控制器拿到后立刻返回 {@code SseEmitter}。
 *
 * <p>这个"立刻返回 + 异步推"的形状是照着飞书通道设计的（飞书要求 3 秒内 ACK，
 * 否则重推）。先在 HTTP 上把它跑通，M2 换成长连接时只是把 emitter 换成主动推送接口。
 */
public class HttpChatChannel implements ChatChannel {

    private static final Logger log = LoggerFactory.getLogger(HttpChatChannel.class);

    private final AgentPort agentPort;

    private final Executor streamExecutor;

    /** 消息审计（ADR-0037 票 04）。默认空实现：没有审计设施时不写消息行。 */
    private final MessageAudit messageAudit;

    public HttpChatChannel(AgentPort agentPort, Executor streamExecutor) {
        this(agentPort, streamExecutor, MessageAudit.NONE);
    }

    /**
     * 带上消息审计的那一份。
     *
     * <p>HTTP 是唯一"答案随响应回去"的通道：没有主动推，于是 {@code ConversationDelivery}
     * 那一侧写不出这一行（它会诚实地什么都不写）。投递事实只在这里——响应真的交回给调用方的那一刻。
     *
     * @param agentPort 跑一轮的那一侧
     * @param streamExecutor 流式用的线程池
     * @param messageAudit 消息审计，null 时按空实现
     */
    public HttpChatChannel(AgentPort agentPort, Executor streamExecutor, MessageAudit messageAudit) {
        this.agentPort = Objects.requireNonNull(agentPort, "agentPort");
        this.streamExecutor = Objects.requireNonNull(streamExecutor, "streamExecutor");
        this.messageAudit = messageAudit == null ? MessageAudit.NONE : messageAudit;
    }

    @Override
    public String id() {
        return "http";
    }

    @Override
    public void start() {
        log.info("HTTP 通道就绪：POST /api/chat（同步）、POST /api/chat/stream（SSE）。端点由 servlet 容器挂载，没有需要建立的连接");
    }

    @Override
    public void stop() {
        log.info("HTTP 通道关闭");
    }

    /**
     * 一次性问答。调用方自己在请求线程上等着，同步接口本来就是这个语义。
     */
    public OutboundMessage ask(InboundMessage message) {
        long startedAt = System.nanoTime();
        OutboundMessage answer = agentPort.reply(message);
        // 同步接口的"投递"就是这次方法返回：调用方拿到的就是它。
        audit(message, answer.runId(), answer.text(), startedAt);
        return answer;
    }

    /**
     * 记一条出站消息（ADR-0037 票 04）。
     *
     * <p>三类结果：有正文就是 {@code sent}；空正文是 {@code suppressed / no_visible_payload}
     * ——没有人期待它出现，它不是失败；流式断在半路是 {@code unknown}，因为已经发出去的那几帧
     * 对方收到没有我们不知道，编一个 {@code failed} 就是在替客户端下结论。
     *
     * @param message 入站消息（用来取通道、地址与私聊/群）
     * @param runId 这一轮的编号，可为 null
     * @param text 交出去的正文，可为 null
     * @param startedAtNanos 开始那一刻（{@link System#nanoTime()}）
     */
    private void audit(InboundMessage message, String runId, String text, long startedAtNanos) {
        MessageOutcome outcome;
        String reason = null;
        if (text == null || text.isBlank()) {
            outcome = MessageOutcome.SUPPRESSED;
            reason = MessageReason.NO_VISIBLE_PAYLOAD;
        } else {
            outcome = MessageOutcome.SENT;
        }
        audit(message, runId, outcome, reason, null, startedAtNanos);
    }

    private void audit(InboundMessage message, String runId, MessageOutcome outcome, String reason,
                       String failureStage, long startedAtNanos) {
        try {
            messageAudit.outbound(new MessageAudit.Outbound(runId, null, message.channel(),
                    message.chatId(), message.chatId(), outcome, reason, failureStage, "text",
                    Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L),
                    message.direct()));
        } catch (RuntimeException failure) {
            // 记账不是回答的前置条件：写不进去只丢一行，回答照给。
            log.debug("写 HTTP 投递审计时出错：chatId={}", message.chatId(), failure);
        }
    }

    /**
     * 流式问答：提交到业务线程池，立刻返回。
     *
     * <p>返回的 future 有两个用途：正常结束时调用方收尾连接；出错时异常完成，调用方据此报错，
     * 而不是让异常消失在线程池里——那样客户端只会看到 SSE 连接挂到超时。
     *
     * <p>取消要自己送中断：{@code CompletableFuture.cancel(true)} 的
     * {@code mayInterruptIfRunning} 在 JDK 里不生效，所以这里记下执行线程，future 一旦被取消
     * 就打断它，让 {@code AgentPort.stream} 里阻塞的模型调用立刻结束。因此不能用
     * {@code runAsync(..., streamExecutor)} 代替：它同样不送中断，而且把任务包进内部的
     * {@code AsyncRun}，拿不到跑它的线程。客户端都断开了还把整条流烧完，是白烧 token 加钉死线程。
     *
     * <p>打断池线程之所以安全，靠的是 future 的一次性状态：任务正常结束后 future 已 completed，
     * {@code cancel()} 返回 false 且不触发 {@code whenComplete}，不会出现线程已归还、之后才被打断
     * 的情况。唯一的窄窗口是取消与完成几乎同时发生，中断位可能带进下一次 {@code getTask()}，
     * 但 {@code workQueue.take()} 会立刻抛 {@code InterruptedException} 并清掉标志位。
     */
    public CompletableFuture<Void> stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(onChunk, "onChunk");

        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        long startedAt = System.nanoTime();
        AtomicBoolean delivered = new AtomicBoolean();
        // 有没有真的吐出过字：只有结束帧（空文本）时这一路是"模型什么都没说"，
        // 那是被抑制，不是送达——与同步那条的 blank 判断同一个口径。
        AtomicBoolean sawText = new AtomicBoolean();
        AtomicReference<String> runId = new AtomicReference<>();
        // 先交付、再记账：写不出去的那一块不算送到。最后那一帧到了才算这一路送完。
        Consumer<OutboundMessage> deliveredThenCounted = chunk -> {
            onChunk.accept(chunk);
            if (chunk.runId() != null) {
                runId.set(chunk.runId());
            }
            if (chunk.text() != null && !chunk.text().isBlank()) {
                sawText.set(true);
            }
            if (chunk.finalChunk()) {
                delivered.set(true);
            }
        };
        future.whenComplete((ignored, error) -> {
            if (future.isCancelled()) {
                Thread thread = worker.get();
                if (thread != null) {
                    thread.interrupt();
                }
            }
        });

        try {
            streamExecutor.execute(() -> {
                worker.set(Thread.currentThread());
                if (future.isCancelled()) {
                    return; // 提交之后、开跑之前就被取消
                }
                try {
                    agentPort.stream(message, deliveredThenCounted);
                    if (delivered.get() && sawText.get()) {
                        audit(message, runId.get(), MessageOutcome.SENT, null, null, startedAt);
                    } else if (delivered.get()) {
                        audit(message, runId.get(), MessageOutcome.SUPPRESSED,
                                MessageReason.NO_VISIBLE_PAYLOAD, null, startedAt);
                    }
                    future.complete(null);
                } catch (Throwable error) {
                    // 断在半路：已经出去的那几帧对方收到没有，我们不知道——记 unknown 而不是 failed。
                    audit(message, runId.get(), MessageOutcome.UNKNOWN, null, "platform_send", startedAt);
                    if (!future.isCancelled()) {
                        future.completeExceptionally(error);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            // 池满是同步拒绝：抛给调用方转 503，future 作废
            future.cancel(false);
            throw exception;
        }
        return future;
    }
}
