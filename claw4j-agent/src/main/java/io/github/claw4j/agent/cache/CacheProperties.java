package io.github.claw4j.agent.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语义缓存的配置。
 *
 * <p><b>只有一项</b>：框架的 {@code DefaultSemanticCache} 能配的东西里，只有相似度阈值是我们
 * 需要改默认值的（它的默认是 0.8，见下面的实测表）。TTL、清理间隔、上下文维度这些它没有——
 * 它没有的我们不补，所以这里也没有对应的键。
 *
 * <p><b>字段用包装类型</b>：配置绑定对缺失的键只能传 null，原始类型会被填成 0 ——
 * 而这里 0 是有意义的坏值（阈值 0 = 任何候选都算命中）。漏一个包装类型，就等于给那一项
 * 配了一个谁都没写的 0。
 *
 * <p><b>没有 {@code enabled} 字段</b>：总开关由 {@code CacheAutoConfiguration} 类上那个
 * {@code @ConditionalOnProperty} 读 {@code claw4j.cache.enabled} 判定，关掉即整套不装。
 * 同一个键在 record 里再存一份、再写一个 {@code isEnabled()}，就是"两处判断同一个开关"——
 * 它们迟早会不一致，而表现是"配了关但还在缓存"（pitfalls 第十条）。
 *
 * @param similarityThreshold 余弦相似度到多少算"同一个问题"
 * @param redisUri            缓存实例的地址；语义缓存落 Redis Stack（RediSearch + RedisJSON）
 */
@ConfigurationProperties("claw4j.cache")
public record CacheProperties(Double similarityThreshold, String redisUri) {

    /** 默认与本机 compose 里的那个服务一致。 */
    public static final String DEFAULT_REDIS_URI = "redis://127.0.0.1:6379";

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
     * <p>看懂这张表就知道两件事：
     * <ol>
     *   <li><b>纯阈值救不了"换时间"</b>：它 0.995，比同义改写还高。这一档现在挡不住了——
     *       框架的缓存只有"系统提示词哈希 + 阈值"两个维度，没有"实体"这一维。
     *       这是照官方实现走之后接受的代价，记在 {@code docs/design/semantic-cache.md}。
     *   <li>所以 0.93 取的是<b>宁漏不误</b>那一侧：它挡得住换区域与换指标，
     *       代价是漏掉措辞差得多的同义改写（0.9117）。漏只是没省到钱，误是给出另一个问题的答案。
     *       顺带推翻票面原定的 0.95——那一档连 0.9470 的同义说法都会落空。
     * </ol>
     */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.93;

    public CacheProperties {
        // 越界一律落回默认值：0 或负数会让"任何候选都命中"，>1 则永远不命中，
        // 两者都不是"配置错了一点"，而是"这个功能变成了另一件事"。
        similarityThreshold = (similarityThreshold == null
                || similarityThreshold <= 0 || similarityThreshold > 1)
                ? DEFAULT_SIMILARITY_THRESHOLD : similarityThreshold;
        redisUri = (redisUri == null || redisUri.isBlank()) ? DEFAULT_REDIS_URI : redisUri;
    }

    public static CacheProperties defaults() {
        return new CacheProperties(null, null);
    }
}
