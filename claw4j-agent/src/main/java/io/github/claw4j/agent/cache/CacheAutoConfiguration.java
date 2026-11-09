package io.github.claw4j.agent.cache;

import io.github.claw4j.agent.model.EmbeddingAutoConfiguration;
import io.github.claw4j.agent.model.QwenModelCondition;
import org.springframework.ai.chat.cache.semantic.SemanticCache;
import org.springframework.ai.chat.cache.semantic.SemanticCacheAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.cache.semantic.DefaultSemanticCache;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.RedisClient;

/**
 * 语义缓存的装配（M3-05）。
 *
 * <p><b>整套用框架的</b>：Advisor 是 {@link SemanticCacheAdvisor}，存储是
 * {@link DefaultSemanticCache}（Redis Stack）。我们只负责装配与一个 order ——
 * 没有自己的存储实现、没有自己的清理任务、没有自己的指标。
 *
 * <p><b>为什么整份配置挂 {@link QwenModelCondition} 上</b>：缓存靠向量判断"这个问题问过没有"，
 * 而向量只有通义有（DeepSeek 没有 embedding 接口）。所以配了 qwen key 才有这个能力——
 * 只配 DeepSeek 的环境里，缓存整套不装，对话照常。
 *
 * <p><b>缓存是强依赖，这是照官方实现走之后的既定状态</b>：`DefaultSemanticCache` 在
 * {@code afterPropertiesSet} 之前不会建索引，而它自己不走 Spring 生命周期，所以要在这里
 * 显式调一次；那一步连不上 Redis 就直接启动失败。官方的 `adviseCall` 调 `get`/`set` 时
 * 也没有 try/catch，运行中 Redis 挂掉会让这一轮报错。这两条都不由我们再包一层去抹平——
 * 抹平就是自研，见 {@code docs/agents/alignment.md}。
 *
 * <p><b>为什么开关用 {@code @ConditionalOnProperty} 而不是 properties 里一个 boolean</b>：
 * 同一个键在两处判断，迟早会不一致（pitfalls 第十条）。这里只留一处，而且它挂在**类上**：
 * 关掉之后客户端、存储、Advisor 一个都不装。
 */
@AutoConfiguration(after = EmbeddingAutoConfiguration.class)
@ConditionalOnClass({SemanticCache.class, SemanticCacheAdvisor.class, RedisVectorStore.class})
@Conditional(QwenModelCondition.class)
@ConditionalOnProperty(value = "claw4j.cache.enabled", matchIfMissing = true)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration {

    /**
     * 缓存 advisor 在链上的位置：**记忆之后、工具循环与脱敏之前**。
     *
     * <ul>
     *   <li>比记忆大 → 记忆的 before 先把这条用户消息写进会话；命中时记忆的 after 照样会把
     *       "（同上次）"这条回答写回去，会话是连续的。</li>
     *   <li>比工具循环小 → 整通对话只查一次，不会在每一轮工具迭代上重复查。</li>
     *   <li>比脱敏大 → 命中时出站仍然过脱敏。</li>
     * </ul>
     *
     * <p>**必须显式传**：官方 advisor 的默认 order 是 {@code -2147483448}，排在记忆之前——
     * 命中时那条用户消息还没进会话，表现是"聊天记录里少了一轮"。
     */
    public static final int ADVISOR_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;

    /** 缓存实例的客户端。jedis 7 的 `RedisClient.create(URI)` 接受 `redis://` 形式的地址。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RedisClient.class)
    public RedisClient cacheRedisClient(CacheProperties properties) {
        return RedisClient.create(properties.redisUri());
    }

    /**
     * 框架的 Redis 实现。
     *
     * <p>`afterPropertiesSet` 必须手动调：`DefaultSemanticCache` 内部是 `new RedisVectorStore(...)`，
     * 而索引创建写在 `RedisVectorStore#afterPropertiesSet` 里，Spring 生命周期管不到那个对象。
     * 不调就是"索引不存在"，第一次读写直接报错。
     */
    @Bean
    @ConditionalOnMissingBean(SemanticCache.class)
    public SemanticCache semanticCache(RedisClient cacheRedisClient, EmbeddingModel embeddings,
                                       CacheProperties properties) {
        DefaultSemanticCache cache = DefaultSemanticCache.builder()
                .embeddingModel(embeddings)
                .jedisClient(cacheRedisClient)
                .similarityThreshold(properties.similarityThreshold())
                .build();
        ((RedisVectorStore) cache.getStore()).afterPropertiesSet();
        return cache;
    }

    /**
     * 框架的缓存 Advisor。注册成 bean 就会被 {@code AgentAutoConfiguration#chatClient}
     * 的 {@code defaultAdvisors} 收走，调用方不需要知道有缓存这回事。
     */
    @Bean
    @ConditionalOnMissingBean(SemanticCacheAdvisor.class)
    public SemanticCacheAdvisor semanticCacheAdvisor(SemanticCache cache) {
        return new SemanticCacheAdvisor(cache, ADVISOR_ORDER, Schedulers.boundedElastic());
    }
}
