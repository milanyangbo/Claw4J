package io.github.claw4j.agent.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量模型的两项配置。**它不在 {@code claw4j.cache} 下面**（P10 之前在那里）。
 *
 * <p><b>为什么搬出来</b>：向量模型有两个消费者——语义缓存（"这个问题问过没有"）与
 * 知识库（"哪段文档相关"）。它原先住在 {@code CacheAutoConfiguration} 里，条件之一是
 * {@code claw4j.cache.enabled}。于是"关掉语义缓存"这个**省钱开关**会连带关掉知识库要用的
 * 向量模型，而且失败的样子是"知识库起不来"或者"得打开一个用不上的缓存来换"。
 *
 * <p>现在它的条件只剩一条：**配了通义那家的 key**（DeepSeek 没有 embedding 接口）。
 * 关掉缓存之后这个 bean 还在，但没人调它——它是懒的，只有第一次 {@code embed} 才发请求。
 *
 * @param model      向量模型名
 * @param dimensions 维度。**必须与两张表的 {@code vector(1024)} 一致**（缓存与知识库）：
 *                   改这个数要同时写两个迁移，插入维度不符会直接报错（不会静默截断）
 */
@ConfigurationProperties("claw4j.model.embedding")
public record EmbeddingProperties(String model, Integer dimensions) {

    /** 通义的第三代向量模型。这是唯一被实测过的取值（见 {@code CacheProperties} 的阈值表）。 */
    public static final String DEFAULT_MODEL = "text-embedding-v3";

    /** 与迁移里的 `vector(1024)` 成对。 */
    public static final int DEFAULT_DIMENSIONS = 1024;

    public EmbeddingProperties {
        model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        // 0 或负数不是"配错了一点"：它会得到一个长度为零的向量，而 vector(1024) 会拒收它，
        // 表现为"每次检索都报维度错"。不如在这里就落回默认值。
        dimensions = (dimensions == null || dimensions <= 0) ? DEFAULT_DIMENSIONS : dimensions;
    }

    public static EmbeddingProperties defaults() {
        return new EmbeddingProperties(null, null);
    }
}
