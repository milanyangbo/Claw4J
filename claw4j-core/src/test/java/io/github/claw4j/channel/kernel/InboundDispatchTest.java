package io.github.claw4j.channel.kernel;

import io.github.claw4j.domain.inbound.ClaimRef;
import io.github.claw4j.domain.inbound.ClaimedInbound;
import io.github.claw4j.domain.inbound.EnqueueOutcome;
import io.github.claw4j.domain.inbound.InboundStore;
import io.github.claw4j.domain.inbound.QueuedInbound;
import io.github.claw4j.domain.inbound.Retention;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.port.AgentPort;
import io.github.claw4j.domain.port.InboundMessage;
import io.github.claw4j.domain.port.OutboundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入站编排这一个功能：一条事件进来之后，会话里出现几条消息、Agent 被调了几次、什么时候返回、
 * 进程被杀之后还剩下什么。
 *
 * <p>入口是 {@link InboundDispatcher#accept}——真正的系统边界（长连接 / webhook）需要真凭据，
 * 而它要做的事只是"把平台事件压成 {@link InboundEvent}"，翻译错了在假事件上没有意义。
 * 所以这里从责任开始的那一层进，断言的是外部可观察的结果：发送了什么、调了几次、ACK 何时返回、
 * 库里那一条最后是什么状态。
 *
 * <p>本用例一个字都不提飞书。它用的 {@link Event} 是一个只有五个字段的 record，语义上与飞书、
 * Telegram、Slack 无关。这就是"这套编排不属于某一个通道"的可执行版本：如果哪天有人往
 * {@code InboundDispatcher} 里加了一个飞书概念，这里会先编译不过。
 *
 * <p>两条轴分开验：编排本身（回推、失败、回执、分段）与队列的语义（去重、恢复、串行、令牌）。
 * 后者大多直接对着 {@link InboundStore} 断言——那几条是"库里那一行长什么样"，走一遍 Agent 只会
 * 把要验的东西埋在一堆无关日志里。
 *
 * <p>这些错了都不会报错：事件照样被"确认"，只是会话里多一条重复回答、少一条失败说明、或者进程
 * 重启之后那条消息永远没人接着跑。平台侧看不出区别，只有重推次数在涨。
 */
class InboundDispatchTest {

    /** 平台名只出现在一句拒绝文案里，所以用一个能一眼认出来的假值。 */
    private static final String PLATFORM = "测试平台";

    private static final String CONVERSATION = "conv-demo";

    private static final String SENDER = "user-zhang";

    private static final String EVENT_ID = "evt-0001";

    /** 队列名的口径与生产一致：它就是通道名（{@code feishu} / {@code slack} …）。 */
    private static final String QUEUE = "feishu";

    private final RecordingAgent agent = new RecordingAgent();

    private final RecordingSender sender = new RecordingSender();

    private final Map<String, Principal> directory = new ConcurrentHashMap<>();

    {
        directory.put(SENDER, Principal.readOnly("u-1"));
    }

    // ---------------------------------------------------------------- ACK：回调线程不等 Agent

    @Test
    @DisplayName("ACK 不等 Agent：accept 返回时任务还排在队列里，Agent 一次都没被调")
    void ackReturnsBeforeTheAgentRuns() {
        QueuedExecutor executor = new QueuedExecutor();
        InboundDispatcher dispatcher = dispatcher(executor);

        dispatcher.accept(event(EVENT_ID, "上周华东区成交额"));

        assertTrue(agent.received.isEmpty(), "accept 里就跑了 Agent，等于把几秒的 ACK 预算赌在模型上");
        assertTrue(sender.texts().isEmpty());

        executor.runAll();

        assertEquals(1, agent.received.size());
        InboundMessage delivered = agent.received.get(0);
        assertEquals("上周华东区成交额", delivered.text());
        assertEquals("u-1", delivered.principal().userId());
        assertEquals(CONVERSATION, delivered.chatId());
        assertEquals(EVENT_ID, delivered.eventId());
        assertEquals(List.of("答复"), sender.texts());
        assertEquals(CONVERSATION, sender.sent.get(0).conversationId());
    }

    @Test
    @Timeout(20)
    @DisplayName("Agent 卡住不返回时 ACK 仍然立刻返回：否则平台判定超时并无限重推")
    void ackStillReturnsWhileTheAgentIsStuck() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            BlockingAgent stuck = new BlockingAgent(1);
            InboundDispatcher dispatcher =
                    new InboundDispatcher(stuck, pool, this::lookup, sender, PLATFORM);

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
    @DisplayName("两条不同会话互不排队：串行只发生在同一条会话上")
    void twoConversationsRunSideBySide() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            BlockingAgent stuck = new BlockingAgent(2);
            InboundDispatcher dispatcher =
                    new InboundDispatcher(stuck, pool, this::lookup, sender, PLATFORM);

            dispatcher.accept(event("evt-1", "conv-a", "问题一"));
            dispatcher.accept(event("evt-2", "conv-b", "问题二"));

            // 两条都进到 Agent 里且谁也没放行：说明第二条没有被第一条挡住。
            // 串行是按会话的（见串行那条用例），不是全局的——全局串行等于把并发改回 1。
            assertTrue(stuck.entered.await(5, SECONDS), "不同会话的事件被串行化了");

            stuck.release.countDown();

            assertNotNull(sender.next(5));
            assertNotNull(sender.next(5));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 去重与重启

    @Test
    @DisplayName("同一个 eventId 再来一次：仍然 ACK，但 Agent 只跑一次、会话里只有一条回答")
    void duplicateDeliveryRunsOnceButStillAcks() {
        InboundDispatcher dispatcher = dispatcher(Runnable::run);
        InboundEvent event = event(EVENT_ID, "上周成交额");

        dispatcher.accept(event);
        dispatcher.accept(event);
        dispatcher.accept(event(EVENT_ID, "上周成交额"));

        assertEquals(1, agent.received.size(), "重推被当成新消息，模型与库都白跑一遍");
        assertEquals(1, sender.texts().size(), "会话里会刷出两条一样的回答");
    }

    @Test
    @DisplayName("事件没有 eventId：照常处理并记 WARN，不能因为少一个字段把用户的话丢掉")
    void missingEventIdStillProcesses() {
        dispatcher(Runnable::run).accept(event(null, "问题"));

        assertEquals(1, agent.received.size());
        assertEquals(List.of("答复"), sender.texts());
    }

    @Test
    @DisplayName("重启不丢：上一个进程只入队没跑完的那条，新进程起来之后自己跑完")
    void aRestartFinishesWhatThePreviousProcessQueued() {
        InMemoryInboundStore store = new InMemoryInboundStore();

        // 第一个进程：入队成功、任务攒着还没跑就被 kill -9（QueuedExecutor 只收不跑）
        InboundDispatcher killed = new InboundDispatcher(agent, new QueuedExecutor(), this::lookup, sender,
                PLATFORM, QUEUE, null, store);
        killed.accept(event(EVENT_ID, "上周华东区成交额"));
        assertTrue(agent.received.isEmpty(), "前提：这条确实还没跑");

        // 第二个进程：同一个 store（库里那一行还在），start() 把它捞起来跑完
        InboundDispatcher restarted = new InboundDispatcher(agent, Runnable::run, this::lookup, sender,
                PLATFORM, QUEUE, null, store);
        try {
            restarted.start();

            assertEquals(1, agent.received.size(), "没人接着跑：这条消息就永久丢了，而平台以为我们收下了");
            assertEquals(List.of("答复"), sender.texts(), "补发的回答要回到原来那段对话里，且只发一次");
        } finally {
            restarted.stop();
        }
    }

    @Test
    @DisplayName("认领之后进程崩了：孤儿被收回、跑完，attempts 不涨（否则重启三次就把额度耗光）")
    void aClaimedOrphanIsRecoveredWithoutBurningAnAttempt() {
        InMemoryInboundStore store = new InMemoryInboundStore();
        store.enqueue(queued(EVENT_ID, CONVERSATION, "上周华东区成交额"), CONVERSATION);

        // 上个进程认领了它，然后被杀——认领时间就是"刚刚"，租约还没到期
        assertTrue(store.claimNext(QUEUE, Set.of(), "上个进程").isPresent());

        // 回收之后再看一眼：attempts 还是 0。这是"重启三次把正常消息的额度耗光"那条的落点。
        assertEquals(1, store.recoverStaleClaims(QUEUE, Duration.ZERO));
        assertEquals(0, store.claimNext(QUEUE, Set.of(), "探针").orElseThrow().attempts(),
                "丢了一个认领人不算一次投递失败");

        InboundDispatcher restarted = new InboundDispatcher(agent, Runnable::run, this::lookup, sender,
                PLATFORM, QUEUE, null, store);
        try {
            // 探针又把它认领了一次（同样是孤儿），start() 用 cutoff = now 全部收回再跑
            restarted.start();

            assertEquals(1, agent.received.size());
            assertEquals(List.of("答复"), sender.texts());
        } finally {
            restarted.stop();
        }
    }

    // ---------------------------------------------------------------- 串行与令牌（库那一侧）

    @Test
    @DisplayName("车道与令牌：同车道不重复认领，被回收并重新认领之后旧令牌写不进任何结论")
    void theLaneAndTheClaimTokenBothGuardTheRow() {
        InMemoryInboundStore store = new InMemoryInboundStore();
        store.enqueue(queued("evt-a", CONVERSATION, "问题一"), CONVERSATION);
        store.enqueue(queued("evt-b", CONVERSATION, "问题二"), CONVERSATION);
        store.enqueue(queued("evt-c", "conv-other", "别处的"), "conv-other");

        ClaimedInbound first = store.claimNext(QUEUE, Set.of(), "A").orElseThrow();
        assertEquals("evt-a", first.event().eventId(), "最早的那条先来");
        assertEquals("evt-c", store.claimNext(QUEUE, Set.of(CONVERSATION), "A").orElseThrow()
                .event().eventId(), "同车道那条必须被跳过，只能拿别的车道");
        assertTrue(store.claimNext(QUEUE, Set.of(CONVERSATION, "conv-other"), "A").isEmpty(),
                "两条车道都占着时拿不到任何东西——这就是\"不越过未提交的一轮\"");

        // 令牌：evt-a 被回收、再由 B 认领之后，A 手里那个旧令牌写不进任何结论
        store.recoverStaleClaims(QUEUE, Duration.ZERO);
        ClaimedInbound fresh = store.claimNext(QUEUE, Set.of(), "B").orElseThrow();
        assertFalse(store.complete(first.claim(), null),
                "A 晚回来了：它的令牌已经不属于它，写进去就等于盖掉 B 的结论");
        assertTrue(store.complete(fresh.claim(), null));
        assertFalse(store.release(first.claim(), "A 也说自己失败了"), "同理，旧令牌连失败都写不进");
    }

    // ---------------------------------------------------------------- 失败：不能变成沉默

    @Test
    @DisplayName("Agent 一直失败：前几次安静重试，到上限才回一句说清失败的消息")
    void agentFailureIsRetriedThenReportedOnceAtTheLimit() {
        agent.failingWith(new IllegalStateException("模型连不上"));
        InboundDispatcher dispatcher = dispatcher(Runnable::run);

        dispatcher.accept(event(EVENT_ID, "问题"));
        assertEquals(0, sender.texts().size(), "第一次失败就道歉的话，一次库抖动会在群里刷五条");

        // 每一轮扫描 = 一次重试。放回去的那些要等到扫描才再试，不是立刻重试。
        for (int round = 1; round < InboundDispatcher.MAX_ATTEMPTS; round++) {
            dispatcher.sweep();
        }

        List<String> texts = sender.texts();
        assertEquals(1, texts.size(), "到上限才说：那时它真的不会再试了");
        assertTrue(texts.get(0).startsWith(InboundDispatcher.FAILURE_REPLY_PREFIX));
        assertTrue(texts.get(0).contains("模型连不上"), "没带上原因，用户无从判断");
        assertEquals(InboundDispatcher.MAX_ATTEMPTS, agent.received.size(), "上限就是上限");
        assertEquals(CONVERSATION, sender.sent.get(0).conversationId());

        dispatcher.sweep();
        assertEquals(InboundDispatcher.MAX_ATTEMPTS, agent.received.size(),
                "进了死信就不该再被认领：再试也是同一个失败，而每试一次都是一次模型调用");
    }

    @Test
    @DisplayName("认人失败的两条路（没登记 / 解析抛异常）都当场回一句没有权限，绝不调 Agent")
    void aFailedPrincipalLookupAlwaysAnswersTheUser() {
        dispatcher(Runnable::run).accept(event(EVENT_ID, CONVERSATION, "问题", "user-stranger"));

        assertTrue(agent.received.isEmpty());
        List<String> texts = sender.texts();
        assertEquals(1, texts.size());
        assertTrue(texts.get(0).contains("没有权限"), "用户要知道这是权限问题，不是系统坏了");
        assertTrue(texts.get(0).contains(PLATFORM),
                "各平台账号体系不同，说错平台名会让人去找错地方：" + texts.get(0));

        sender.sent.clear();
        new InboundDispatcher(agent, Runnable::run, senderId -> {
            throw new IllegalStateException("角色表配错了");
        }, sender, PLATFORM).accept(event(EVENT_ID + "-2", "问题"));

        assertTrue(agent.received.isEmpty());
        assertEquals(1, sender.texts().size(), "角色表坏了也是当场回一句，不能把用户晾着");
        assertTrue(sender.texts().get(0).contains("没有权限"));
    }

    @Test
    @DisplayName("落库失败要抛：这条不 ACK，让平台重推，而不是越过一条没落库的消息")
    void aPersistFailureIsThrownSoThePlatformRetries() {
        InboundDispatcher dispatcher = new InboundDispatcher(agent, Runnable::run, this::lookup, sender,
                PLATFORM, QUEUE, null, new BrokenStore());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> dispatcher.accept(event(EVENT_ID, "问题")));

        assertTrue(thrown.getMessage().contains("库"), "报错要说清是库的问题：" + thrown.getMessage());
        assertTrue(agent.received.isEmpty(), "没落库就不该跑：跑了才丢，因为平台那边以为我们收下了");
        assertTrue(sender.texts().isEmpty());
    }

    @Test
    @DisplayName("模型返回空内容：也要发一句占位，不能什么都不出现")
    void blankAnswerIsNotSilentlyDropped() {
        agent.answeringWith("   ");

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        assertEquals(List.of(InboundDispatcher.EMPTY_ANSWER_REPLY), sender.texts());
    }

    @Test
    @DisplayName("回推失败：后续段落不再发（宁可缺也不乱），但已经发出去的那些不受影响")
    void sendFailureStopsFurtherParts() {
        // 三段、远超单条上限，于是"切出来的段数 ≥ 3"是可预期的：第 1 段成功，第 2 段失败
        String first = "第一段：" + "甲".repeat(3000);
        String second = "第二段：" + "乙".repeat(3000);
        String third = "第三段：" + "丙".repeat(3000);
        agent.answeringWith(String.join("\n\n", first, second, third));
        sender.failingAfter(1);

        dispatcher(Runnable::run).accept(event(EVENT_ID, "问题"));

        assertEquals(1, sender.sent.size(), "第二段发不出去时，不该再往下发第三段");
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

    // ---------------------------------------------------------------- 落点：内存兜底与通道名

    @Test
    @DisplayName("没有库时退回内存实现：能跑，但重启之后那条没人接着跑（启动时会 WARN 说这件事）")
    void withoutAStoreNothingSurvivesARestart() {
        // 第一个进程：没有 store（= 没有 DataSource 的部署），入队进内存、任务还没跑就被杀
        InboundDispatcher killed = new InboundDispatcher(agent, new QueuedExecutor(), this::lookup, sender,
                PLATFORM, QUEUE, null, null);
        killed.accept(event(EVENT_ID, "上周成交额"));
        assertTrue(agent.received.isEmpty());

        // 第二个进程：内存表随进程没了，start() 捞不到任何东西
        InboundDispatcher restarted = new InboundDispatcher(agent, Runnable::run, this::lookup, sender,
                PLATFORM, QUEUE, null, null);
        try {
            restarted.start();
            assertEquals(0, agent.received.size(),
                    "内存兜底就是这个代价：行为与改造之前一致，也就是重启之后的补发不存在。"
                            + "它是能选的降级，所以构造时会 WARN 一句\"入站事件不落库\"");
        } finally {
            restarted.stop();
        }
    }

    @Test
    @DisplayName("通道名传到 Agent：不传是 null（老调用方照旧），传了才知道结果送回哪儿")
    void channelNameReachesTheAgent() {
        new InboundDispatcher(agent, Runnable::run, this::lookup, sender, PLATFORM, "feishu")
                .accept(event(EVENT_ID, "问题"));

        assertEquals("feishu", agent.received.getFirst().channel(),
                "没有通道名，这件事做完之后没人能把它送回原处");

        dispatcher(Runnable::run).accept(event(EVENT_ID + "-2", "另一件"));

        assertNull(agent.received.getLast().channel(),
                "不点名的通道不该凭空有一个名字：那会让结果被送到一个猜出来的地址");
    }

    // ---------------------------------------------------------------- 回执（可选）

    @Test
    @DisplayName("回执：先出门、失败不吞答案、没传就不发——三件事同一个开关")
    void theReceiptGoesOutFirstAndNeverSwallowsTheAnswer() {
        new InboundDispatcher(agent, Runnable::run, this::lookup, sender, PLATFORM, null, "收到，正在查")
                .accept(event(EVENT_ID, "问题"));
        assertEquals(List.of("收到，正在查", "答复"), sender.texts(),
                "没有被动回复的平台，用户在那十几秒里至少知道有人接了");

        List<String> sent = new CopyOnWriteArrayList<>();
        ReplySender onlyAnswersAreSent = (conversationId, text) -> {
            if (text.startsWith("收到")) {
                throw new IllegalStateException("回执这一条没发出去");
            }
            sent.add(text);
        };
        new InboundDispatcher(agent, Runnable::run, this::lookup, onlyAnswersAreSent, PLATFORM, null, "收到，正在查")
                .accept(event(EVENT_ID + "-2", "问题"));
        assertEquals(List.of("答复"), sent, "回执失败只该记日志，答案必须照发——否则用户什么都收不到");

        sender.sent.clear();
        dispatcher(Runnable::run).accept(event(EVENT_ID + "-3", "问题"));
        assertEquals(List.of("答复"), sender.texts(),
                "不传回执就一句都不多：飞书自己能被动回复，多推一句只是噪音");
    }

    // ---------------------------------------------------------------- 装配
    /** 不传 store：走内存兜底（单测与"没有库的部署"用的就是这条）。 */
    private InboundDispatcher dispatcher(Executor executor) {
        return new InboundDispatcher(agent, executor, this::lookup, sender, PLATFORM);
    }

    /** 直接落一条到队列里：验队列语义时不必经过 accept（那会把 Agent 也拉进来）。 */
    private static QueuedInbound queued(String eventId, String conversationId, String text) {
        return new QueuedInbound(QUEUE, eventId, conversationId, SENDER, text,
                Instant.parse("2026-10-26T12:00:00Z"));
    }

    private Optional<Principal> lookup(String senderId) {
        return Optional.ofNullable(directory.get(senderId));
    }

    private static InboundEvent event(String eventId, String text) {
        return event(eventId, CONVERSATION, text, SENDER);
    }

    private static InboundEvent event(String eventId, String conversationId, String text) {
        return event(eventId, conversationId, text, SENDER);
    }

    private static InboundEvent event(String eventId, String conversationId, String text, String senderId) {
        return new Event(eventId, conversationId, senderId, text, Instant.parse("2026-10-26T12:00:00Z"));
    }

    /**
     * 一个"第三方通道"的事件：五个字段，与任何平台无关。
     *
     * <p>它刻意不叫 {@code FeishuInboundEvent}——那份是飞书侧的适配，属于 {@code claw4j-channel}。
     * 这里能用一个 record 顶替它，说明编排层要的确实只有这五列。
     */
    private record Event(String eventId, String conversationId, String senderId, String text,
                         Instant receivedAt) implements InboundEvent {
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
            throw new UnsupportedOperationException("这个通道走一次性回答，不走流式");
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
            throw new UnsupportedOperationException("这个通道走一次性回答，不走流式");
        }
    }

    /** 记下发了什么，并留一个队列让测试能"等第一条出现"而不必睡一会儿再看。 */
    private static final class RecordingSender implements ReplySender {

        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        private final BlockingQueue<Sent> arriving = new LinkedBlockingQueue<>();

        private int failAfter = -1;

        /** 发出去 N 条之后开始失败，用来验证"后续段落不再发"。 */
        void failingAfter(int successfulSends) {
            this.failAfter = successfulSends;
        }

        @Override
        public void send(String conversationId, String text) {
            if (failAfter >= 0 && sent.size() >= failAfter) {
                throw new IllegalStateException("平台拒收");
            }
            Sent message = new Sent(conversationId, text);
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

    private record Sent(String conversationId, String text) {
    }

    /** 把提交的任务攒着，由测试决定什么时候在哪个线程上跑。模拟"任务排着但进程被杀了"。 */
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

    /**
     * 落不了库的 store：只有 {@code enqueue} 会炸，其余没有意义（调用方在入队那一步就该抛出去）。
     *
     * <p>它模拟的是"库挂了"这一件事，而这条路径的判据只有一个：异常要冒到通道那一层。
     */
    private static final class BrokenStore implements InboundStore {

        @Override
        public EnqueueOutcome enqueue(QueuedInbound event, String laneKey) {
            throw new IllegalStateException("库连不上");
        }

        @Override
        public Optional<ClaimedInbound> claimNext(String queueName, java.util.Set<String> blockedLanes,
                                                  String claimOwner) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public boolean complete(ClaimRef claim, String metadataJson) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public boolean release(ClaimRef claim, String lastError) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public boolean fail(ClaimRef claim, String reason, String message) {
            throw new UnsupportedOperationException("入队就失败了，走不到这里");
        }

        @Override
        public int recoverStaleClaims(String queueName, Duration lease) {
            return 0;
        }

        @Override
        public int prune(Retention retention) {
            return 0;
        }
    }
}
