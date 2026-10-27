package io.github.claw4j.agent.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 会话存储的可调项。
 *
 * <p>前两项是部署相关（schema 落哪、连接池多大），中间三项是压缩相关，最后一项是清理间隔。
 * 没有什么"要不要用记忆"这个选项（ADR-0005），也没有"换实现"这个选项
 * （{@code InMemorySessionRepository} 被架构测试挡住）。
 *
 * @param schema        会话表所在的 schema，默认 {@value SessionSchema#DEFAULT_SCHEMA}
 * @param poolSize      会话连接池大小。默认是 2：会话读写是"每轮一写一读"的小事务，
 *                      不是业务查询那种可能跑满 5 秒的重活，给多了只是占连接
 * @param contextWindow 上下文窗口的 token 数，压缩阈值按它的比例算。
 *                      换模型要跟着改——但只有一个数要改，而不是把阈值整个重算一遍
 * @param triggerRatio  到窗口的百分之多少就压。默认 0.5：留一半给"压完之后的这一轮"，
 *                      压到 0.9 时下一轮又会超，等于每轮都压一次
 * @param eventsToKeep  压缩时保留最近多少条事件不压。全压掉的话"刚才说的那件事"也会没
 * @param cleanupInterval 过期会话的清理间隔。<b>我们不读它，读它的是
 *                      {@code SessionCleanup} 上 {@code @Scheduled} 的占位符原文</b>；
 *                      它出现在这里只为了在绑定期把坏值挡下来——{@code PT0S} 会让
 *                      {@code fixedDelay} 变成 0，清理任务退化成一个热循环在打库。
 *                      同 {@code CacheProperties#cleanupInterval}，那边的注释写了完整理由
 */
@ConfigurationProperties("claw4j.session")
public record SessionMemoryProperties(String schema, int poolSize,
                                      int contextWindow, double triggerRatio, int eventsToKeep,
                                      Duration cleanupInterval) {

    /** 连接池的默认值。 */
    public static final int DEFAULT_POOL_SIZE = 2;

    /** deepseek-chat 的上下文窗口是 64k，这里取 65536。 */
    public static final int DEFAULT_CONTEXT_WINDOW = 65536;

    public static final double DEFAULT_TRIGGER_RATIO = 0.5;

    public static final int DEFAULT_EVENTS_TO_KEEP = 20;

    /**
     * 保留窗口的下限。{@code RecursiveSummarizationCompactionStrategy} 的 {@code overlapSize}
     * 默认 2 且必须小于保留条数，所以配成 1 或 2 会让 {@code build()} 抛异常。
     * 在这里兜住，报错说的是"claw4j.session.events-to-keep 太小"，而不是框架那句
     * "overlapSize (2) must be less than maxEventsToKeep (2)"。
     */
    public static final int MIN_EVENTS_TO_KEEP = 3;

    /** 与 {@code SessionCleanup} 占位符里的 {@code PT1H} 同一个数：那边是"没配时的兜底"，这里是"归一后的值"。 */
    public static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofHours(1);

    public SessionMemoryProperties {
        schema = (schema == null || schema.isBlank()) ? SessionSchema.DEFAULT_SCHEMA : schema.trim();
        poolSize = poolSize <= 0 ? DEFAULT_POOL_SIZE : poolSize;
        contextWindow = contextWindow <= 0 ? DEFAULT_CONTEXT_WINDOW : contextWindow;
        // 1.0 与 0 一样是错的：等于"永远压"或者"窗口满了才压"，两种都退化成没有压缩。
        triggerRatio = (triggerRatio <= 0 || triggerRatio >= 1) ? DEFAULT_TRIGGER_RATIO : triggerRatio;
        eventsToKeep = eventsToKeep <= 0 ? DEFAULT_EVENTS_TO_KEEP : Math.max(eventsToKeep, MIN_EVENTS_TO_KEEP);
        // 这一项不能归一，只能拒绝：调度器读的是占位符里的原文，这里悄悄改成 1 小时
        // 并不会改变它拿到的值，只会让"配置写的是 0"这件事查不出来。
        if (cleanupInterval == null) {
            cleanupInterval = DEFAULT_CLEANUP_INTERVAL;
        } else if (cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException(
                    "claw4j.session.cleanup-interval 必须为正：" + cleanupInterval
                            + "。它由 @Scheduled 直接读取原文，归一回默认只会让调度器与配置对不上");
        }
    }

    /** 压缩阈值：按窗口比例算，不是一个写死的绝对数。 */
    public int compactionThreshold() {
        return (int) Math.round(contextWindow * triggerRatio);
    }

    public static SessionMemoryProperties defaults() {
        return new SessionMemoryProperties(null, 0, 0, 0, 0, null);
    }
}
