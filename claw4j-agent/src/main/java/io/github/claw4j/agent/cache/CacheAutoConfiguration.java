package io.github.claw4j.agent.cache;

import io.github.claw4j.agent.model.EmbeddingAutoConfiguration;
import io.github.claw4j.agent.model.QwenModelCondition;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * 语义缓存的装配（M3-05）。
 *
 * <p><b>为什么整份配置挂 {@link QwenModelCondition} 上</b>：缓存靠向量判断"这个问题问过没有"，
 * 而向量只有通义有（DeepSeek 没有 embedding 接口）。所以配了 qwen key 才有这个能力——
 * 只配 DeepSeek 的环境里，缓存整套不装，对话照常。这比"装一个会在第一次请求时才失败的缓存"好：
 * 失败点前移到装配期，且能解释为什么没有它。
 *
 * <p><b>向量模型不在这里装配了</b>（P10）：它现在由 {@link EmbeddingAutoConfiguration} 提供，
 * 条件只有"配了 qwen key"，不含 {@code claw4j.cache.enabled}。原因是知识库也要用它，
 * 而"关掉缓存"是一个成本开关，不该顺带关掉一个功能。这里只**消费**它。
 * 缓存自己的条件仍然是"有向量能力才装"——它是可选优化，不该成为启动失败的原因。
 *
 * <p><b>为什么开关用 {@code @ConditionalOnProperty} 而不是 properties 里一个 boolean</b>：
 * 同一个键在两处判断，迟早会不一致（pitfalls 第十条）。这里只留一处，而且它挂在**类上**：
 * 关掉之后三个 bean（存储、清理任务、Advisor）一个都不装。
 * 挂在某个方法上是不够的——那样"配了关"仍然留着每小时跑一次的删除任务与一条 Advisor，
 * 配置看起来生效了，实际只关掉了一半。
 *
 * <p><b>缓存不参与可用性</b>：向量算不出来时 {@link CacheAdvisor} 退化成"没命中"，
 * 库写不进去只影响省成本，都不会让这一轮答不了话。它是成本优化，不是依赖。
 */
@AutoConfiguration(after = EmbeddingAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({SemanticCache.class, JdbcTemplate.class, EmbeddingModel.class})
@ConditionalOnBean(DataSource.class)
@Conditional(QwenModelCondition.class)
@ConditionalOnProperty(value = "claw4j.cache.enabled", matchIfMissing = true)
@EnableConfigurationProperties(CacheProperties.class)
// 清理任务是 @Scheduled，跟着本配置一起开：会话那份额外也开了，重复一次无害，
// 而依赖别人的 @EnableScheduling（比如 spring-ai-session 不在 classpath 时）会漏开。
@EnableScheduling
public class CacheAutoConfiguration {

    /**
     * 缓存存储。用主 {@code JdbcTemplate} 而不是另开连接池：这里的 SQL 是我们自己写的，
     * 表名写全限定，落点不依赖 {@code search_path}（与会话存储的区别见类注释）。
     */
    @Bean
    @ConditionalOnMissingBean(SemanticCache.class)
    public SemanticCache semanticCache(JdbcTemplate jdbc, CacheProperties properties) {
        return new PgVectorSemanticCache(jdbc, properties);
    }

    /** 过期清理。只配 TTL 不配清理 = 表一直长，而配置看起来已经成立了。 */
    @Bean
    @ConditionalOnMissingBean(SemanticCacheCleanup.class)
    public SemanticCacheCleanup semanticCacheCleanup(SemanticCache cache,
                                                     ObjectProvider<MeterRegistry> meterRegistry) {
        return new SemanticCacheCleanup(cache, meterRegistry.getIfAvailable());
    }

    /**
     * 缓存 Advisor。注册成 bean 就会被 {@code AgentAutoConfiguration#chatClient}
     * 的 {@code defaultAdvisors} 收走，调用方不需要知道有缓存这回事。
     *
     * <p>它消费**所有** {@code ToolPermissionRegistry}：判断"这一轮有没有副作用"要按工具名
     * 查它的动作，而权限登记表跟着工具走（ADR-0007）。一个都没登记时，任何工具调用都会被
     * 判成有副作用——保守方向是"不缓存"，不是"缓存了不该缓存的"。
     */
    @Bean
    @ConditionalOnMissingBean(CacheAdvisor.class)
    public CacheAdvisor cacheAdvisor(SemanticCache cache, EmbeddingModel embeddings,
                                     CacheProperties properties,
                                     ObjectProvider<ToolPermissionRegistry> permissions,
                                     ObjectProvider<MeterRegistry> meterRegistry) {
        return new CacheAdvisor(cache, embeddings, properties,
                permissions.orderedStream().toList(), meterRegistry.getIfAvailable());
    }
}
