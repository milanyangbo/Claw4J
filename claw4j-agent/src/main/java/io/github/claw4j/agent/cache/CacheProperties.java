package io.github.claw4j.agent.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 语义缓存的几项配置。
 *
 * <p><b>字段全部用包装类型</b>：配置绑定对缺失的键只能传 null，原始类型会被填成 0 ——
 * 而这里 0 是有意义的坏值（阈值 0 = 任何候选都算命中，TTL 0 = 存进去就过期）。
 * 这个坑在 M3-06 踩过一次（`ToolSearchProperties`），同样的形状不再踩第二次。
 *
 * <p><b>没有 {@code enabled} 字段</b>：总开关由 {@code CacheAutoConfiguration} 类上那个
 * {@code @ConditionalOnProperty} 读 {@code claw4j.cache.enabled} 判定，关掉即整套不装。
 * 同一个键在 record 里再存一份、再写一个 {@code isEnabled()}，就是"两处判断同一个开关"——
 * 它们迟早会不一致，而表现是"配了关但还在缓存"。这与 pitfalls 第十条是同一类问题。
 *
 * @param similarityThreshold 余弦相似度到多少算"同一个问题"
 * @param timeToLive         一条缓存活多久
 * @param cleanupInterval    多久扫一次过期行。**它的问题不是我们不读，是别人读**：
 *                           调度器走 {@code @Scheduled} 的占位符拿原文，不经过这个 record，
 *                           所以越界值在这里"归一回默认"只会让两边对不上（见构造器）
 *
 * <p><b>向量模型的两项配置不在这里</b>（P10 搬走了）：它们去了 {@code claw4j.model.embedding}，
 * 因为知识库也要用同一个向量模型，而这边那个 {@code enabled} 是成本开关。
 * 把"用哪个模型"放进一个会被关掉的功能的配置里，关掉它就连带关掉了别处的依赖。
 */
@ConfigurationProperties("claw4j.cache")
public record CacheProperties(Double similarityThreshold, Duration timeToLive,
                              Duration cleanupInterval) {

    /**
     * 默认阈值 <b>0.93</b>，这个数是实测出来的，不是拍的。用 {@code text-embedding-v3}
     * （dimensions=1024）对两组问句算余弦，基准分别是「上周华东成交额多少」与「上周华东区成交额多少」：
     *
     * <pre>
     * 同一句话                        1.0000
     * 只多了个「是」                   0.9987
     * 词序颠倒                        0.9967
     * **换时间**（上上周…）            0.9950 / 0.9956   ← 意思完全不同，却比同义改写更像
     * 同义（额 → 销售额）              0.9470 / 0.9501
     * 同义改写（…卖了多少）            0.9372 / 0.9117   ← 同一类改写，两组差了 0.026
     * **换指标**（订单量）             0.9187 / 0.9154   ← 与同义改写**交叠**
     * 换区域（华南）                   0.8968 / 0.9057
     * </pre>
     *
     * <p>看懂这张表就知道三件事：
     * <ol>
     *   <li><b>纯阈值救不了"换时间"</b>：它 0.995，比同义改写还高。所以"时间词与数字"
     *       被编进上下文键（{@link CacheAdvisor#entitySignature}）——这两句不在同一个上下文里，
     *       压根不互相检索。它挡的是"问的不是同一段时间"，不是"这句话问的是什么"，
     *       后者仍然靠阈值。
     *   <li><b>"换指标"与"同义改写"的分数是交叠的</b>（0.9154 vs 0.9117~0.9501）。
     *       也就是说任何阈值都做不到"命中所有同义改写、同时挡住所有换指标"——
     *       这不是调参能解决的，要看的是往哪边错。
     *   <li>所以 0.93 取的是<b>宁漏不误</b>那一侧：它挡得住换区域与换指标，
     *       代价是漏掉措辞差得多的同义改写（0.9117）。漏只是没省到钱，误是给出另一个问题的答案。
     *       顺带推翻票面原定的 0.95——那一档连 0.9470 的同义说法都会落空。
     * </ol>
     */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.93;

    /**
     * 默认 1 小时，比票面写的 24 小时短得多。
     *
     * <p>理由是这个业务问的是**相对时间**（上周、本周、近 7 天），答案随日历翻页而变。
     * 缓存 24 小时的话，周一早上问"上周"命中的是周日晚上算出来的"上周"——两个不同的一周。
     * 1 小时覆盖的是这个缓存真正的收益场景（同一个人十分钟内换个说法再问一遍），
     * 而把跨周、跨月首日错配的概率压到很低。
     */
    public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofHours(1);

    public static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofHours(1);

    public CacheProperties {
        // 越界一律落回默认值：0 或负数会让"任何候选都命中"，>1 则永远不命中，
        // 两者都不是"配置错了一点"，而是"这个功能变成了另一件事"。
        similarityThreshold = (similarityThreshold == null
                || similarityThreshold <= 0 || similarityThreshold > 1)
                ? DEFAULT_SIMILARITY_THRESHOLD : similarityThreshold;
        timeToLive = (timeToLive == null || timeToLive.isNegative() || timeToLive.isZero())
                ? DEFAULT_TIME_TO_LIVE : timeToLive;
        // 清理间隔是唯一一个"我们不读、别人读"的字段：调度器从 @Scheduled 的占位符拿原文。
        // 于是它不能静默归一——`PT0S` 在这里被改成 1 小时，调度器那边照样拿到 0，
        // fixedDelay=0 是"上一轮结束立刻下一轮"，一个热循环在打库。这种值只能拒绝。
        // 判据与"启动就炸"那一格一致：宁可起不来，也不留一个看起来配好了的错配置。
        cleanupInterval = cleanupInterval == null ? DEFAULT_CLEANUP_INTERVAL : cleanupInterval;
        if (cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException(
                    "claw4j.cache.cleanup-interval 必须为正：" + cleanupInterval
                            + "。它由 @Scheduled 直接读取原文，归一回默认只会让调度器与配置对不上");
        }
    }

    public static CacheProperties defaults() {
        return new CacheProperties(null, null, null);
    }
}
