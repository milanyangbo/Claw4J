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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 入站编排：一条事件进来之后要发生的全部事情，且都在平台的回调线程之外。
 *
 * <p><b>它与平台无关</b>。进得来的是 {@link InboundEvent}（五个字符串加一个时间戳），
 * 出得去的是 {@link ReplySender}（发一段文本）。飞书、Telegram、Slack 在这两层上
 * 是同一个形状——它们的回调都有几秒的窗口、重推都带一个不变的幂等键、都有单条长度上限、
 * 都有一个能认人的用户标识。
 *
 * <p>存在的理由是一条硬约束：平台<b>几秒内</b>没处理完就重推，而一次 Text-to-SQL 可能十几秒。
 * 所以正确的形状不是"把 Agent 调快"，而是<b>立刻 ACK → 异步编排 → 主动回推</b>。
 *
 * <p><b>顺序固定为：落库 → 返回（ACK）→ 认领 → 执行 → 结算</b>（票 04）。四件事按这个顺序：
 *
 * <ol>
 *   <li><b>落库</b>：{@link InboundStore#enqueue}。重复投递（任何一种 {@code DUPLICATE_*}）
 *       直接返回，不执行、也不发回执。</li>
 *   <li><b>认领</b>：按 {@code (到达时间, eventId)} 取最早的一条，跳过"正在跑的车道"——
 *       同一条会话上同时只有一条在跑，这是"不越过未提交的一轮"在代码里的样子。</li>
 *   <li><b>认人</b>：{@code senderId} → {@link Principal}。认不出就回一句"没有权限"并记 WARN，
 *       然后<b>完成</b>（重试不会让这个人变成已登记）。</li>
 *   <li><b>执行与结算</b>：成功 {@code complete}；失败未到上限 {@code release}（回待处理，
 *       等下一轮扫到），到上限 {@code fail} 并回一句失败说明。</li>
 * </ol>
 *
 * <p><b>为什么不是"内存去重表 + 直接投线程池"</b>（这条是票 04 改掉的那句旧理由）：
 * 平台已经收到我们的 ACK、不会重投，而那条消息只在内存里——进程一退，用户的话就永久消失了，
 * 日志里只留下"已提交到执行线程池"。留一份去重表在内存里解决的是"多实例之间不重复"，
 * 那件事在单实例形态下（ADR-0009 的长连接不支持广播）本来就不成立；
 * **真正会丢消息的是"重启之后没人接着跑"**。所以落库、去重、状态机都在一张表上。
 *
 * <p><b>落不了库就把异常抛出去</b>：这是本类唯一允许抛的地方。与 OpenClaw 一致
 * （它的做法是关掉那条 socket："instead of continuing past an uncommitted turn"）——
 * 抛出去之后通道会让平台重推，而我们没有越过一条没落库的消息。代价写在明处：
 * 今天入站是纯内存的、库挂了照样能跑；之后<b>库挂了入站全停</b>。这是有意选择。
 *
 * <p><b>至少一次，不是恰好一次</b>：结算（{@code complete}）本身写不进去时，租约到期之后
 * 这条会被再跑一遍，于是会话里可能出现两条一样的回答。这个窗口比"消息永久丢失"小得多，
 * 也是任何"先做事再记账"的系统都有的窗口。
 *
 * <p><b>这一层只认 JDK 类型与自己那几个接口</b>，所以它能进默认构建，
 * 用假 store + 假发送 + 可控 executor 断言上面每一条，不需要任何平台的凭据。
 */
public class InboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InboundDispatcher.class);

    /**
     * 一条事件最多跑几次。到了就进死信并回一句失败说明。
     *
     * <p>**不做成配置项**：先固定一个数并写进日志，等真的有人需要调再说——
     * 多一个配置键就多一个"这个部署到底跑的是哪套"的来源。
     */
    static final int MAX_ATTEMPTS = 5;

    /**
     * 认领多久没结算算超时。
     *
     * <p>取 5 分钟，与 OpenClaw 那个 "five-minute per-chat queue limit" 同量级：
     * 比一次最慢的一轮（几分钟）长，又不至于让一条卡住的消息等到天荒地老。
     */
    static final Duration LEASE = Duration.ofMinutes(5);

    /** 多久扫一次：回收超时认领 + 重试放回去的那些。 */
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    /** 处理失败时的答复前缀。带上原因，用户才知道是"数据查不到"还是"系统出问题"。 */
    static final String FAILURE_REPLY_PREFIX = "这条消息没能处理成功：";

    /** 模型返回空内容时的占位：多数平台不接受空文本，而"什么都不出现"会被当成没收到。 */
    static final String EMPTY_ANSWER_REPLY = "（模型没有返回内容，可以换个问法再试一次）";

    /** 失败原因截断长度：异常消息可能带整段 SQL 或响应体，全贴进去没人读。 */
    private static final int MAX_REASON_CHARS = 300;

    private final AgentPort agentPort;

    private final Executor executor;

    private final Function<String, Optional<Principal>> principalResolver;

    private final ReplySender replySender;

    private final InboundStore store;

    /**
     * 队列名：一个通道一份。它就是 {@code channel}（{@code feishu} / {@code slack} …），
     * 没给通道名的调用方（老的、测试）落在 {@code default} 上。
     *
     * <p>它同时是主键的一半：同一个平台的两个 bot 会收到同一个 {@code eventId}，
     * 它们该各跑一次。
     */
    private final String queueName;

    /** 认领人标识：写进认领行，出问题时能看出"是谁占着这条"。 */
    private final String claimOwner = "dispatcher-" + UUID.randomUUID();

    /** 正在跑的车道。认领时把它们整片排除，于是同一条会话上不会并行两条。 */
    private final Map<String, ClaimedInbound> running = new ConcurrentHashMap<>();

    /** 刚被放回去的车道：这一轮不再认领，等下一轮扫到。 */
    private final Set<String> releasedLanes = ConcurrentHashMap.newKeySet();

    /** 同一时刻只有一个扫描循环在认领，否则重复认领要靠数据库兜。 */
    private final AtomicBoolean draining = new AtomicBoolean();

    /** 扫描期间又来了新事件：这一轮结束之后再开一轮。 */
    private final AtomicBoolean drainAgain = new AtomicBoolean();

    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 认不出人时的答复。说清"找谁"比说"无权访问"有用——后者会被当成系统故障。
     *
     * <p>带平台名的只有这一处：各平台的账号体系不同，说"你的 Telegram 账号"才对。
     */
    private final String noPermissionReply;

    /**
     * 本通道的标识（{@code feishu} / {@code telegram}），与
     * {@code OutboundDelivery#channel()} 同口径。
     *
     * <p>它只有一个用途：告诉 Agent "这条消息从哪个通道来"，于是这件事的结果
     * 知道以后该送回哪儿（A05）。
     */
    private final String channel;

    /**
     * 认出人之后先推的一句"收到了"。null 表示这个通道不回执（飞书就是这样）。
     */
    private final String receipt;

    /**
     * 定时扫描（回收租约 + 重试）。守护线程：关不掉的线程会让 JVM 停不下来，
     * 那比"某条消息晚几十秒"更坏。
     */
    private volatile ScheduledExecutorService sweeper;

    public InboundDispatcher(AgentPort agentPort,
                             Executor executor,
                             Function<String, Optional<Principal>> principalResolver,
                             ReplySender replySender,
                             String platformName) {
        this(agentPort, executor, principalResolver, replySender, platformName, null, null, null);
    }

    /**
     * @param channel 本通道的标识。null 表示"这条消息不登记收件地址"（A05）
     */
    public InboundDispatcher(AgentPort agentPort,
                             Executor executor,
                             Function<String, Optional<Principal>> principalResolver,
                             ReplySender replySender,
                             String platformName,
                             String channel) {
        this(agentPort, executor, principalResolver, replySender, platformName, channel, null, null);
    }

    /**
     * @param receipt 认出人之后先推的一句"收到了"（例如企业微信）。null／空白表示不回执——
     *                通道自己能被动回复时（飞书）就不需要它，多推一句只是噪音
     */
    public InboundDispatcher(AgentPort agentPort,
                             Executor executor,
                             Function<String, Optional<Principal>> principalResolver,
                             ReplySender replySender,
                             String platformName,
                             String channel,
                             String receipt) {
        this(agentPort, executor, principalResolver, replySender, platformName, channel, receipt, null);
    }

    /**
     * @param store 入站事件的落点。**null 表示不落库**：用内存实现，行为与改造之前一致
     *              （重启之后没人接着跑），并 WARN 一句。这个默认值只服务"没有 DataSource 的
     *              部署与单测"，生产的四个通道都从容器里拿真的那一份
     */
    public InboundDispatcher(AgentPort agentPort,
                             Executor executor,
                             Function<String, Optional<Principal>> principalResolver,
                             ReplySender replySender,
                             String platformName,
                             String channel,
                             String receipt,
                             InboundStore store) {
        this.agentPort = Objects.requireNonNull(agentPort, "agentPort");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.replySender = Objects.requireNonNull(replySender, "replySender");
        this.channel = channel == null || channel.isBlank() ? null : channel.strip();
        this.queueName = this.channel == null ? "default" : this.channel;
        this.receipt = receipt == null || receipt.isBlank() ? null : receipt;
        this.store = store == null ? new InMemoryInboundStore() : store;
        if (store == null) {
            log.warn("入站事件不落库（队列 {}）：重启之后没跑完的那条不会有人接着跑。"
                    + "接上 claw4j.persistence 之后这张表在 claw4j_channel.inbound_event", this.queueName);
        }
        String platform = Objects.requireNonNull(platformName, "platformName").strip();
        this.noPermissionReply = "没有权限：你的" + platform
                + "账号还没有在 Claw4J 里登记。请联系管理员开通后重新提问。";
    }

    /**
     * 启动：把重启前没结算的认领收回，然后开始定时扫描。
     *
     * <p><b>只该被调一次</b>（通道装配里调用）。用 {@link Duration#ZERO} 回收：
     * 这一刻只有一个进程，所有 {@code claimed} 都是孤儿——它们的主人不在了。
     * 不这么做的话，"重启之后那条回答自己补发"要等到第一次定时扫描，
     * 而那时的 cutoff 是"五分钟前"，一条十分钟前的孤儿要再等五分钟才轮到。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        recover(Duration.ZERO, "启动");
        submitDrain();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "claw4j-inbound-sweep-" + queueName);
            thread.setDaemon(true);
            return thread;
        });
        this.sweeper = scheduler;
        scheduler.scheduleWithFixedDelay(this::sweep,
                SWEEP_INTERVAL.toMillis(), SWEEP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 停掉定时扫描。可重复调用。 */
    public void stop() {
        ScheduledExecutorService running = this.sweeper;
        this.sweeper = null;
        if (running != null) {
            running.shutdownNow();
        }
    }

    /**
     * 接一条事件。<b>方法返回就是 ACK</b>：这里不等 Agent。
     *
     * <p><b>落不了库就抛</b>：平台会因此重推，而我们没有越过一条没落库的消息。
     * 这是本类唯一允许抛的地方——其余失败都在执行那一侧收口（回推一句说明，或放回队列重试）。
     */
    public void accept(InboundEvent event) {
        Objects.requireNonNull(event, "event");
        String eventId = eventIdOf(event);
        String conversationId = event.conversationId();
        log.info("收到事件：eventId={} conversationId={} sender={}",
                eventId, conversationId, event.senderId());

        EnqueueOutcome outcome;
        try {
            outcome = store.enqueue(new QueuedInbound(queueName, eventId, conversationId,
                    event.senderId(), event.text(), event.receivedAt()), conversationId);
        }
        catch (RuntimeException failure) {
            log.error("入站事件落不了库（{}）：这条不 ACK，交给平台重推。eventId={} conversationId={}",
                    failure.toString(), eventId, conversationId);
            throw failure;
        }
        if (outcome.duplicate()) {
            // 四种重复对调用方是四件不同的事（见 EnqueueOutcome）：
            // DUPLICATE_FAILED 尤其要看一眼——那是死信，重推也救不回来。
            log.info("同一 eventId 重复投递（{}）：已 ACK，不再执行。eventId={}", outcome, eventId);
            return;
        }
        submitDrain();
    }

    /**
     * 认领并执行，直到再没有能认领的。**包内可见是为了让用例能推一轮**：
     * 生产里它只由 {@link #accept} 与定时扫描触发。
     */
    void drain() {
        if (!draining.compareAndSet(false, true)) {
            drainAgain.set(true);
            return;
        }
        try {
            while (true) {
                Optional<ClaimedInbound> next = store.claimNext(queueName, blockedLanes(), claimOwner);
                if (next.isEmpty()) {
                    break;
                }
                if (!dispatch(next.get())) {
                    // 线程池满：这一轮到此为止。剩下的都还在库里，下一轮扫到接着跑。
                    break;
                }
            }
        }
        catch (RuntimeException failure) {
            log.warn("认领这一轮没跑完（队列 {}），下一轮再说：{}", queueName, failure.toString());
        }
        finally {
            draining.set(false);
        }
        if (drainAgain.getAndSet(false)) {
            submitDrain();
        }
    }

    /**
     * 定时扫描：把超时的认领收回，再推一轮。
     *
     * <p>放回去的那些车道也在这里解封——它们是"这一轮失败了、等下一轮再试"，
     * 而"下一轮"就是这里。**这就是重试的节奏**：30 秒一次，不是立刻重试。
     * 立刻重试的话，一个必然失败的调用会在几秒里烧掉 5 次额度，而人还在等。
     */
    void sweep() {
        releasedLanes.clear();
        recover(LEASE, "定时");
        submitDrain();
    }

    private void recover(Duration lease, String when) {
        try {
            int recovered = store.recoverStaleClaims(queueName, lease);
            if (recovered > 0) {
                log.warn("{}恢复：队列 {} 里有 {} 条认领没结算（执行者可能已经不在了），放回待处理。"
                        + "attempts 不变——丢了一个认领人不算一次投递失败", when, queueName, recovered);
            }
        }
        catch (RuntimeException failure) {
            // 库这一下不通：下一轮再说。这里不能抛，扫描线程抛一次就再也不会跑了。
            log.warn("{}恢复没跑成（队列 {}），下一轮再说：{}", when, queueName, failure.toString());
        }
    }

    /** @return false 表示线程池满了、这一轮别再认领 */
    private boolean dispatch(ClaimedInbound claimed) {
        String lane = claimed.event().laneKey();
        running.put(lane, claimed);
        try {
            executor.execute(() -> handle(claimed));
            log.info("已交给执行线程：eventId={} conversationId={}", claimed.event().eventId(), lane);
            return true;
        }
        catch (RejectedExecutionException full) {
            running.remove(lane);
            // 不结算、也不 release：release 会涨 attempts，而"线程池满"不是这条消息的错。
            // 它会一直是 claimed，由租约回收（那时 attempts 也不涨）。
            log.warn("执行线程池满了：eventId={} 留在待认领状态，租约到期后会被收回重跑",
                    claimed.event().eventId());
            return false;
        }
    }

    /**
     * 跑一条：认人、回执、Agent、回推，最后结算。异常都在这里收口——
     * 这条任务在线程池里，抛出去只会变成一行没人看的堆栈，而会话里则是永远的沉默。
     */
    private void handle(ClaimedInbound claimed) {
        boolean settled;
        try {
            settled = runTurn(claimed);
        }
        catch (RuntimeException | Error error) {
            settled = failed(claimed, error);
        }
        finally {
            running.remove(claimed.event().laneKey());
        }
        if (settled) {
            // 车道空出来了：可能还有同一会话排着的一条
            submitDrain();
        }
    }

    private boolean runTurn(ClaimedInbound claimed) {
        QueuedInbound queued = claimed.event();
        String conversationId = queued.conversationId();
        long startedAt = System.nanoTime();

        Optional<Principal> principal;
        try {
            principal = principalResolver.apply(queued.senderId());
        }
        catch (RuntimeException error) {
            // 角色表本身坏了（配置写错）和"这个人没登记"是两件事，但都不能把用户晾着。
            // 都不重试：换个时间点问同一个人，答案一样。
            log.error("认人失败：sender={} conversationId={}", queued.senderId(), conversationId, error);
            sendWithoutBlocking(conversationId, noPermissionReply);
            return completeQuietly(claimed, "rejected:resolver");
        }
        if (principal == null || principal.isEmpty()) {
            log.warn("用户未登记，已拒绝：sender={} conversationId={}", queued.senderId(), conversationId);
            sendWithoutBlocking(conversationId, noPermissionReply);
            return completeQuietly(claimed, "rejected:unknown-sender");
        }

        InboundMessage message = new InboundMessage(
                principal.get(), conversationId, queued.text(), queued.receivedAt(),
                queued.eventId(), channel);
        log.info("开始处理：eventId={} user={} conversationId={} 第 {} 次",
                queued.eventId(), principal.get().userId(), conversationId, claimed.attempts() + 1);

        // 回执在这里，不在 accept 里：走到这一步说明已经认领、也认出了人。
        // 发失败只记日志——回执是"别让人以为没收到"，不该反过来把回答也弄丢。
        if (receipt != null) {
            sendWithoutBlocking(conversationId, receipt);
        }

        OutboundMessage answer = agentPort.reply(message);
        log.info("Agent 算完，用时 {} ms，准备回推：eventId={}",
                elapsedMillis(startedAt), queued.eventId());
        sendAnswer(conversationId, answer == null ? "" : answer.text());
        log.info("已回推：eventId={} conversationId={}", queued.eventId(), conversationId);
        return completeQuietly(claimed, null);
    }

    /**
     * 一条没跑完：未到上限放回队列，到上限进死信并回一句失败说明。
     *
     * <p>**中间几次失败不说话**：每一次失败都回一句"没成功，稍后再试"的话，
     * 一次库抖动会让群里刷出五条同样的道歉。到上限才说，那时它真的不会再试了。
     */
    private boolean failed(ClaimedInbound claimed, Throwable error) {
        QueuedInbound queued = claimed.event();
        String reason = reasonOf(error);
        int attempts = claimed.attempts() + 1;
        log.error("处理失败（第 {} / {} 次）：eventId={} conversationId={}",
                attempts, MAX_ATTEMPTS, queued.eventId(), queued.conversationId(), error);
        if (attempts >= MAX_ATTEMPTS) {
            settleQuietly(() -> store.fail(claimed.claim(), "max_attempts", reason), "进死信", queued);
            sendAnswer(queued.conversationId(), FAILURE_REPLY_PREFIX + reason + "。可以稍后重试。");
            return true;
        }
        settleQuietly(() -> store.release(claimed.claim(), reason), "放回待处理", queued);
        // 这条车道这一轮不再认领：重试的节奏由定时扫描给，不是立刻重试
        releasedLanes.add(queued.laneKey());
        return false;
    }

    private boolean completeQuietly(ClaimedInbound claimed, String metadataJson) {
        return settleQuietly(() -> store.complete(claimed.claim(), metadataJson), "标记完成",
                claimed.event());
    }

    /**
     * 结算写不进去只记日志，**不把这一轮翻成失败**：回答已经发出去了，
     * 把它再跑一遍只会在会话里多一条一样的回答。
     */
    private boolean settleQuietly(BooleanSupplier action, String what,
                                  QueuedInbound queued) {
        try {
            boolean done = action.getAsBoolean();
            if (!done) {
                log.warn("{}没生效（令牌已经不是我们的了）：eventId={}——另一个执行者接管了这条",
                        what, queued.eventId());
            }
            return done;
        }
        catch (RuntimeException failure) {
            log.error("{}写不进去：eventId={}。这一条会在租约到期后再跑一遍（至少一次，不是恰好一次）",
                    what, queued.eventId(), failure);
            return false;
        }
    }

    /** 正在跑的车道 ∪ 刚放回去的车道。前者是串行，后者是"等下一轮"。 */
    private Set<String> blockedLanes() {
        Set<String> blocked = new HashSet<>(running.keySet());
        blocked.addAll(releasedLanes);
        return blocked;
    }

    private void submitDrain() {
        try {
            executor.execute(this::drain);
        }
        catch (RejectedExecutionException full) {
            // 事件已经在库里了：不 ACK 也不丢，等下一轮扫到
            log.warn("执行线程池满了：事件仍在待处理（队列 {}），下一轮扫到接着跑", queueName);
        }
    }

    /**
     * 平台没给 eventId 时生成一个。
     *
     * <p>生成的代价要说清：**这条的去重失效**（重推会拿到一个新 id，于是跑第二遍），
     * 与改造之前"没有 eventId 就照常处理"是同一个行为。宁可重复回答一次，
     * 也不能因为少一个字段把用户的话丢掉。
     */
    private static String eventIdOf(InboundEvent event) {
        String eventId = event.eventId();
        if (eventId != null && !eventId.isBlank()) {
            return eventId.strip();
        }
        String generated = "generated-" + UUID.randomUUID();
        log.warn("事件没有 eventId：用生成的 {} 入队（去重对这条失效，重推会再跑一遍）", generated);
        return generated;
    }

    /**
     * 按段发送。一段发不出去就不再往下发：后续段落单独看是"后半截"，发出去只会更乱，
     * 所以停在这里并记 ERROR——会话里会缺内容，但日志里能查到缺在哪一条。
     */
    private void sendAnswer(String conversationId, String text) {
        List<String> parts = MessageSplitter.split(text, MessageSplitter.DEFAULT_MAX_CHARS);
        if (parts.isEmpty()) {
            sendWithoutBlocking(conversationId, EMPTY_ANSWER_REPLY);
            return;
        }
        for (int index = 0; index < parts.size(); index++) {
            try {
                replySender.send(conversationId, parts.get(index));
            }
            catch (RuntimeException error) {
                log.error("回推失败，后续段落不再发送：conversationId={} 第 {} / {} 段",
                        conversationId, index + 1, parts.size(), error);
                return;
            }
        }
    }

    /** 认人失败这类"答复很短但要立刻发"的场景：发送本身失败只记日志，不能反过来打断流程。 */
    private void sendWithoutBlocking(String conversationId, String text) {
        try {
            replySender.send(conversationId, text);
        }
        catch (RuntimeException error) {
            log.error("回推失败：conversationId={}", conversationId, error);
        }
    }

    private static String reasonOf(Throwable error) {
        String message = error.getMessage();
        String reason = (message == null || message.isBlank())
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + "：" + message.strip();
        return reason.length() <= MAX_REASON_CHARS ? reason : reason.substring(0, MAX_REASON_CHARS) + "…";
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
