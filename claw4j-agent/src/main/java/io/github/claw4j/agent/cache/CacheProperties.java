package io.github.claw4j.agent.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 语义缓存的四个数。
 *
 * <p><b>字段全部用包装类型</b>：配置绑定对缺失的键只能传 null，原始类型会被填成 0 ——
 * 而这里 0 是有意义的坏值（阈值 0 = 任何候选都算命中，TTL 0 = 存进去就过期）。
 * 这个坑在 M3-06 踩过一次（`ToolSearchProperties`），同样的形状不再踩第二次。
 *
 * <p><b>没有 {@code enabled} 字段</b>：总开关由装配层那个 {@code @ConditionalOnProperty}
 * 读 {@code claw4j.cache.enabled} 判定。同一个键在 record 里再存一份、再写一个
 * {@code isEnabled()}，就是"两处判断同一个开关"——它们迟早会不一致，
 * 而表现是"配了关但还在缓存"。这与 pitfalls 第十条是同一类问题。
 *
 * @param similarityThreshold 余弦相似度到多少算"同一个问题"
 * @param timeToLive         一条缓存活多久
 * @param cleanupInterval    多久扫一次过期行
 * @param embeddingModel     向量模型名。复用通义那套 key（DeepSeek 没有 embedding 接口），
 *                           只换模型名，所以它落在这里而不是模型配置里
 * @param embeddingDimensions 向量维度。**必须与表的 `vector(1024)` 一致**：
 *                           改这个数要新写一个迁移，插入维度不符会直接报错（不会静默截断）
 */
@ConfigurationProperties("claw4j.cache")
public record CacheProperties(Double similarityThreshold, Duration timeToLive,
                              Duration cleanupInterval, String embeddingModel,
                              Integer embeddingDimensions) {

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
     *   <li><b>纯阈值救不了"换时间"</b>：它 0.995，比同义改写还高。所以命中判定里
     *       另有一道词面守卫（时间词与数字必须完全一致），见 {@link CacheAdvisor}。
     *       这是补丁不是解法——语义相似度本来就分不清"上周"与"上上周"。
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

    /** 通义的第三代向量模型。这是唯一被实测过的取值（见 {@link #DEFAULT_SIMILARITY_THRESHOLD}）。 */
    public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";

    /** 与迁移里的 `vector(1024)` 成对。 */
    public static final int DEFAULT_EMBEDDING_DIMENSIONS = 1024;

    public CacheProperties {
        // 越界一律落回默认值：0 或负数会让"任何候选都命中"，>1 则永远不命中，
        // 两者都不是"配置错了一点"，而是"这个功能变成了另一件事"。
        similarityThreshold = (similarityThreshold == null
                || similarityThreshold <= 0 || similarityThreshold > 1)
                ? DEFAULT_SIMILARITY_THRESHOLD : similarityThreshold;
        timeToLive = (timeToLive == null || timeToLive.isNegative() || timeToLive.isZero())
                ? DEFAULT_TIME_TO_LIVE : timeToLive;
        cleanupInterval = (cleanupInterval == null || cleanupInterval.isNegative() || cleanupInterval.isZero())
                ? DEFAULT_CLEANUP_INTERVAL : cleanupInterval;
        embeddingModel = (embeddingModel == null || embeddingModel.isBlank())
                ? DEFAULT_EMBEDDING_MODEL : embeddingModel.trim();
        embeddingDimensions = (embeddingDimensions == null || embeddingDimensions <= 0)
                ? DEFAULT_EMBEDDING_DIMENSIONS : embeddingDimensions;
    }

    public static CacheProperties defaults() {
        return new CacheProperties(null, null, null, null, null);
    }
}
