package io.github.claw4j.channel.http;

import io.github.claw4j.channel.http.HttpChatController.AskRequest;
import io.github.claw4j.channel.http.HttpChatController.AskResponse;
import io.github.claw4j.channel.spi.PrincipalRejectedException;
import io.github.claw4j.channel.spi.TooBusyException;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 对话通道这一个功能：一次对话请求被接下来、认出是谁、原样交给 AgentPort。
 *
 * <p>入口停在责任边界上——控制器的入参与通道的 {@code stream/ask}，不断言状态码、响应头与 SSE 分帧；
 * 断言的是 AgentPort 真正收到了什么（文本、身份、chatId）、结束信号只来一次、客户端走了有没有切断上游。
 * 这些错了都不会报错：请求照样 200，只是拿着别人的身份查库、或者客户端断线后把整条模型调用烧完。
 */
class HttpChatChannelTest {

    private static final Principal CALLER = new Principal("u-1");

    private static final String CHAT_ID = "http:u-1";

    private static InboundMessage inbound(String text) {
        return new InboundMessage(CALLER, CHAT_ID, text);
    }

    /**
     * 带通道名的那一条：控制器造出来的消息就是这样（{@code channel = "http"}）。
     *
     * <p>投递审计要用通道名与地址——{@link #inbound} 那个短构造没有通道，用它验投递会得到一行
     * {@code channel = null} 的假事实。
     */
    private static InboundMessage inboundOnHttp(String text) {
        return new InboundMessage(CALLER, CHAT_ID, text, java.time.Instant.now(), "evt-1", "http",
                io.github.claw4j.domain.NotifyPolicy.DEFAULT);
    }

    // ---------------------------------------------------------------- 通道：入站怎么交给 Agent

    @Test
    @DisplayName("同步问答原样把消息交给 Agent，不改写文本与身份")
    void askHandsTheMessageOverUnchanged() {
        TestAgent agent = new TestAgent();
        InboundMessage message = inbound("上周华东区成交额");

        OutboundMessage answer = new HttpChatChannel(agent, Runnable::run).ask(message);

        assertSame(message, agent.received.get(0));
        assertEquals(CHAT_ID, answer.chatId());
    }

    /**
     * HTTP 是唯一"答案随响应回去"的通道（ADR-0037 票 04）：投递那一行只能在这里写。
     *
     * <p>三种结果各验一次：有正文是 {@code sent}、空正文是 {@code suppressed}（没有人期待它出现，
     * 它不是失败）、流断在半路是 {@code unknown}（已经出去的那几帧对方收到没有，我们不知道）。
     * 编一个 {@code failed} 就是在替客户端下结论。
     */
    @Test
    @DisplayName("HTTP 的投递事实记在通道这一层：sent / suppressed / 断在半路是 unknown")
    void outboundDeliveryIsAuditedWhereTheAnswerLeaves() {
        List<io.github.claw4j.domain.port.MessageAudit.Outbound> audited = new ArrayList<>();
        io.github.claw4j.domain.port.MessageAudit audit =
                new io.github.claw4j.domain.port.MessageAudit() {
                    @Override
                    public void inbound(io.github.claw4j.domain.port.MessageAudit.Inbound record) {
                    }

                    @Override
                    public void outbound(io.github.claw4j.domain.port.MessageAudit.Outbound record) {
                        audited.add(record);
                    }
                };

        new HttpChatChannel(new TestAgent(), Runnable::run, audit).ask(inboundOnHttp("上周成交额"));

        assertEquals(1, audited.size());
        assertEquals(io.github.claw4j.domain.audit.MessageOutcome.SENT, audited.get(0).outcome());
        assertEquals("http", audited.get(0).channel());
        assertEquals(CHAT_ID, audited.get(0).chatRef());
        assertEquals(Boolean.TRUE, audited.get(0).direct(), "HTTP 没有群的观念，恒为私聊");

        // 空正文：被抑制，不是失败。
        audited.clear();
        new HttpChatChannel(new TestAgent(new OutboundMessage(CHAT_ID, "", true, null)), Runnable::run, audit)
                .stream(inboundOnHttp("在吗"), chunk -> { });
        assertEquals(io.github.claw4j.domain.audit.MessageOutcome.SUPPRESSED, audited.get(0).outcome(),
                "空正文没有可见内容，记抑制而不是送达");

        // 断在半路：unknown，带失败阶段。
        audited.clear();
        TestAgent broken = new TestAgent(new OutboundMessage(CHAT_ID, "半截", false, "run-9"))
                .failingWith(new IllegalStateException("客户端走了"));
        new HttpChatChannel(broken, Runnable::run, audit).stream(inboundOnHttp("讲一下"), chunk -> { });
        assertEquals(io.github.claw4j.domain.audit.MessageOutcome.UNKNOWN, audited.get(0).outcome());
        assertEquals("platform_send", audited.get(0).failureStage());
        assertEquals("run-9", audited.get(0).runId());
    }

    @Test
    @DisplayName("流式把活交给池：调用方立刻拿到 future，模型调用不在请求线程上跑")
    void streamDoesNotRunTheAgentOnTheCallingThread() {
        QueuedExecutor executor = new QueuedExecutor();
        TestAgent agent = new TestAgent(OutboundMessage.of(CHAT_ID, ""));
        List<OutboundMessage> seen = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> future = new HttpChatChannel(agent, executor).stream(inbound("问题"), seen::add);

        assertFalse(future.isDone());
        assertTrue(agent.received.isEmpty());
        executor.runAll();
        assertTrue(future.isDone());
        assertEquals("问题", agent.received.get(0).text());
    }

    @Test
    @DisplayName("流式的块按原顺序到达，收尾只有一次且在最后")
    void streamKeepsChunkOrderAndOneEnding() {
        QueuedExecutor executor = new QueuedExecutor();
        TestAgent agent = new TestAgent(
                OutboundMessage.chunk(CHAT_ID, "第一块"),
                OutboundMessage.chunk(CHAT_ID, "第二块"),
                OutboundMessage.of(CHAT_ID, ""));
        List<OutboundMessage> seen = new ArrayList<>();

        CompletableFuture<Void> future = new HttpChatChannel(agent, executor).stream(inbound("问题"), seen::add);
        executor.runAll();

        assertEquals(List.of("第一块", "第二块", ""), seen.stream().map(OutboundMessage::text).toList());
        assertEquals(1, seen.stream().filter(OutboundMessage::finalChunk).count());
        assertTrue(seen.get(seen.size() - 1).finalChunk());
        assertTrue(future.isDone());
    }

    @Test
    @DisplayName("取消发生在开跑之前：这条请求不再去调模型")
    void cancelBeforeStartSkipsTheAgent() {
        QueuedExecutor executor = new QueuedExecutor();
        TestAgent agent = new TestAgent();

        CompletableFuture<Void> future = new HttpChatChannel(agent, executor).stream(inbound("问题"), chunk -> { });
        future.cancel(true);
        executor.runAll();

        assertTrue(future.isCancelled());
        assertTrue(agent.received.isEmpty());
    }

    @Test
    @DisplayName("客户端走了就打断正在跑的模型调用：否则线程白烧到自然结束")
    void cancelWhileRunningInterruptsTheAgentCall() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            BlockingAgent agent = new BlockingAgent();
            HttpChatChannel channel = new HttpChatChannel(agent, pool);

            CompletableFuture<Void> future = channel.stream(inbound("慢慢算"), chunk -> { });
            assertTrue(agent.entered.await(5, SECONDS));

            future.cancel(true);

            assertTrue(agent.interrupted.await(5, SECONDS));
            assertTrue(future.isCancelled());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Agent 抛的异常交回给调用方，不消失在线程池里")
    void agentFailureCompletesTheFutureExceptionally() {
        QueuedExecutor executor = new QueuedExecutor();
        IllegalStateException boom = new IllegalStateException("模型挂了");
        TestAgent agent = new TestAgent().failingWith(boom);

        CompletableFuture<Void> future = new HttpChatChannel(agent, executor).stream(inbound("问题"), chunk -> { });
        executor.runAll();

        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        assertSame(boom, thrown.getCause());
    }

    @Test
    @DisplayName("池满当场拒绝：不排队、也不假装已经开跑")
    void rejectedSubmissionFailsFast() {
        TestAgent agent = new TestAgent();
        Executor full = command -> {
            throw new RejectedExecutionException("池满");
        };

        assertThrows(RejectedExecutionException.class,
                () -> new HttpChatChannel(agent, full).stream(inbound("问题"), chunk -> { }));
        assertTrue(agent.received.isEmpty());
    }

    @Test
    @DisplayName("通道标识固定，stop 可以重复调用")
    void channelHasAStableIdAndStoppableLifecycle() {
        HttpChatChannel channel = new HttpChatChannel(new TestAgent(), Runnable::run);

        assertEquals("http", channel.id());
        channel.start();
        channel.stop();
        channel.stop();
    }

    // ---------------------------------------------------------------- 控制器：认出是谁再交出去

    @Test
    @DisplayName("没有用户头、或只有空白：认不出人，请求进不到 Agent")
    void missingOrBlankUserIsRejected() {
        TestAgent agent = new TestAgent();
        HttpChatController controller = controller(agent, Runnable::run);

        assertThrows(PrincipalRejectedException.class,
                () -> controller.ask(new AskRequest("问题"), null, null));
        assertThrows(PrincipalRejectedException.class,
                () -> controller.ask(new AskRequest("问题"), "   ", null));

        assertTrue(agent.received.isEmpty());
    }

    @Test
    @DisplayName("不在白名单就拒：默认空名单意味着谁都不进，而不是谁都能进")
    void unlistedUserIsRejected() {
        TestAgent agent = new TestAgent();

        assertThrows(PrincipalRejectedException.class,
                () -> controller(agent, Runnable::run).ask(new AskRequest("问题"), "u-2", null));

        assertTrue(agent.received.isEmpty());
    }

    @Test
    @DisplayName("只带用户头就够：名单里有他就进来，不需要任何角色头")
    void userHeaderAloneIsEnough() {
        TestAgent agent = new TestAgent();

        controller(agent, Runnable::run).ask(new AskRequest("问题"), "u-1", null);

        assertEquals("u-1", agent.received.get(0).principal().userId());
        assertEquals(1, agent.received.size());
    }

    @Test
    @DisplayName("名单写成 [\"*\"] 就是不设准入：谁都进得来（与忘了配区分开）")
    void wildcardAllowlistLetsEveryoneIn() {
        TestAgent agent = new TestAgent();
        HttpChannelProperties properties = new HttpChannelProperties();
        properties.setUsers(Set.of("*"));

        newHttpController(agent, Runnable::run, properties).ask(new AskRequest("问题"), "u-anything", null);

        assertEquals("u-anything", agent.received.get(0).principal().userId());
    }

    @Test
    @DisplayName("chatId 只是会话寻址：换一个会话，人还是同一个人")
    void chatIdAddressesTheConversationNotThePerson() {
        TestAgent agent = new TestAgent();
        HttpChatController controller = controller(agent, Runnable::run);

        controller.ask(new AskRequest("问题"), "u-1", "room-42");
        controller.ask(new AskRequest("问题"), "u-1", "   ");

        assertEquals("http:room-42", agent.received.get(0).chatId());
        assertEquals("u-1", agent.received.get(0).principal().userId());
        // 空白不是会话名，回落到「一人一条对话」，而不是把空白当成 id 带下去
        assertEquals(CHAT_ID, agent.received.get(1).chatId());
        assertEquals("u-1", agent.received.get(1).principal().userId());
    }

    @Test
    @DisplayName("没有可回答的内容就拒：空问题不该占用 Agent 与线程池")
    void emptyTextNeverReachesTheAgent() {
        TestAgent agent = new TestAgent();
        HttpChatController controller = controller(agent, Runnable::run);

        assertThrows(HttpChatController.InvalidRequestException.class,
                () -> controller.ask(new AskRequest("   "), "u-1", null));
        assertThrows(HttpChatController.InvalidRequestException.class,
                () -> controller.ask(new AskRequest(null), "u-1", null));

        assertTrue(agent.received.isEmpty());
    }

    @Test
    @DisplayName("流式里 Agent 出错要当场收尾连接，而不是让客户端挂到超时")
    void failingStreamClosesTheEmitter() {
        QueuedExecutor executor = new QueuedExecutor();
        TestAgent agent = new TestAgent().failingWith(new IllegalStateException("模型挂了"));
        SseEmitter emitter = controller(agent, executor)
                .stream(new AskRequest("问题"), "u-1", null);

        executor.runAll();

        // 收尾过的 emitter 拒绝再发：异常被接住并交回连接，而不是在线程池里消失
        assertThrows(IllegalStateException.class, () -> emitter.send("再来一块"));
    }

    @Test
    @DisplayName("线程池满了按过载拒绝，而不是让请求无声排队")
    void overloadedStreamIsRejectedAsBusy() {
        TestAgent agent = new TestAgent();
        Executor full = command -> {
            throw new RejectedExecutionException("池满");
        };
        HttpChatController controller = controller(agent, full);

        assertThrows(TooBusyException.class,
                () -> controller.stream(new AskRequest("问题"), "u-1", null));
        assertTrue(agent.received.isEmpty());
    }

    /**
     * 默认装配：白名单里放上测试用的那个人。
     *
     * <p>白名单默认是空（谁都不进），所以每一条"能进来"的用例都必须显式把人放进去——
     * 这本身就是那条默认值在起作用。
     */
    private static HttpChatController controller(AgentPort agent, Executor executor) {
        HttpChannelProperties properties = new HttpChannelProperties();
        properties.setUsers(Set.of("u-1"));
        return newHttpController(agent, executor, properties);
    }

    private static HttpChatController newHttpController(AgentPort agent, Executor executor,
                                                        HttpChannelProperties properties) {
        return new HttpChatController(
                new HttpChatChannel(agent, executor),
                new HttpPrincipalResolver(properties),
                properties,
                null);
    }

    // ---------------------------------------------------------------- 边界外的替身

    /** 记录端口真正收到了什么，并按脚本回块。 */
    private static final class TestAgent implements AgentPort {

        private final List<InboundMessage> received = new CopyOnWriteArrayList<>();

        private final List<OutboundMessage> script;

        private RuntimeException failure;

        TestAgent(OutboundMessage... script) {
            this.script = List.of(script);
        }

        TestAgent failingWith(RuntimeException failure) {
            this.failure = failure;
            return this;
        }

        @Override
        public OutboundMessage reply(InboundMessage message) {
            received.add(message);
            return OutboundMessage.of(message.chatId(), "答复");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            received.add(message);
            script.forEach(onChunk);
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** 卡在模型调用里，只有中断能叫醒它。 */
    private static final class BlockingAgent implements AgentPort {

        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public OutboundMessage reply(InboundMessage message) {
            throw new UnsupportedOperationException("这条用例只走流式");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        }
    }

    /** 把提交的任务攒着，由测试决定什么时候在哪个线程上跑。 */
    private static final class QueuedExecutor implements Executor {

        private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = queued.poll()) != null) {
                task.run();
            }
        }
    }
}
