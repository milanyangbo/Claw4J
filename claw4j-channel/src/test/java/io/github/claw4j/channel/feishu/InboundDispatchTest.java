package io.github.claw4j.channel.feishu;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书入站调度这一个功能：一条事件进来之后，群里出现几条消息、Agent 被调了几次、什么时候返回。
 *
 * <p>入口是 {@link InboundDispatcher#accept}——真正的系统边界（长连接回调）需要真凭据，
 * 而它要做的事只是"把 SDK 对象翻译成 {@link FeishuInboundEvent}"，翻译错了在假事件上没有意义。
 * 所以这里从责任开始的那一层进，断言的是**外部可观察的结果**：发送了什么、调了几次、ACK 何时返回。
 *
 * <p>这些错了都不会报错：事件照样被"确认"，只是群里多一条重复回答、少一条失败说明、
 * 或者某个人拿到了不该看的数。飞书侧看不出区别，只有重推次数在涨。
 */
class InboundDispatchTest {

    private static final String CHAT_ID = "oc_demo_chat";

    private static final String SENDER = "ou_zhang";

    private static final String EVENT_ID = "evt-0001";

    private final RecordingAgent agent = new RecordingAgent();

    private final RecordingSender sender = new RecordingSender();

    private final Map<String, Principal> directory = new ConcurrentHashMap<>();

    {
        directory.put(SENDER, Principal.readOnly("u-1"));
    }

    // ---------------------------------------------------------------- ACK：事件线程不等 Agent

    @Test
    @DisplayName("ACK 不等 Agent：accept 返回时任务还排在队列里，Agent 一次都没被调")
    void ackReturnsBeforeTheAgentRuns() {
        QueuedExecutor executor = new QueuedExecutor();
        InboundDispatcher dispatcher = dispatcher(executor);

        dispatcher.accept(event(EVENT_ID, "上周华东区成交额"));

        assertTrue(agent.received.isEmpty(), "accept 里就跑了 Agent，等于把 3 秒预算赌在模型上");
        assertTrue(sender.texts().isEmpty());

        executor.runAll();

        assertEquals(1, agent.received.size());
        InboundMessage delivered = agent.received.get(0);
        assertEquals("上周华东区成交额", delivered.text());
        assertEquals("u-1", delivered.principal().userId());
        assertEquals(CHAT_ID, delivered.chatId());
        assertEquals(EVENT_ID, delivered.eventId());
        assertEquals(List.of("答复"), sender.texts());
        assertEquals(CHAT_ID, sender.sent.get(0).chatId());
    }

    @Test
    @Timeout(20)
    @DisplayName("Agent 卡住不返回时 ACK 仍然立刻返回：否则飞书判定超时并无限重推")
    void ackStillReturnsWhileTheAgentIsStuck() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            BlockingAgent stuck = new BlockingAgent(1);
            InboundDispatcher dispatcher = new InboundDispatcher(stuck, pool, this::lookup, sender);

            dispatcher.accept(event(EVENT_ID, "慢慢算")); // 若这里等 Agent，本用例会挂到超时

            assertTrue(stuck.entered.await(5, SECONDS), "Agent 没被交出去");
            assertTrue(sender.texts().isEmpty(), "还没算完就回了话");

            stuck.release.countDown();

            assertNotNull(sender.next(5), "算完没有回推");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("两条不同事件互不排队：都在 executor 上跑起来，后一条不挡在前一条后面")
    void twoEventsRunSideBySide() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            BlockingAgent stuck = new BlockingAgent(2);
            InboundDispatcher dispatcher = new InboundDispatcher(stuck, pool, this::lookup, sender);

            dispatcher.accept(event("evt-1", "问题一"));
            dispatcher.accept(event("evt-2", "问题二"));

            // 两条都进到 Agent 里且谁也没放行：说明第二条没有被第一条串行挡住
            assertTrue(stuck.entered.await(5, SECONDS), "事件被串行化了");

            stuck.release.countDown();

            assertNotNull(sender.next(5));
            assertNotNull(sender.next(5));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 去重：重推只执行一次

    @Test
    @DisplayName("同一个 eventId 再来一次：仍然 ACK，但 Agent 只跑一次、群里只有一条回答")
    void duplicateDeliveryRunsOnceButStillAcks() {
        InboundDispatcher dispatcher = dispatcher(Runnable::run);
        FeishuInboundEvent event = event(EVENT_ID, "上周成交额");

        dispatcher.accept(event);
        dispatcher.accept(event);
        dispatcher.accept(event(EVENT_ID, "上周成交额"));

        assertEquals(1, agent.received.size(), "重推被当成新消息，模型与库都白跑一遍");
        assertEquals(1, sender.texts().size(), "群里会刷出两条一样的回答");
    }

    @Test
    @DisplayName("去重只记住一个窗口：过窗口后同一 eventId 会重新处理，表不会只增不减")
    void redeliveryAfterTheWindowRunsAgain() {
        MutableClock clock = new MutableClock();
        InboundDispatcher dispatcher = new InboundDispatcher(agent, Runnable::run, this::lookup, sender,
                new InboundDispatcher.SeenEventIds(Duration.ofMinutes(10), clock));

        dispatcher.accept(event(EVENT_ID, "问题"));
        dispatcher.accept(event(EVENT_ID, "问题"));
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        dispatcher.accept(event(EVENT_ID, "问题"));

        assertEquals(2, agent.received.size());
    }

    // ---------------------------------------------------------------- 失败：不能变成群里的沉默

    @Test
    @DisplayName("Agent 抛异常：群里出现的是一条说清失败的消息，而不是什么都没有")
    void agentFailureStillPutsAReasonInTheChat() {
        agent.failingWith(new IllegalStateException("模型连不上"));

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        List<String> texts = sender.texts();
        assertEquals(1, texts.size(), "群里的沉默分不清'还在算'和'挂了'");
        assertTrue(texts.get(0).startsWith(InboundDispatcher.FAILURE_REPLY_PREFIX));
        assertTrue(texts.get(0).contains("模型连不上"), "没带上原因，用户无从判断");
        assertEquals(CHAT_ID, sender.sent.get(0).chatId());
    }

    @Test
    @DisplayName("没登记的用户：回一句没有权限，绝不调 Agent、也不静默丢弃")
    void unknownSenderGetsANoPermissionReply() {
        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题", "ou_stranger"));

        assertTrue(agent.received.isEmpty());
        assertEquals(List.of(InboundDispatcher.NO_PERMISSION_REPLY), sender.texts());
    }

    @Test
    @DisplayName("线程池满：ACK 已发生、飞书不会重推，所以必须当场回一句，不能让用户干等")
    void overloadedPoolTellsTheUserInsteadOfDropping() {
        Executor full = command -> {
            throw new RejectedExecutionException("池满");
        };

        dispatcher(full).accept(event(EVENT_ID, "问题"));

        assertTrue(agent.received.isEmpty());
        assertEquals(List.of(InboundDispatcher.OVERLOADED_REPLY), sender.texts());
    }

    @Test
    @DisplayName("模型返回空内容：也要发一句占位，不能让群里什么都不出现")
    void blankAnswerIsNotSilentlyDropped() {
        agent.answeringWith("   ");

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        assertEquals(List.of(InboundDispatcher.EMPTY_ANSWER_REPLY), sender.texts());
    }

    // ---------------------------------------------------------------- 出站：长回答怎么切

    @Test
    @DisplayName("长回答切成多条按序发送：每段不超上限，段落顺序不变，代码块与表格各自完整")
    void longAnswerIsDeliveredInOrderWithoutBreakingBlocks() {
        String code = "```java\nString sql = \"select 1\";\n```";
        String table = "| 区域 | 成交额 |\n| --- | --- |\n| 华东 | 128.4 |";
        String first = "第一段：" + "甲".repeat(2000);
        String second = "第二段：" + "乙".repeat(2000);
        String third = "第三段：" + "丙".repeat(2000);
        agent.answeringWith(String.join("\n\n", first, code, second, table, third));

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        List<String> texts = sender.texts();
        assertTrue(texts.size() > 1, "这么长的回答不可能一条发完");
        for (String part : texts) {
            assertTrue(part.length() <= MessageSplitter.DEFAULT_MAX_CHARS,
                    "超过单条上限，这条会被平台整条拒掉");
        }

        String joined = String.join("\n", texts);
        int firstAt = joined.indexOf(first);
        int secondAt = joined.indexOf(second);
        int thirdAt = joined.indexOf(third);
        assertTrue(firstAt >= 0 && secondAt > firstAt && thirdAt > secondAt,
                "段序被打乱：" + List.of(firstAt, secondAt, thirdAt));

        assertTrue(texts.stream().anyMatch(part -> part.contains(code)), "代码块被切开了，两段都渲染成裸文本");
        assertTrue(texts.stream().anyMatch(part -> part.contains(table)), "表格被切开了，后一段丢了表头");
    }

    @Test
    @DisplayName("代码块自己超过上限：每段补上围栏带语言标记，而不是把 ``` 切成两半")
    void oversizedCodeBlockKeepsFencesBalanced() {
        StringBuilder code = new StringBuilder("```sql\n");
        for (int index = 0; index < 400; index++) {
            code.append("select ").append(index).append(" from sales_order where region = '华东';\n");
        }
        code.append("```");
        agent.answeringWith(code.toString());

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        List<String> texts = sender.texts();
        assertTrue(texts.size() > 1, "这个代码块超过单条上限，必须切开");
        for (String part : texts) {
            assertTrue(part.length() <= MessageSplitter.DEFAULT_MAX_CHARS);
            assertTrue(part.startsWith("```sql"), "每段都要重新开头围栏，否则这一段不是代码");
            assertTrue(part.endsWith("```"), "每段都要自己收尾，否则后面全被吞进代码里");
            assertEquals(2, part.lines().filter(line -> line.strip().startsWith("```")).count(),
                    "围栏必须成对");
        }

        long codeLines = texts.stream()
                .flatMap(part -> part.lines())
                .filter(line -> line.startsWith("select "))
                .count();
        assertEquals(400, codeLines, "切的是显示方式，不能少掉任何一行");
    }

    @Test
    @DisplayName("表格自己超过上限：每段重放表头，否则后几段是一堆没有列名的数字")
    void oversizedTableRepeatsTheHeaderInEveryPart() {
        String header = "| 区域 | 门店 | 成交额 |";
        String divider = "| --- | --- | --- |";
        StringBuilder table = new StringBuilder(header).append('\n').append(divider).append('\n');
        for (int index = 0; index < 300; index++) {
            table.append("| 华东 | 门店").append(index).append(" | ").append(index).append(".5 |\n");
        }
        agent.answeringWith(table.toString());

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        List<String> texts = sender.texts();
        assertTrue(texts.size() > 1, "这张表超过单条上限，必须切开");
        for (String part : texts) {
            assertTrue(part.length() <= MessageSplitter.DEFAULT_MAX_CHARS);
            assertTrue(part.startsWith(header + "\n" + divider),
                    "这一段丢了表头，看的人会以为这是另一张表");
        }

        long rows = texts.stream()
                .flatMap(part -> part.lines())
                .filter(line -> line.startsWith("| 华东 |"))
                .count();
        assertEquals(300, rows, "切的是显示方式，不能少掉任何一行");
    }

    @Test
    @DisplayName("表头自己就超了、块里又没有数据行：切不出更小的段也要发出去，不能让整段回答变成一句“处理失败”")
    void oversizedHeaderWithoutRowsIsStillDelivered() {
        String header = "| " + "列".repeat(4100) + " |";
        String divider = "| --- |";
        agent.answeringWith(header + "\n" + divider);

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        List<String> texts = sender.texts();
        assertEquals(1, texts.size(), "切不出更小的段，就整块发一条，不该凭空多出一条或变成失败提示");
        assertTrue(texts.get(0).contains(header), "整段内容被丢了：" + texts.get(0).length());
    }

    // ---------------------------------------------------------------- 装配
    private InboundDispatcher dispatcher(Executor executor) {
        return new InboundDispatcher(agent, executor, this::lookup, sender);
    }

    private Optional<Principal> lookup(String senderId) {
        return Optional.ofNullable(directory.get(senderId));
    }

    private static FeishuInboundEvent event(String eventId, String text) {
        return event(eventId, text, SENDER);
    }

    private static FeishuInboundEvent event(String eventId, String text, String senderId) {
        return new Event(eventId, CHAT_ID, senderId, text, Instant.parse("2026-10-26T12:00:00Z"));
    }

    private record Event(String eventId, String chatId, String senderId, String text, Instant receivedAt)
            implements FeishuInboundEvent {
    }

    // ---------------------------------------------------------------- 边界外的替身

    /** 记录端口真正收到了什么，并按脚本回答。 */
    private static final class RecordingAgent implements AgentPort {

        private final List<InboundMessage> received = new CopyOnWriteArrayList<>();

        private String answer = "答复";

        private RuntimeException failure;

        RecordingAgent answeringWith(String answer) {
            this.answer = answer;
            return this;
        }

        RecordingAgent failingWith(RuntimeException failure) {
            this.failure = failure;
            return this;
        }

        @Override
        public OutboundMessage reply(InboundMessage message) {
            received.add(message);
            if (failure != null) {
                throw failure;
            }
            return OutboundMessage.of(message.chatId(), answer);
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            throw new UnsupportedOperationException("飞书走一次性回答，不走流式");
        }
    }

    /** 卡在模型调用里，由测试决定什么时候放行。 */
    private static final class BlockingAgent implements AgentPort {

        private final CountDownLatch entered;

        private final CountDownLatch release = new CountDownLatch(1);

        BlockingAgent(int expectedConcurrentCalls) {
            this.entered = new CountDownLatch(expectedConcurrentCalls);
        }

        @Override
        public OutboundMessage reply(InboundMessage message) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return OutboundMessage.of(message.chatId(), "慢回答");
        }

        @Override
        public void stream(InboundMessage message, Consumer<OutboundMessage> onChunk) {
            throw new UnsupportedOperationException("飞书走一次性回答，不走流式");
        }
    }

    /** 记下发了什么，并留一个队列让测试能"等第一条出现"而不必睡一会儿再看。 */
    private static final class RecordingSender implements FeishuReplySender {

        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        private final BlockingQueue<Sent> arriving = new LinkedBlockingQueue<>();

        @Override
        public void send(String chatId, String text) {
            Sent message = new Sent(chatId, text);
            sent.add(message);
            arriving.add(message);
        }

        List<String> texts() {
            return sent.stream().map(Sent::text).toList();
        }

        Sent next(long timeoutSeconds) throws InterruptedException {
            return arriving.poll(timeoutSeconds, SECONDS);
        }
    }

    private record Sent(String chatId, String text) {
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

    /** TTL 这种事不该靠 {@code Thread.sleep} 证明：时钟由测试推。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-26T12:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
