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
 * 一段对话现在叫什么（ADR-0026 决定一）。
 *
 * <p><b>它解决的是"会话没有结束"这件事</b>。在此之前会话 id 永远不变：
 * 记忆因此永不换段、预算只能自己按空闲猜、TTL 只能整行删掉。用户想开一段新对话，
 * 唯一的手段是清空浏览器——而 web 端的 {@code chatId} 缺省就等于 {@code userId}，
 * 所以他连"开一段新对话"这个动作都没有。
 *
 * <p><b>换段 = 换对话，不是删数据</b>：旧段留在库里只读（04 才决定留多久），
 * 新段有一条干净的上下文与一份新的预算。把"结束"和"忘记"分开，是因为它们的代价不同：
 * 结束是一句话的事，忘记是不可逆的。
 *
 * <p><b>指针读不到时不拦这一轮</b>：库没迁、连不上、语句报错——退回加段之前的形状
 * （{@code SessionKeys} 的老口径），打一条 WARN，记一个计数。段回答的是"这段对话叫什么"，
 * 不是"能不能回答"。这与 {@code FaultTolerantSessionService} 是同一个判断：
 * 记忆是附加信息，不是可用性依赖。反过来（抛出去）会把每一句话都打成 500，
 * 而根因是"少了一张表"——那两件事的代价差得太远。
 *
 * <p><b>降级之后会发生什么，要说清</b>：所有对话退到同一个永不过期的 id，
 * 也就是<b>回到改造之前的行为</b>——不是更糟，只是这个改造没生效。计数
 * {@code claw4j.session.pointer.failures} 就是"它到底生效了没有"的唯一信号，
 * 别让它变成静默。
 */
public final class ConversationPointers {

    private static final Logger log = LoggerFactory.getLogger(ConversationPointers.class);

    /** 指针失败的计数。它不为 0 就说明段这个改造当前没生效。 */
    public static final String FAILURE_METRIC = "claw4j.session.pointer.failures";

    /**
     * 存储。可为 null——没有数据库时没有"段"这回事，那是能力不在场，不是配置错误，
     * 所以不造一个假实现去假装它存在（与 {@code ChatClientAgentPort} 对 run 存储的判断一致）。
     */
    private final ConversationPointerStore store;

    private final SessionResetProperties reset;

    private final ZoneId zone;

    private final Clock clock;

    /** 可不给。装不装 actuator 只影响报不报得出去，不影响该不该换段。 */
    private final MeterRegistry registry;

    /** 日志刷屏的闸：同一个进程里只把第一条说清楚，后面的只计数。 */
    private boolean warned;

    public ConversationPointers(ConversationPointerStore store, SessionResetProperties reset,
                                ZoneId zone, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.reset = reset;
        this.zone = zone;
        this.clock = clock;
        this.registry = registry;
    }

    /**
     * 这段对话现在的段。
     *
     * @return 段；空串表示"不加段"——要么 {@code mode: none}，要么指针读不到。
     *         调用方把它原样交给 {@link SessionKeys#sessionIdOf(String, String, String, String)}，
     *         空串就是改造之前的形状
     */
    public String segmentOf(String owner, String channel, String chatId) {
        if (!reset.rotates() || store == null || owner == null || owner.isBlank()) {
            return "";
        }
        Instant now = clock.instant();
        ConversationPointerStore.Rotation rotation = new ConversationPointerStore.Rotation(
                freshSegment(now), now, now.minusMillis(reset.idleMillis()),
                reset.logicalDayOf(now, zone), reset.followsDaily());
        try {
            return store.segmentOf(new ConversationPointerStore.Key(
                    owner, SessionKeys.placeholder(channel), SessionKeys.placeholder(chatId)), rotation)
                    .orElse("");
        } catch (RuntimeException unreadable) {
            count();
            if (!warned) {
                warned = true;
                log.warn("会话指针不可用，这批对话退回加段之前的形状（记忆不会按段隔离，"
                        + "预算也不会因换段清零）；之后同类失败只计数不再打日志：{}", unreadable.toString());
            }
            return "";
        }
    }

    /**
     * 新段的名字：{@code <epochMilli>-<4 位十六进制>}。
     *
     * <p>时间前缀不是为了唯一性（随机四位才是），是为了两件事：清理时能按段排序，
     * 排查时能一眼看出"这段对话是什么时候开始的"。UUID 也能唯一，但它在库里
     * 什么信息都不给——而这张表存在的意义就是让人看得懂。
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
                .description("会话指针读写失败：段当前没生效")
                .register(registry)
                .increment();
    }
}
