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
     * <p>返回的 future 有两个用途：正常结束时调用方收尾连接；
     * 出错时 future 异常完成，调用方据此报错而不是让异常消失在线程池里——
     * 这是"静默失败"最容易发生的地方，一次工具调用抛异常如果不接住，
     * 客户端只会看到 SSE 连接挂起直到超时。
     *
     * <p><b>取消语义</b>：{@code CompletableFuture.cancel(true)} 的
     * {@code mayInterruptIfRunning} 在 JDK 里不生效（文档原话：此值无效果），
     * 所以中断由这里自己送——记录执行线程，future 一旦被取消就打断它，
     * 让 {@code AgentPort.stream} 里阻塞的模型调用立刻结束。
     * 客户端都断开了还把整条流烧完，是白烧 token 加钉死线程。
     *
     * <p>因此不能用 {@code CompletableFuture.runAsync(..., streamExecutor)} 一行代替：
     * 它返回的是普通 {@code CompletableFuture}，取消时同样不送中断，而且把任务包进内部的
     * {@code AsyncRun}，拿不到跑它的线程，连自己补都补不上。
     * （顺带一提：池满时 {@code runAsync} 是<b>同步</b>抛 {@code RejectedExecutionException}
     * 的，这一点和这里等价，不是选它的理由。）
     *
     * <p><b>打断池线程为什么是安全的</b>（自己送中断必须回答的问题）：
     * 前提是这个线程此刻一定还在跑我们的任务，由 future 的一次性状态保证——
     * 任务正常结束后 future 已是 completed，{@code cancel()} 返回 false 且<b>不触发</b>
     * {@code whenComplete} 回调，所以不会出现"线程已归还给池、之后才被打断、污染下一个任务"。
     * 只有一个窄窗口：取消与完成几乎同时发生，中断位可能带进下一次 {@code getTask()}；
     * 但 {@code workQueue.take()} 会立刻抛 {@code InterruptedException} 并顺带清掉标志位，
     * 不会污染后续任务。
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
