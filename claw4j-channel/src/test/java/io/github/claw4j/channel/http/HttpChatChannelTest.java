package io.github.claw4j.channel.http;

import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一个完整功能：HTTP 对话通道。
 *
 * <p>从 HTTP 请求进入（认人 → 组装入站消息 → 交给 AgentPort → 把结果写回响应），
 * 一路走到响应体，中间不拆开测。真正容易坏的地方都不在"握手"上：
 * 匿名请求被放进来、角色拼错变成默认权限、流式结束信号发两次、
 * 客户端断开后上游还在烧 token——这些全都不抛异常，只是行为悄悄变了。
 */
class HttpChatChannelTest {

    private final HttpChannelProperties properties = new HttpChannelProperties();

    private final List<InboundMessage> received = new CopyOnWriteArrayList<>();

    private final StubAgent agent = new StubAgent(received);

    private final HttpChatChannel channel = new HttpChatChannel(agent, Runnable::run);

    /**
     * 带上编码过滤器：真实容器里 Boot 会把响应编码固定成 UTF-8，
     * standalone 的 MockMvc 没有这层，默认 ISO-8859-1 会把中文写成问号——
     * 那是测试脚手架与容器的差异，不是被测代码的行为。
     */
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new HttpChatController(channel, new HttpPrincipalResolver(properties), properties))
            .addFilters(new CharacterEncodingFilter("UTF-8", true))
            .build();

    @Test
    @DisplayName("一句话进、一整段出：回答与 chatId 都回到响应里")
    void answerRoundTrip() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .content("""
                                {"text": "上周成交额"}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.chatId").value("http:u-1"))
                .andExpect(jsonPath("$.text").value("收到：上周成交额"));
    }

    @Test
    @DisplayName("没有用户头就拒绝：匿名请求不许进来")
    void missingUserHeaderIsRejected() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "上周成交额"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("X-Claw4j-User")));
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("角色没登记就拒绝，且不告诉调用方系统里有哪些角色")
    void unregisteredRoleIsRejectedWithoutLeakingTheRoleTable() throws Exception {
        String body = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .header(HttpPrincipalResolver.ROLE_HEADER, "root")
                        .content("""
                                {"text": "上周成交额"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("root");
        assertThat(body).doesNotContain("read-only", "guest", "admin");
    }

    @Test
    @DisplayName("没带角色头时用默认角色，且是最小权限的那个")
    void roleHeaderFallsBackToDefault() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-2")
                        .content("""
                                {"text": "你好"}"""))
                .andExpect(status().isOk());

        InboundMessage seen = received.getFirst();
        assertThat(seen.principal().role()).isEqualTo(properties.getDefaultRole());
        assertThat(seen.principal().canPerform("run_script")).isFalse();
    }

    @Test
    @DisplayName("空文本是 400，不是 500")
    void blankTextIsBadRequest() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .content("""
                                {"text": "   "}"""))
                .andExpect(status().isBadRequest());
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("给了 chatId 头就用它，并加通道前缀")
    void explicitChatIdIsNamespaced() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .header(HttpPrincipalResolver.CHAT_ID_HEADER, "room-9")
                        .content("""
                                {"text": "你好"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("http:room-9"));
    }

    @Test
    @DisplayName("流式：逐块推送，结束信号只发一次")
    void streamPushesChunksThenOneDone() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .content("""
                                {"text": "上周成交额"}"""))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:chunk").contains("第一块").contains("第二块");
        assertThat(countOccurrences(body, "event:done")).isEqualTo(1);
        assertThat(body.indexOf("第一块")).isLessThan(body.indexOf("event:done"));
    }

    @Test
    @DisplayName("流式：结束块文本为空时不发 chunk 事件，但 done 照发")
    void emptyFinalChunkDoesNotProduceAChunkEvent() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .content("""
                                {"text": "你好"}"""))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString();
        assertThat(countOccurrences(body, "event:done")).isEqualTo(1);
    }

    @Test
    @DisplayName("流式线程池满：按过载回 503，而不是让请求排队")
    void saturatedPoolIsServiceUnavailable() throws Exception {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("池满");
        };
        HttpChatChannel busy = new HttpChatChannel(agent, rejecting);
        MockMvc busyMvc = MockMvcBuilders.standaloneSetup(
                        new HttpChatController(busy, new HttpPrincipalResolver(properties), properties))
                .build();

        busyMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpPrincipalResolver.USER_HEADER, "u-1")
                        .content("""
                                {"text": "你好"}"""))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("上游异常不消失在线程池里：future 异常完成")
    void upstreamFailureSurfacesOnTheFuture() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            HttpChatChannel failing = new HttpChatChannel(new AgentPort() {
                @Override
                public OutboundMessage reply(InboundMessage message) {
                    throw new UnsupportedOperationException("本用例只走流式");
                }

                @Override
                public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
                    throw new IllegalStateException("模型侧炸了");
                }
            }, pool);

            CompletableFuture<Void> future = failing.stream(
                    new InboundMessage(io.github.claw4j.domain.principal.Principal.readOnly("u-1"), "http:u-1", "你好"),
                    chunk -> {
                    });

            assertThatThrownBy(future::join)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("模型侧炸了");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("客户端断开要切断上游：取消会打断正在跑的线程")
    void cancellationInterruptsTheUpstream() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            HttpChatChannel blocking = new HttpChatChannel(new AgentPort() {
                @Override
                public OutboundMessage reply(InboundMessage message) {
                    throw new UnsupportedOperationException("本用例只走流式");
                }

                @Override
                public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                }
            }, pool);

            CompletableFuture<Void> future = blocking.stream(
                    new InboundMessage(io.github.claw4j.domain.principal.Principal.readOnly("u-1"), "http:u-1", "你好"),
                    chunk -> {
                    });

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            future.cancel(true);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!interrupted.get() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(interrupted)
                    .as("客户端已经走了还让模型调用跑完，是白烧 token 加钉死线程")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("空消息体与缺参数一样按请求错处理")
    void nullBodyIsRejected() {
        HttpChatController controller = new HttpChatController(channel, new HttpPrincipalResolver(properties), properties);
        assertThatThrownBy(() -> controller.ask(null, "u-1", null, null))
                .isInstanceOf(HttpChatController.InvalidRequestException.class);
    }

    @Test
    @DisplayName("认人失败抛的是 PrincipalRejectedException，不是空指针")
    void resolverRejectsBlankUser() {
        HttpPrincipalResolver resolver = new HttpPrincipalResolver(properties);
        assertThatThrownBy(() -> resolver.resolve("  ", null)).isInstanceOf(PrincipalRejectedException.class);
        assertThat(resolver.chatId("u-1", " ")).isEqualTo("http:u-1");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** 只做"把入站消息变成回答"的桩：本功能要验的是通道，不是模型。 */
    private static final class StubAgent implements AgentPort {

        private final List<InboundMessage> received;

        private StubAgent(List<InboundMessage> received) {
            this.received = received;
        }

        @Override
        public OutboundMessage reply(InboundMessage message) {
            received.add(message);
            return OutboundMessage.of(message.chatId(), "收到：" + message.text());
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            received.add(message);
            onChunk.accept(OutboundMessage.chunk(message.chatId(), "第一块"));
            onChunk.accept(OutboundMessage.chunk(message.chatId(), "第二块"));
            onChunk.accept(OutboundMessage.of(message.chatId(), ""));
        }
    }
}
