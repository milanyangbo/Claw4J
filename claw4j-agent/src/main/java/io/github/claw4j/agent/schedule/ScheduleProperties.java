package io.github.claw4j.agent.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 定时调度的四个数（P04）：多久扫一次、一次最多几条、连着坏几次就停、占一条占多久。
 *
 * <p><b>为什么扫描间隔放在这里，而 {@code @Scheduled} 又读原文</b>：调度注解在容器启动时
 * 就要拿到值，那时属性绑定不一定已经完成。所以两边读同一个键（占位符原文 + 本对象），
 * 不会出现"配了 5 分钟但实际还是 30 秒"。代价是这个字段"我们不读、别人读"，
 * 于是它不能静默归一——{@code PT0S} 在这里被改成 30 秒，调度器那边照样拿到 0，
 * 而 {@code fixedDelay=0} 是"上一轮结束立刻下一轮"：一个热循环在打库。
 * 与 {@code CacheProperties#cleanupInterval} 同一个判据，宁可起不来。
 *
 * <p><b>其余三个越界都判红，不落回默认值</b>：它们各自都能把功能悄悄变成另一件事——
 * {@code maxPerScan=0} 是"谁都不跑"，{@code failureThreshold=0} 是"第一次失败就停用"
 * （那样一条网络抖一下就没了），{@code lease<=0} 是"没有租约"（认领后崩溃的那条永远没人再跑）。
 * 这三种都不会报错，只会在某天被问"它怎么不跑了"。
 *
 * <p><b>为什么失败阈值是 3</b>：一次失败多半是模型抽风或网络抖，两次是可疑，
 * 三次还能连着坏就是配置或权限出了问题。阈值再高，用户会在"它还在试"和"它已经死了"
 * 之间干等好几天；再低，一次抖动就停掉一个每天都在用的任务。
 *
 * <p><b>总开关不在这里</b>：{@code claw4j.schedule.enabled} 的读者是那个 {@code @Bean} 上的
 * {@code @ConditionalOnProperty}，本对象不是它的主人。在这里再声明一次，就是一个
 * "人人都以为自己读了"的键——哪天两边不一致，谁也不知道该以哪个为准。
 *
 * @param scanInterval     扫描间隔，默认 30 秒。它只由 {@code @Scheduled} 读取
 * @param maxPerScan       一次扫描最多认领几条，默认 5 条
 * @param failureThreshold 连续失败几次自动停用，默认 3 次
 * @param lease            一条被认领之后占多久，默认 5 分钟
 */
@ConfigurationProperties("claw4j.schedule")
public record ScheduleProperties(Duration scanInterval,
                                 Integer maxPerScan,
                                 Integer failureThreshold,
                                 Duration lease) {

    public static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofSeconds(30);

    public static final int DEFAULT_MAX_PER_SCAN = 5;

    public static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /**
     * 默认 5 分钟。它要长于一次 run 的常见耗时（否则另一个实例会把还在跑的那条抢走），
     * 又要短到"进程崩了之后不必等一小时"。一轮跑不完本来就会撞预算上限停下来，
     * 所以这里不需要照顾特别长的那种。
     */
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    public ScheduleProperties {
        scanInterval = scanInterval == null ? DEFAULT_SCAN_INTERVAL : scanInterval;
        if (scanInterval.isNegative() || scanInterval.isZero()) {
            throw new IllegalArgumentException(
                    "claw4j.schedule.scan-interval 必须为正：" + scanInterval
                            + "。它由 @Scheduled 直接读取原文，归一回默认只会让调度器与配置对不上");
        }
        maxPerScan = maxPerScan == null ? DEFAULT_MAX_PER_SCAN : maxPerScan;
        if (maxPerScan <= 0) {
            throw new IllegalArgumentException(
                    "claw4j.schedule.max-per-scan 必须为正：" + maxPerScan
                            + "。0 的意思是“一条都不跑”，而那看起来和“没有到点的任务”一模一样");
        }
        failureThreshold = failureThreshold == null ? DEFAULT_FAILURE_THRESHOLD : failureThreshold;
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException(
                    "claw4j.schedule.failure-threshold 必须为正：" + failureThreshold
                            + "。0 会让第一次失败就停用一个每天都在用的任务");
        }
        lease = lease == null ? DEFAULT_LEASE : lease;
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException(
                    "claw4j.schedule.lease 必须为正：" + lease
                            + "。没有租约，认领之后崩溃的那条任务没人敢再跑");
        }
    }

    public static ScheduleProperties defaults() {
        return new ScheduleProperties(null, null, null, null);
    }
}
