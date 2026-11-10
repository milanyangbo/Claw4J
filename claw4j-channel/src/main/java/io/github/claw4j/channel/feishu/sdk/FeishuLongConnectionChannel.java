package io.github.claw4j.channel.feishu.sdk;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.github.claw4j.channel.feishu.FeishuApprovalCard;
import io.github.claw4j.channel.feishu.FeishuInboundEvent;
import io.github.claw4j.channel.feishu.FeishuReplySender;
import io.github.claw4j.channel.kernel.InboundDispatcher;
import io.github.claw4j.channel.spi.ChatChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 飞书长连接通道：SDK 主动外连（不需要公网 IP、域名、内网穿透），把收到的事件交给入站调度。
 *
 * <p>本类只做三件事：建连、把回调转给 {@link InboundDispatcher}、关连接。什么时候 ACK、
 * 失败了说什么、长回答怎么切，全在调度层——那是唯一能被默认构建测到的部分，逻辑不往这里搬。
 *
 * <p>三个取舍：
 * <ul>
 *   <li>连不上的两类分开处理（票 02，分法见 {@code docs/channels/slack/transports.md:71}）。
 *       凭据错、应用未开通长连接这类换个时间点还是错的，启动失败；网络、握手超时、对端不可用
 *       这类等一会儿会好的不抛，状态置 {@link FeishuConnectionState#DISCONNECTED}，
 *       后台按 30 秒起、每次翻倍、封顶 10 分钟退避重连。一次对端抖动不该让整个应用起不来，
 *       那在 K8s 上是 CrashLoopBackOff，连 HTTP 通道也一起不可用。</li>
 *   <li>重连是关掉旧的、开一条新的，不让 SDK 救活失败的那条。SDK 的 {@code autoReconnect}
 *       只在已经连上之后断开时有用，退避与状态也不归调用方管。</li>
 *   <li>回调里默认不抛。事件线程抛异常会触发平台重推，而重推的往往是同一条处理不了的消息，
 *       失败、重推、再失败能跑好几轮——翻译不了的那一类就吞掉。落库失败是唯一的例外：
 *       这一条还没被收下，重推才有机会补上，所以照旧往上抛，同时关掉这条连接强制重建。</li>
 * </ul>
 */
public class FeishuLongConnectionChannel implements ChatChannel {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionChannel.class);

    /**
     * 第一次重连等 30 秒，之后每次翻倍，封顶 10 分钟。
     *
     * <p>数字与 OpenClaw 的 MCP 客户端同一组（{@code docs/tools/mcp.md:61}：30 seconds,
     * doubling up to 10 minutes），便于两边日志对着看。翻倍而不是固定：抖一下三十秒内就回来，
     * 对端真挂了也不必每分钟去撞一次。
     */
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(30);

    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    /**
     * 翻不出来的消息回什么。
     *
     * <p>刻意不点具体原因：翻不出来有三种情形（图片或富文本、只有 @ 没有正文、事件里缺发送者），
     * 而这一层只知道翻不出来。写死一个原因会在另外两种情形上说错话，
     * 用户会照着错的原因去改自己的操作，比只说处理不了更糟。
     */
    static final String CANNOT_HANDLE_REPLY =
            "这条消息我没法处理：把问题直接打成文字发给我就行（例如「上周华东区成交额多少」）。";

    private final FeishuChannelProperties properties;

    private final InboundDispatcher dispatcher;

    private final FeishuMessageTranslator translator;

    private final FeishuReplySender replySender;

    /**
     * 卡片按钮的处理（P03）。可以为 null：没有装配审批的部署里没人会发卡片，此时不注册卡片回调。
     * 留一个「注册了但没东西可判」的回调，表现是每次点击都回一句没装审批，比没有按钮更让人困惑。
     */
    private final FeishuApprovalActions approvals;

    private final FeishuConnectionFactory connectionFactory;

    /** 重连的定时器。守护线程：关不掉的线程会让 JVM 停不下来，那比"通道没连上"更坏。 */
    private final ScheduledExecutorService scheduler;

    private final AtomicReference<FeishuConnectionState> state =
            new AtomicReference<>(FeishuConnectionState.CONNECTING);

    /** 当前那条连接；{@code stop()} 之后清空，所以它是可重复调用的。 */
    private volatile FeishuConnection connection;

    /** 建连时用的回调注册表。落库失败要主动重建连接，那时得拿它再建一条。 */
    private volatile EventDispatcher events;

    public FeishuLongConnectionChannel(FeishuChannelProperties properties,
                                       InboundDispatcher dispatcher,
                                       FeishuMessageTranslator translator,
                                       FeishuReplySender replySender,
                                       FeishuApprovalActions approvals,
                                       FeishuConnectionFactory connectionFactory) {
        this(properties, dispatcher, translator, replySender, approvals, connectionFactory, newScheduler());
    }

    /** 测试用：把定时器换成能记录、能同步驱动的替身，退避序列才验得出来。 */
    FeishuLongConnectionChannel(FeishuChannelProperties properties,
                                InboundDispatcher dispatcher,
                                FeishuMessageTranslator translator,
                                FeishuReplySender replySender,
                                FeishuApprovalActions approvals,
                                FeishuConnectionFactory connectionFactory,
                                ScheduledExecutorService scheduler) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.translator = translator;
        this.replySender = replySender;
        this.approvals = approvals;
        this.connectionFactory = connectionFactory;
        this.scheduler = scheduler;
    }

    @Override
    public String id() {
        return "feishu";
    }

    /** 这条通道现在能不能收到消息。目前没有健康端点，状态的第一落点就是它和日志。 */
    public FeishuConnectionState state() {
        return state.get();
    }

    @Override
    public synchronized void start() {
        // 只在"还没试过"时动手：重复调用（比如测试里手动 start 一次）不该再开第二条连接。
        if (state.get() != FeishuConnectionState.CONNECTING) {
            return;
        }
        EventDispatcher events = eventDispatcher();
        this.events = events;
        try {
            connect(events);
            state.set(FeishuConnectionState.CONNECTED);
            log.info("飞书通道就绪：长连接已建立（appId={}），入站事件会走 去重 → ACK → 异步执行 → 回推",
                    mask(properties.getAppId()));
        }
        catch (Exception failure) {
            FeishuConnectionFailures.Verdict verdict = FeishuConnectionFailures.classify(failure);
            if (verdict.isPermanent()) {
                state.set(FeishuConnectionState.STOPPED);
                throw new IllegalStateException(
                        "飞书长连接没建起来（" + verdict.reason() + "，重启不会好；appId="
                                + mask(properties.getAppId()) + "）：" + failure.getMessage()
                                + "。检查 claw4j.channel.feishu 的 app-id / app-secret，"
                                + "以及飞书后台里这个应用是否已发布、是否开通了长连接。",
                        failure);
            }
            state.set(FeishuConnectionState.DISCONNECTED);
            log.warn("飞书长连接没建起来（{}），{} 秒后重试第 1 次（appId={}）：{}",
                    verdict.reason(), INITIAL_BACKOFF.toSeconds(), mask(properties.getAppId()),
                    failure.toString());
            scheduleReconnect(events, 1, INITIAL_BACKOFF);
        }
    }

    @Override
    public synchronized void stop() {
        // 顺序不能反：先停定时器、再关连接。反过来的话，关连接那一秒里排着的任务会重新建一条
        // 没人管的连接。shutdownNow 可重复调用，所以 stop() 也就可以重复调用。
        state.set(FeishuConnectionState.STOPPED);
        scheduler.shutdownNow();
        this.events = null;
        FeishuConnection closing = this.connection;
        this.connection = null;
        if (closing != null) {
            closeQuietly(closing);
            log.info("飞书通道关闭");
        }
    }

    /**
     * 建一条连接，成功才替换掉当前那条。
     *
     * <p>失败时必须关掉这一次开出来的连接：SDK 的 {@code close()} 会把 {@code userClosed} 置真，
     * 它自己的自动重连随之停下。不关就会留一条 SDK 在后台悄悄重连的连接，与本类的重连叠在一起，
     * 同一条消息被两条连接各收一次。
     */
    private void connect(EventDispatcher events) throws Exception {
        FeishuConnection opening = connectionFactory.open(events);
        try {
            opening.start();
            opening.awaitReady(properties.getReadyTimeout().toMillis());
        }
        catch (Exception failure) {
            closeQuietly(opening);
            throw failure;
        }
        FeishuConnection previous = this.connection;
        this.connection = opening;
        if (previous != null) {
            closeQuietly(previous);
        }
    }

    private void scheduleReconnect(EventDispatcher events, int attempt, Duration delay) {
        if (state.get() == FeishuConnectionState.STOPPED) {
            return;
        }
        scheduler.schedule(() -> reconnect(events, attempt), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 丢掉当前连接，排一次重连。
     *
     * <p>不就地重试一次：一条刚证明收不下事件的连接，下一次多半也收不下；重建要重新走握手与鉴权，
     * 那段代码与启动时的失败处理是同一处（{@link #scheduleReconnect}），不必写第二份。
     */
    private void dropAndReconnect() {
        FeishuConnection closing = this.connection;
        this.connection = null;
        if (closing != null) {
            closeQuietly(closing);
        }
        state.set(FeishuConnectionState.DISCONNECTED);
        EventDispatcher current = this.events;
        if (current != null) {
            scheduleReconnect(current, 1, INITIAL_BACKOFF);
        }
    }

    private void reconnect(EventDispatcher events, int attempt) {
        if (state.get() == FeishuConnectionState.STOPPED) {
            return;
        }
        try {
            connect(events);
            state.set(FeishuConnectionState.CONNECTED);
            log.info("飞书长连接第 {} 次重试后连上（appId={}）", attempt, mask(properties.getAppId()));
        }
        catch (Exception failure) {
            FeishuConnectionFailures.Verdict verdict = FeishuConnectionFailures.classify(failure);
            if (verdict.isPermanent()) {
                // 重试途中才变永久（比如应用被停用）：停下来，但不要把已经起来的应用带下去。
                state.set(FeishuConnectionState.STOPPED);
                log.error("飞书长连接第 {} 次重试遇到永久错（{}），停止重连（appId={}）：{}",
                        attempt, verdict.reason(), mask(properties.getAppId()), failure.toString());
                return;
            }
            Duration next = backoff(attempt + 1);
            state.set(FeishuConnectionState.DISCONNECTED);
            log.warn("飞书长连接第 {} 次重试失败（{}），下次 {} 秒后再试（appId={}）：{}",
                    attempt, verdict.reason(), next.toSeconds(), mask(properties.getAppId()),
                    failure.toString());
            scheduleReconnect(events, attempt + 1, next);
        }
    }

    /**
     * 第 {@code attempt} 次重连之前等多久：30 秒起，每次翻倍，封顶 10 分钟。
     * 上限必须真的封住，不封的话第 11 次就是 8.5 小时，那已经不像还在重连了。
     */
    static Duration backoff(int attempt) {
        Duration delay = INITIAL_BACKOFF;
        for (int step = 1; step < attempt; step++) {
            delay = delay.multipliedBy(2);
            if (delay.compareTo(MAX_BACKOFF) >= 0) {
                return MAX_BACKOFF;
            }
        }
        return delay;
    }

    /** 回调注册。卡片按钮与消息事件共用同一条长连接，所以两者都在这里挂。 */
    private EventDispatcher eventDispatcher() {
        EventDispatcher.Builder events = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        onMessage(event);
                    }
                });
        if (approvals != null) {
            // 卡片按钮走的是另一类事件（card.action.trigger），与消息事件共用同一条长连接：
            // 于是"点一下批准"不需要公网回调地址，也不需要另开一个入口（P03 的前提）。
            events.onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                @Override
                public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                    return onCardAction(event);
                }
            });
        }
        return events.build();
    }

    /**
     * 事件回调。返回就等于 ACK：调度层的 {@code accept} 只做落库与提交，不等模型。
     *
     * <p>两条失败路径刻意不同（票 04）：
     *
     * <ul>
     *   <li>翻译不了（图片、只有 @ 没有正文、正文不是 JSON）：吞掉并回一句人话。
     *       抛出去平台会重推同一条，而重推的还是翻译不了，失败、重推、再失败能跑好几轮。</li>
     *   <li>落不了库：往上抛，平台会因此重推，也就没有越过一条没落库的消息。OpenClaw 在同样
     *       情况下关掉那条 socket 并强制重建（{@code docs/channels/feishu/setup.md:45}：
     *       instead of continuing past an uncommitted turn）。吞掉它的现场最难查：
     *       平台以为收下了，库里没有这条，日志里只有一句处理失败。</li>
     * </ul>
     *
     * <p>包内可见是为了让用例直接打这两个分支，它们都不需要真凭据。
     */
    void onMessage(P2MessageReceiveV1 event) {
        Optional<FeishuInboundEvent> inbound;
        try {
            inbound = translator.translate(event);
        }
        catch (RuntimeException failure) {
            log.error("飞书事件翻译失败（不抛出，避免平台重推一条同样处理不了的消息）", failure);
            return;
        }
        if (inbound.isEmpty()) {
            replyCannotHandle(event);
            return;
        }
        try {
            dispatcher.accept(inbound.get());
        }
        catch (RuntimeException failure) {
            // 落库失败：这一条不能算收下。往上抛让平台重推，同时关掉这条连接强制重建
            // （OpenClaw 同处做法：close socket + fresh authenticated connection，
            // 而不是越过一条没落库的消息继续往下走）。
            log.error("飞书事件落不了库，关掉当前连接强制重建（appId={}）",
                    mask(properties.getAppId()), failure);
            dropAndReconnect();
            throw failure;
        }
    }

    /**
     * 卡片按钮的回调（P03）：返回的对象就是「就地换卡片加一句提示」。
     *
     * <p>不抛，理由同 {@link #onMessage}：抛出去平台会重推同一个点不动的按钮。
     * 但这里多一层考虑——点击是人在等回音（toast 会转圈），失败也要回一句，不能安静地什么都不做。
     */
    private P2CardActionTriggerResponse onCardAction(P2CardActionTrigger event) {
        try {
            return responseOf(approvals.on(clickOf(event)));
        }
        catch (RuntimeException failure) {
            log.error("飞书卡片回调失败（不抛出，避免平台重推同一次点击）", failure);
            return responseOf(FeishuApprovalActions.Outcome.of("error",
                    "这次点击没处理成功，稍后再点一次；如果一直这样，让管理员看服务日志。"));
        }
    }

    /** 卡片事件到三个值：谁点的、哪一条、批还是拒。别的都不进来，判定不需要它们。 */
    private static FeishuApprovalActions.Click clickOf(P2CardActionTrigger event) {
        P2CardActionTriggerData data = event == null ? null : event.getEvent();
        String openId = data == null || data.getOperator() == null ? null : data.getOperator().getOpenId();
        Map<String, Object> value = data == null || data.getAction() == null
                || data.getAction().getValue() == null
                ? Map.of()
                : data.getAction().getValue();
        return new FeishuApprovalActions.Click(openId,
                textOf(value.get(FeishuApprovalCard.VALUE_APPROVAL)),
                textOf(value.get(FeishuApprovalCard.VALUE_DECISION)));
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 结果转成飞书的响应体。{@code type=raw} 表示这一整张卡片换成给定的结构，
     * 于是同一条消息就地变了，群里不会出现第二条自相矛盾的卡片。
     */
    private static P2CardActionTriggerResponse responseOf(FeishuApprovalActions.Outcome outcome) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        toast.setType(outcome.toastType());
        toast.setContent(outcome.toastText());
        response.setToast(toast);
        if (outcome.card() != null) {
            CallBackCard card = new CallBackCard();
            card.setType("raw");
            card.setData(outcome.card());
            response.setCard(card);
        }
        return response;
    }

    /** 翻不出来的消息也要回一句：群里的沉默分不清「没收到」和「不会」。 */
    private void replyCannotHandle(P2MessageReceiveV1 event) {
        String chatId = chatIdOf(event);
        if (chatId == null) {
            log.warn("翻不出来的飞书事件里连 chat_id 都没有，只能记日志");
            return;
        }
        try {
            replySender.send(chatId, CANNOT_HANDLE_REPLY);
        }
        catch (RuntimeException failure) {
            log.error("回推'处理不了'失败：chatId={}", chatId, failure);
        }
    }

    private static String chatIdOf(P2MessageReceiveV1 event) {
        if (event == null || event.getEvent() == null || event.getEvent().getMessage() == null) {
            return null;
        }
        return event.getEvent().getMessage().getChatId();
    }

    private static void closeQuietly(FeishuConnection connection) {
        try {
            connection.close();
        }
        catch (RuntimeException ignored) {
            log.debug("关闭飞书连接时又出错，忽略", ignored);
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "claw4j-feishu-reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** App ID 不是密钥，但也没必要整串进日志：够对上配置就行。 */
    private static String mask(String appId) {
        if (appId == null || appId.length() <= 4) {
            return "****";
        }
        return appId.substring(0, 4) + "****";
    }
}
