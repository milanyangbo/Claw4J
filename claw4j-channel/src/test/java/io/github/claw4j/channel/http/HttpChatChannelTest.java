package io.github.claw4j.channel.http;

import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通道的形状：入站消息进、出站消息出。
 *
 * <p>两条必须成立的性质：
 * <ol>
 *   <li>流式调用不占住调用线程——{@code AgentPort.stream} 是阻塞的，堵在 Tomcat 请求线程上
 *       等于并发上限变成线程池大小。</li>
 *   <li>Agent 抛异常要能从 future 上看到。线程池里的异常默认无声消失，
 *       表现是客户端 SSE 挂着等到超时，排查起来极难。</li>
 * </ol>
 */
class HttpChatChannelTest {

    private static InboundMessage inbound() {
        return new InboundMessage(Principal.readOnly("u-1"), "http:u-1", "华东区上周成交额");
    }

    @Test
    void 同步问答原样交出Agent的结果() {
        OutboundMessage expected = OutboundMessage.of("http:u-1", "42 万");
        HttpChatChannel channel = new HttpChatChannel(new StubAgentPort(expected, List.of()), Runnable::run);

        assertSame(expected, channel.ask(inbound()));
    }

    @Test
    void 流式问答不占住调用线程() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            HttpChatChannel channel = new HttpChatChannel(new BlockingAgentPort(started, release), pool);

            CompletableFuture<Void> streaming = channel.stream(inbound(), message -> {
            });

            assertTrue(started.await(2, TimeUnit.SECONDS), "任务应当已经跑起来了");
            assertFalse(streaming.isDone(), "Agent 还没回答完，future 就不该结束——说明活确实在别的线程上");

            release.countDown();
            streaming.get(2, TimeUnit.SECONDS);
            assertTrue(streaming.isDone());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void Agent抛异常时future异常完成而不是被线程池吞掉() {
        HttpChatChannel channel = new HttpChatChannel(new FailingAgentPort(), Runnable::run);

        CompletableFuture<Void> streaming = channel.stream(inbound(), message -> {
        });

        assertTrue(streaming.isCompletedExceptionally(), "异常必须传到调用方，否则客户端只能干等超时");
    }

    @Test
    void 流式逐块回调且以结尾块收尾() {
        HttpChatChannel channel = new HttpChatChannel(
                new StubAgentPort(OutboundMessage.of("http:u-1", "整段"), List.of("第", "一", "块")),
                Runnable::run);

        var collected = new ArrayList<OutboundMessage>();
        channel.stream(inbound(), collected::add).join();

        assertEquals(4, collected.size(), "三块内容加一个结尾块");
        assertEquals("第", collected.get(0).text());
        assertTrue(collected.get(collected.size() - 1).finalChunk(), "结尾块必须带标记，通道据此结束 SSE");
    }

    /**
     * 客户端消失时，上游必须能被切断：{@code cancel(true)} 要一路传到
     * 正在执行 {@code AgentPort.stream} 的线程。否则 emitter 死了模型还在跑，
     * 白烧 token 还钉死线程——32 个线程的池子，挂住几个就废了。
     *
     * <p>契约（{@code AgentPort#stream}）要求实现响应中断，本测试钉住的是
     * 通道这一半：future 的中断确实传到了执行线程。
     */
    @Test
    void 客户端断开后取消能中断正在执行的Agent() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            AgentPort interruptible = new AgentPort() {
                @Override
                public OutboundMessage reply(InboundMessage message) {
                    return OutboundMessage.of(message.chatId(), "");
                }

                @Override
                public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
                    started.countDown();
                    try {
                        // 模拟一次长模型调用：睡到被中断为止
                        Thread.sleep(30_000);
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                }
            };
            HttpChatChannel channel = new HttpChatChannel(interruptible, pool);

            CompletableFuture<Void> streaming = channel.stream(inbound(), message -> {
            });
            assertTrue(started.await(2, TimeUnit.SECONDS), "Agent 应该已经跑起来了");

            streaming.cancel(true);

            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "取消必须中断执行线程，而不是让模型跑完");
            assertTrue(streaming.isCancelled());
        } finally {
            pool.shutdownNow();
        }
    }

    private record StubAgentPort(OutboundMessage reply, List<String> chunks) implements AgentPort {

        @Override
        public OutboundMessage reply(InboundMessage message) {
            return reply;
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            chunks.forEach(chunk -> onChunk.accept(OutboundMessage.chunk(message.chatId(), chunk)));
            onChunk.accept(OutboundMessage.of(message.chatId(), ""));
        }
    }

    private record BlockingAgentPort(CountDownLatch started, CountDownLatch release) implements AgentPort {

        @Override
        public OutboundMessage reply(InboundMessage message) {
            return OutboundMessage.of(message.chatId(), "");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            onChunk.accept(OutboundMessage.of(message.chatId(), "完成"));
        }
    }

    private record FailingAgentPort() implements AgentPort {

        @Override
        public OutboundMessage reply(InboundMessage message) {
            throw new IllegalStateException("工具调用失败");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            throw new IllegalStateException("工具调用失败");
        }
    }
}
