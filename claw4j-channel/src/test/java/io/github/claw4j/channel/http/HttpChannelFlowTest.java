package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import io.github.claw4j.domain.principal.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 通道的一条主流程：请求进 → 认人 → 交给 Agent → 一条或一整段出来。
 *
 * <p>这里同时守住两件在别处看不到的事：
 * <ol>
 *   <li>流式不占住调用线程，且取消能一路传到执行线程——否则 emitter 死了模型还在跑，
 *       白烧 token 还钉死线程。</li>
 *   <li>认人失败必须是 401、过载必须是 503、空问题必须是 400：这三种状态码是调用方
 *       "该重试还是该改请求"的唯一依据，混成 500 就没人能据此决策。</li>
 * </ol>
 */
class HttpChannelFlowTest {

    private final RecordingAgentPort agentPort = new RecordingAgentPort();

    private final HttpChannelProperties properties = new HttpChannelProperties();

    private final HttpPrincipalResolver resolver = new HttpPrincipalResolver(properties);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcWith(new HttpChatChannel(agentPort, Runnable::run));
    }

    @Test
    @DisplayName("同步问答原样交出 Agent 的结果")
    void 同步问答() {
        OutboundMessage expected = OutboundMessage.of("http:u-1", "42 万");
        HttpChatChannel channel = new HttpChatChannel(new StubAgentPort(expected, List.of()), Runnable::run);

        assertSame(expected, channel.ask(inbound()));
    }

    @Test
    @DisplayName("流式问答不占住调用线程")
    void 流式不占线程() throws Exception {
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
        }
        finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Agent 抛异常时 future 异常完成，而不是被线程池吞掉")
    void 异常传到调用方() {
        HttpChatChannel channel = new HttpChatChannel(new FailingAgentPort(), Runnable::run);

        CompletableFuture<Void> streaming = channel.stream(inbound(), message -> {
        });

        assertTrue(streaming.isCompletedExceptionally(), "异常必须传到调用方，否则客户端只能干等超时");
    }

    @Test
    @DisplayName("流式逐块回调，并以带标记的结尾块收尾")
    void 流式逐块与结尾块() {
        HttpChatChannel channel = new HttpChatChannel(
                new StubAgentPort(OutboundMessage.of("http:u-1", "整段"), List.of("第", "一", "块")),
                Runnable::run);

        var collected = new ArrayList<OutboundMessage>();
        channel.stream(inbound(), collected::add).join();

        assertEquals(4, collected.size(), "三块内容加一个结尾块");
        assertEquals("第", collected.get(0).text());
        assertTrue(collected.get(collected.size() - 1).finalChunk(), "结尾块必须带标记，通道据此结束 SSE");
    }

    @Test
    @DisplayName("客户端断开后取消能中断正在执行的 Agent")
    void 取消能中断() throws Exception {
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
                        Thread.sleep(30_000);
                    }
                    catch (InterruptedException exception) {
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
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("解析出的身份原样交给 Agent；缺省角色只读、缺省会话键回落到用户标识")
    void 身份原样交给Agent() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .header(HttpPrincipalResolver.ROLE_HEADER, "admin")
                        .header(HttpPrincipalResolver.CHAT_ID_HEADER, "chat-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"华东区上周成交额\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("http:chat-1"))
                .andExpect(jsonPath("$.text").value("回答:华东区上周成交额"));

        InboundMessage delivered = agentPort.last;
        assertEquals("u-1", delivered.principal().userId());
        assertEquals("admin", delivered.principal().role());
        assertEquals("http:chat-1", delivered.chatId());
        assertEquals("华东区上周成交额", delivered.text());

        // 缺省：不带角色头时按最小权限，不带会话头时回落到用户标识
        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isOk());
        assertEquals("read-only", agentPort.last.principal().role());
        assertEquals("http:u-2", agentPort.last.chatId());
        assertFalse(agentPort.last.principal().canPerform("send_email"), "默认角色执行不了有副作用的动作");
    }

    @Test
    @DisplayName("没有用户标识、或只有空白标识的请求进不来")
    void 无身份拒绝() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized());

        assertThrows(PrincipalRejectedException.class, () -> resolver.resolve(null, "read-only"));
        assertThrows(PrincipalRejectedException.class, () -> resolver.resolve("  ", "read-only"));
    }

    @Test
    @DisplayName("登记过的角色按授权表生效，没登记的一律拒绝而不是给默认权限")
    void 角色授权() throws Exception {
        properties.setRoles(Map.of(
                "analyst",
                new HttpChannelProperties.RoleGrant(Set.of("orders", "customers"), Set.of("query-sql"))));

        Principal analyst = resolver.resolve("u-3", "analyst");

        assertTrue(analyst.canReadTable("orders"));
        assertFalse(analyst.canReadTable("salaries"), "表不在授权范围内");
        assertTrue(analyst.canPerform("query-sql"));
        assertFalse(analyst.canPerform("send-email"));

        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-4")
                        .header(HttpPrincipalResolver.ROLE_HEADER, "god")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("流式线程池满时返回 503 而不是 500")
    void 过载返回503() throws Exception {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("pool exhausted");
        };
        MockMvc rejectingMvc = mockMvcWith(new HttpChatChannel(agentPort, rejecting));

        rejectingMvc.perform(post("/api/chat/stream")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("空问题返回 400，而不是把空串喂给模型")
    void 空问题返回400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mockMvcWith(HttpChatChannel channel) {
        return MockMvcBuilders
                .standaloneSetup(new HttpChatController(channel, resolver, properties))
                .build();
    }

    private static InboundMessage inbound() {
        return new InboundMessage(Principal.readOnly("u-1"), "http:u-1", "华东区上周成交额");
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
            }
            catch (InterruptedException exception) {
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

    /** 记录收到的入站消息，用来断言"身份是被传递的，不是被 Agent 自己猜的"。 */
    private static final class RecordingAgentPort implements AgentPort {

        private InboundMessage last;

        @Override
        public OutboundMessage reply(InboundMessage message) {
            this.last = message;
            return OutboundMessage.of(message.chatId(), "回答:" + message.text());
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            this.last = message;
            onChunk.accept(OutboundMessage.of(message.chatId(), "回答:" + message.text()));
        }
    }
}
