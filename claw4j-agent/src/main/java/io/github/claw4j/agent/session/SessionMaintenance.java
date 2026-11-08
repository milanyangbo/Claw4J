package io.github.claw4j.agent.session;

import io.github.claw4j.agent.schedule.ScheduleProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 旧会话怎么清（ADR-0026 的收尾）。判据照 OpenClaw 的 {@code session.maintenance}。
 *
 * <p><b>为什么不是"到点就删行"</b>：框架那条 {@code expires_at} 在<b>建行</b>时
 * 写死 {@code now + ttl}，之后不随活动刷新。于是"每天都在用的一段对话"与"三天前用过一次"
 * 在库里长得一样，一起被删——用户看到的是"第 30 天记忆突然全空，没有任何提示"。
 * 01 的段让活跃对话永远落在新行上，把这个症状<b>盖住</b>了，但判据本身没修：
 * 只要有人把 {@code idle-minutes} 调成 30 天、或者用 {@code mode: none}，误删立刻回来。
 *
 * <p><b>现在的判据是"这一段的最后一次动静"</b>：{@code coalesce(max(事件时间), 建行时间)}。
 * 事件时间保证"刚说过话的段不会被删"，建行时间兜住"段刚建、事件还没落库"那个窄窗口。
 * 这对应 OpenClaw 的 {@code pruneAfter} 判 {@code lastInteractionAt}。
 *
 * <p><b>四条语句各管一件事</b>：
 * <ul>
 *   <li>段级：{@code prune-after} 内没动静的段（它同时覆盖"整段对话没人要了"）；</li>
 *   <li>桶级：{@code prune-after} 没动的指针行——段删了它也该走，否则这张表只长不消；</li>
 *   <li>上限：指针行超过 {@code max-entries} 时驱逐最久没动的（OpenClaw 的 {@code maxEntries}）；</li>
 *   <li>cron：定时运行留下的段按 {@code claw4j.schedule.session-retention}（默认 24h）单独留，
 *       它的口径与"人的对话"不同——一天一段，按 30 天留会让整张表都是没人看的 cron 段。</li>
 * </ul>
 *
 * <p><b>{@code warn} 下不删，所以必须"报得出来"</b>：默认档位是 {@code warn}（与 OpenClaw 一致），
 * 它唯一的产出就是"本来会删多少"。不进日志与指标的话，这个默认档位就是一次静默空转——
 * {@link SessionCleanup} 的注释早就写过这是清理任务最典型的失败姿势。
 *
 * <p><b>库不可用时不抛</b>：这里是后台任务，抛出去只会让 {@code @Scheduled} 每小时刷一次全栈，
 * 而"清理没跑成"与"没有东西可清"在结果上都是 0 行。所以 WARN 一次、计数、返回空报告——
 * 与 {@code FaultTolerantSessionService} 同一个判断。区别是本类直接写 SQL、
 * 中间没有那一层降级，所以降级要自己接。
 */
public final class SessionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(SessionMaintenance.class);

    /** 一段时间没动静的段。{@code group by ... having} 让"取每组最后一次事件"只算一遍。 */
    private static final String STALE_SEGMENTS_TEMPLATE = """
            select a.id
            from %1$sai_session a
            left join %1$sai_session_event e on e.session_id = a.id
            where a.id not like ?
            group by a.id, a.created_at
            having coalesce(max(e."timestamp"), a.created_at) < ?
            """;

    private static final String DELETE_STALE_SEGMENTS_TEMPLATE =
            "delete from %1$sai_session where id in (" + STALE_SEGMENTS_TEMPLATE + ")";

    /**
     * 一段时间没动静的指针行。
     *
     * <p>它把 cron 的段排除在外（{@code not like '%:cron:%'}）：那一类有自己的一条短留存，
     * 两边都数一遍会让报告里的数字互相吃掉，也让"这次删了几条"答不清楚。
     *
     * <p>与桶级那条分开，是因为它们的依据不同：段看"最后一次事件"，桶看"最后一次访问"。
     * 一个刚换段的人可能一条事件都没写（换段那次是纯读），但指针行必须被刷新。
     */
    private static final String STALE_CONVERSATIONS_TEMPLATE =
            "select owner, channel, chat_id from %1$sconversation_pointer where touched_at < ?";

    private static final String DELETE_STALE_CONVERSATIONS_TEMPLATE =
            "delete from %1$sconversation_pointer where touched_at < ?";

    /** 超出 {@code max-entries} 的那些桶，最久没动的排在前面。 */
    private static final String OVER_LIMIT_TEMPLATE = """
            select owner, channel, chat_id
            from %1$sconversation_pointer
            order by touched_at desc, owner, channel, chat_id
            offset ?
            """;

    private static final String DELETE_OVER_LIMIT_TEMPLATE = """
            delete from %1$sconversation_pointer
            where (owner, channel, chat_id) in (""" + OVER_LIMIT_TEMPLATE + ")";

    /**
     * 定时运行留下的段。
     *
     * <p>{@code like '%:cron:%'} 靠的是 03 定的形状（{@code conversationId = cron:<taskId>:<时刻>}），
     * 而段的形状里<b>不含冒号</b>，所以它不会误伤普通对话的段。唯一的例外是一个
     * {@code chatId} 恰好含 {@code :cron:} 的自定义通道——那是保留字，写在
     * {@code SessionKeys} 的注释里。
     */
    private static final String STALE_CRON_SEGMENTS_TEMPLATE = """
            select a.id
            from %1$sai_session a
            left join %1$sai_session_event e on e.session_id = a.id
            where a.id like ?
            group by a.id, a.created_at
            having coalesce(max(e."timestamp"), a.created_at) < ?
            """;

    private static final String DELETE_STALE_CRON_SEGMENTS_TEMPLATE =
            "delete from %1$sai_session where id in (" + STALE_CRON_SEGMENTS_TEMPLATE + ")";

    private static final String CRON_MARKER = "%:cron:%";

    /** 本来会被清掉的数量。它是 {@code warn} 档位唯一能看得见的东西。 */
    public static final String PRUNABLE = "claw4j.session.maintenance.prunable";

    /** 真删掉的累计数量。 */
    public static final String PRUNED = "claw4j.session.maintenance.pruned";

    /** 清理跑不成（库连不上、表没迁）的次数。它不为 0 就说明这些天一行都没清。 */
    public static final String FAILURES = "claw4j.session.maintenance.failures";

    private final JdbcTemplate jdbc;
    private final SessionMaintenanceProperties properties;
    private final ScheduleProperties schedule;
    private final MeterRegistry registry;

    /** 八条语句的 schema 前缀已在构造时展开：{@code claw4j.session.schema} 改了它们跟着改。 */
    private final String staleSegments;
    private final String deleteStaleSegments;
    private final String staleConversations;
    private final String deleteStaleConversations;
    private final String overLimit;
    private final String deleteOverLimit;
    private final String staleCronSegments;
    private final String deleteStaleCronSegments;

    private final AtomicLong prunableSegments = new AtomicLong();
    private final AtomicLong prunableConversations = new AtomicLong();
    private final AtomicLong prunableOverLimit = new AtomicLong();
    private final AtomicLong prunableCron = new AtomicLong();

    private boolean warned;

    /**
     * @param schema 会话表所在的 schema。本类在主连接池上跑（{@code search_path} 里没有会话 schema），
     *               只能写全限定名，所以"落在哪个 schema"必须由这个参数决定，不能由字面量决定。
     *               <b>与框架那一路同源</b>：两边都读 {@code claw4j.session.schema}，
     *               否则改配之后框架往新 schema 写、这里清旧的，看起来全绿而一行都没清对
     */
    public SessionMaintenance(JdbcTemplate jdbc, SessionMaintenanceProperties properties,
                              ScheduleProperties schedule, MeterRegistry registry, String schema) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.schedule = schedule;
        this.registry = registry;
        String qualifier = SessionSchema.qualifier(schema);
        this.staleSegments = STALE_SEGMENTS_TEMPLATE.formatted(qualifier);
        this.deleteStaleSegments = DELETE_STALE_SEGMENTS_TEMPLATE.formatted(qualifier);
        this.staleConversations = STALE_CONVERSATIONS_TEMPLATE.formatted(qualifier);
        this.deleteStaleConversations = DELETE_STALE_CONVERSATIONS_TEMPLATE.formatted(qualifier);
        this.overLimit = OVER_LIMIT_TEMPLATE.formatted(qualifier);
        this.deleteOverLimit = DELETE_OVER_LIMIT_TEMPLATE.formatted(qualifier);
        this.staleCronSegments = STALE_CRON_SEGMENTS_TEMPLATE.formatted(qualifier);
        this.deleteStaleCronSegments = DELETE_STALE_CRON_SEGMENTS_TEMPLATE.formatted(qualifier);
        if (registry != null) {
            gauge("segments", prunableSegments);
            gauge("conversations", prunableConversations);
            gauge("over-limit", prunableOverLimit);
            gauge("cron", prunableCron);
        }
    }

    /**
     * 跑一次。
     *
     * @param now 当前时刻。测试把 cutoff 推到未来就能验"过 30 天之后会怎样"而不用真等
     * @return 这一次的账。库不可用时是 {@link Report#NONE}
     */
    public Report sweep(Instant now) {
        Instant segmentCutoff = now.minus(properties.pruneAfter());
        Instant cronCutoff = now.minus(schedule.sessionRetention());
        try {
            long segments = measure(staleSegments, CRON_MARKER, timestamp(segmentCutoff));
            long conversations = measure(staleConversations, timestamp(segmentCutoff));
            long maxEntries = properties.maxEntries();
            long overLimitRows = measure(overLimit, maxEntries);
            long cron = measure(staleCronSegments, CRON_MARKER, timestamp(cronCutoff));

            prunableSegments.set(segments);
            prunableConversations.set(conversations);
            prunableOverLimit.set(overLimitRows);
            prunableCron.set(cron);

            if (!properties.enforces()) {
                if (segments + conversations + overLimitRows + cron > 0) {
                    log.info("会话清理（warn，未执行）：段 {} / 桶 {} / 超限 {} / cron {}。"
                            + "设 claw4j.session.maintenance.mode=enforce 才会真删", segments, conversations,
                            overLimitRows, cron);
                }
                return new Report(segments, conversations, overLimitRows, cron, false);
            }

            long prunedSegments = delete(deleteStaleSegments, CRON_MARKER, timestamp(segmentCutoff));
            long prunedConversations = delete(deleteStaleConversations, timestamp(segmentCutoff));
            long prunedOverLimit = delete(deleteOverLimit, maxEntries);
            long prunedCron = delete(deleteStaleCronSegments, CRON_MARKER, timestamp(cronCutoff));

            increment(PRUNED, "segments", prunedSegments);
            increment(PRUNED, "conversations", prunedConversations);
            increment(PRUNED, "over-limit", prunedOverLimit);
            increment(PRUNED, "cron", prunedCron);

            if (prunedSegments + prunedConversations + prunedOverLimit + prunedCron > 0) {
                log.info("会话清理：删了段 {} / 桶 {} / 超限 {} / cron {}",
                        prunedSegments, prunedConversations, prunedOverLimit, prunedCron);
            }
            return new Report(segments, conversations, overLimitRows, cron,
                    prunedSegments, prunedConversations, prunedOverLimit, prunedCron, true);
        } catch (DataAccessException unavailable) {
            // 只把第一条说清楚，后面的只计数：一小时一条全栈日志会把真正的问题淹掉。
            increment(FAILURES);
            if (!warned) {
                warned = true;
                log.warn("会话清理跑不成（库连不上或表没迁），这些天一行都不会被清：{}",
                        unavailable.getMessage());
            }
            return Report.NONE;
        }
    }

    private long measure(String sql, Object... args) {
        Long value = jdbc.queryForObject("select count(*) from (" + sql + ") candidate", Long.class, args);
        return value == null ? 0 : value;
    }

    private long delete(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    private void gauge(String kind, AtomicLong value) {
        Gauge.builder(PRUNABLE, value, AtomicLong::get)
                .tags("kind", kind)
                .description("这一次本来会被清掉的数量（warn 档位下不会执行）")
                .register(registry);
    }

    private void increment(String metric, String kind, long amount) {
        if (registry == null || amount <= 0) {
            return;
        }
        Counter.builder(metric).tags("kind", kind)
                .description("真删掉的数量")
                .register(registry)
                .increment(amount);
    }

    private void increment(String metric) {
        if (registry != null) {
            Counter.builder(metric).description("清理跑不成的次数").register(registry).increment();
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    /**
     * 一次清理的账。
     *
     * @param segments       判定为陈旧的段
     * @param conversations  判定为陈旧的指针行
     * @param overLimit      超出上限、该驱逐的指针行
     * @param cronSegments   判定为陈旧的定时运行段
     * @param prunedSegments 真删掉的段（{@code warn} 下恒为 0）
     * @param prunedConversations 真删掉的指针行
     * @param prunedOverLimit 真驱逐的指针行
     * @param prunedCron     真删掉的定时运行段
     * @param enforced       这一轮是不是真删了
     */
    public record Report(long segments, long conversations, long overLimit, long cronSegments,
                         long prunedSegments, long prunedConversations, long prunedOverLimit,
                         long prunedCron, boolean enforced) {

        /** 库不可用时的空账：什么都没判、什么都没删。 */
        public static final Report NONE = new Report(0, 0, 0, 0, 0, 0, 0, 0, false);

        /** {@code warn} 那一支：判定了，但一行没删。四个 {@code pruned*} 因此是 0。 */
        Report(long segments, long conversations, long overLimit, long cronSegments, boolean enforced) {
            this(segments, conversations, overLimit, cronSegments, 0, 0, 0, 0, enforced);
        }

        /** 判定为陈旧的总数。 */
        public long stale() {
            return segments + conversations + overLimit + cronSegments;
        }

        /** 真删掉的总数。 */
        public long pruned() {
            return prunedSegments + prunedConversations + prunedOverLimit + prunedCron;
        }
    }
}
