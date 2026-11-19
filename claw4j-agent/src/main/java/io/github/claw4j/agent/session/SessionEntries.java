package io.github.claw4j.agent.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一段对话现在叫什么、最后一次交互是什么时候（ADR-0026 决定一；ADR-0029 起落在
 * {@code session_nodes} 的一行 + {@code session_windows} 的一世）。
 *
 * <p>它解决的是"会话没有结束"这件事：在此之前会话 id 永远不变，记忆因此永不换段、预算只能
 * 按空闲猜、TTL 只能整行删掉。用户想开一段新对话，唯一的手段是清空浏览器；而 web 端的
 * {@code chatId} 缺省等于 {@code userId}，他连这个动作都没有。
 *
 * <p>换段等于换对话，不是删数据：旧段留在 {@code ai_session} 里只读，新段有干净的上下文与
 * 一份新的预算。目标跟着 entry 一起清掉——它是这一段对话的承诺，不是历史，
 * 旧段的记忆还在，但那件事已经不用再推。
 *
 * <p>指针读不到时不拦这一轮：库没迁、连不上、语句报错，都退回不加段的形状
 * （{@code sessionId} 就是节点键本身），打一条 WARN，记一个计数。段回答的是"这段对话叫什么"，
 * 不是"能不能回答"，与 {@link FaultTolerantSessionService} 同一个判断。
 *
 * <p>降级之后所有对话退到"每个节点一段、永不换段"，也就是回到改造之前的行为，不是更糟。
 * {@code claw4j.session.pointer.failures} 是"它到底生效了没有"的唯一信号。
 */
public final class SessionEntries {

    private static final Logger log = LoggerFactory.getLogger(SessionEntries.class);

    /** 状态行失败的计数。它不为 0 就说明段这个改造当前没生效。 */
    public static final String FAILURE_METRIC = "claw4j.session.pointer.failures";

    /**
     * 存储。可为 null——没有数据库时没有"段"这回事，那是能力不在场，不是配置错误，
     * 所以不造一个假实现去假装它存在。
     */
    private final SessionEntryStore store;

    private final SessionResetProperties reset;

    private final ZoneId zone;

    private final Clock clock;

    /** 可不给。装不装 actuator 只影响报不报得出去，不影响该不该换段。 */
    private final MeterRegistry registry;

    /** 日志刷屏的闸：同一个进程里只把第一条说清楚，后面的只计数。 */
    private boolean warned;

    private final SessionScopeProperties scopes;

    public SessionEntries(SessionEntryStore store, SessionResetProperties reset,
                          SessionScopeProperties scopes, ZoneId zone, Clock clock,
                          MeterRegistry registry) {
        this.store = store;
        this.reset = reset;
        this.scopes = scopes;
        this.zone = zone;
        this.clock = clock;
        this.registry = registry;
    }

    /**
     * 生效的两个 scope。
     *
     * <p>给 {@code ChatClientAgentPort} 用：它要拼这一轮的会话键，而键的 peer 段是按这两个值算的。
     * 让它自己持一份的话，配置有两处来源，"改了配置但只有一半生效"这种事迟早发生。
     *
     * @return 生效值，构造时已经规整过
     */
    public SessionScopeProperties scopes() {
        return scopes;
    }

    /**
     * 这段对话现在的段。
     *
     * @param owner   归属键，来自 {@link SessionKeys#ownerOf}
     * @param direct  是不是私聊，来自 {@code InboundMessage#direct()}；决定节点键拼成什么
     * @param channel 通道，可以为 null
     * @param chatId  通道里的地址，可以为 null
     * @return 段；空串表示"没有段"——没有库，或者读不到。调用方把它原样交给
     *         {@link SessionKeys#sessionIdOf(String, String)}，空串就是不加段的形状
     */
    public String segmentOf(String owner, boolean direct, String channel, String chatId) {
        return segment(owner, direct, channel, chatId, false);
    }

    /**
     * 把这一段结束掉，回新段的名字（用户发了 {@code /new} 或 {@code /reset}）。
     *
     * <p>单独一个方法，不给 {@link #segmentOf} 加 boolean："现在这段叫什么"与"把这段结束掉"
     * 是两个动词，加参数的话每个调用点都得重新想一遍"我要的是哪个"。
     *
     * <p>{@code reset.mode: none} 时照样换：那一档的语义是"不自动结束"，不是"不许结束"
     * （ADR-0029）。人的显式动作优先于自动策略。
     *
     * @param owner   归属键，来自 {@link SessionKeys#ownerOf}
     * @param direct  是不是私聊
     * @param channel 通道，可以为 null
     * @param chatId  通道里的地址，可以为 null
     * @return 新段；空串表示"没换成"（没有库，或读不到）
     */
    public String rotate(String owner, boolean direct, String channel, String chatId) {
        return segment(owner, direct, channel, chatId, true);
    }

    private String segment(String owner, boolean direct, String channel, String chatId,
                           boolean forced) {
        if (store == null || owner == null || owner.isBlank()) {
            return "";
        }
        Instant now = clock.instant();
        String normalizedChannel = SessionKeys.placeholder(channel);
        String normalizedChat = SessionKeys.placeholder(chatId);
        // 定位键不含段：目标与段都挂在这个节点上，当前段是节点的一列（ADR-0029）。
        String conversationKey = SessionKeys.conversationKeyOf(
                owner, direct, normalizedChannel, normalizedChat, scopes);
        // mode: none 时"永不过期"用两个开关一起表达：关掉日历那一半，把空闲线拉到纪元。
        // 不用"如果是 none 就跳过库访问"：段现在是表里的一行，不查就不知道当前是哪一段。
        Instant idleBefore = reset.rotates() ? now.minusMillis(reset.idleMillis()) : Instant.EPOCH;
        SessionEntryStore.Rotation rotation = new SessionEntryStore.Rotation(
                freshSegment(now), now, idleBefore,
                reset.dailyResetAt(now, zone), reset.followsDaily(), forced);
        try {
            return store.segmentOf(
                            new SessionEntryStore.Key(conversationKey, owner, normalizedChannel,
                                    normalizedChat, direct), rotation)
                    .orElse("");
        } catch (RuntimeException unreadable) {
            count();
            if (!warned) {
                warned = true;
                log.warn("会话状态行不可用，这批对话退回不加段的形状（记忆不会按段隔离，"
                        + "目标也不会因换段清空）；之后同类失败只计数不再打日志：{}", unreadable.toString());
            }
            return "";
        }
    }

    /**
     * 新段的名字：{@code <epochMilli>-<4 位十六进制>}。
     *
     * <p>时间前缀不是为了唯一性（随机四位才是），是为了两件事：清理时能按段排序，排查时能一眼
     * 看出这段对话什么时候开始。真实的开始时刻另存一列（{@code session_started_at}），
     * 判定不解析这个字符串。
     */
    private static String freshSegment(Instant now) {
        return now.toEpochMilli() + "-" + String.format("%04x",
                ThreadLocalRandom.current().nextInt(0x1_0000));
    }

    private void count() {
        if (registry == null) {
            return;
        }
        Counter.builder(FAILURE_METRIC)
                .description("会话状态行读写失败：段当前没生效")
                .register(registry)
                .increment();
    }
}
