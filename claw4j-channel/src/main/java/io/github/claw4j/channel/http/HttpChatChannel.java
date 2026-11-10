package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.ChatChannel;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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

    public HttpChatChannel(AgentPort agentPort, Executor streamExecutor) {
        this.agentPort = Objects.requireNonNull(agentPort, "agentPort");
        this.streamExecutor = Objects.requireNonNull(streamExecutor, "streamExecutor");
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
        return agentPort.reply(message);
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
                    agentPort.stream(message, onChunk);
                    future.complete(null);
                } catch (Throwable error) {
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
