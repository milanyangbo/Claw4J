package io.github.claw4j.agent.cache;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.agent.model.QwenModelCondition;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
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
 * <p><b>为什么整份配置挂 {@link QwenModelCondition} 上</b>：向量要走通义的
 * {@code text-embedding-v3}（DeepSeek 没有 embedding 接口），而且票面要求
 * **复用 {@code claw4j.model.qwen.*} 那套凭据**，不新增一组。所以只有配了 qwen key
 * 才有这个能力——只配 DeepSeek 的环境里，缓存整套不装，对话照常。
 * 这比"装一个会在第一次请求时才失败的缓存"好：失败点前移到装配期，且能解释为什么没有它。
 *
 * <p><b>为什么开关用 {@code @ConditionalOnProperty} 而不是 properties 里一个 boolean</b>：
 * 同一个键在两处判断，迟早会不一致（pitfalls 第十条）。这里只留一处，而且它挂在**类上**：
 * 关掉之后四个 bean（向量模型、存储、清理任务、Advisor）一个都不装。
 * 挂在某个方法上是不够的——那样"配了关"仍然留着每小时跑一次的删除任务和一个用不上的
 * 向量模型，配置看起来生效了，实际只关掉了一半。
 *
 * <p><b>缓存不参与可用性</b>：向量算不出来时 {@link CacheAdvisor} 退化成"没命中"，
 * 库写不进去只影响省成本，都不会让这一轮答不了话。它是成本优化，不是依赖。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({SemanticCache.class, JdbcTemplate.class, EmbeddingModel.class})
@ConditionalOnBean(DataSource.class)
@Conditional(QwenModelCondition.class)
@ConditionalOnProperty(value = "claw4j.cache.enabled", matchIfMissing = true)
@EnableConfigurationProperties(CacheProperties.class)
// 清理任务是 @Scheduled，跟着本配置一起开：会话那份额外也开了，重复一次无害，
// 而依赖别人的 @EnableScheduling（比如 spring-ai-session 不在 classpath 时）会漏开。
@EnableScheduling
public class CacheAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheAutoConfiguration.class);

    /**
     * 向量模型。用 {@code OpenAiEmbeddingModel} 是因为通义提供的是 OpenAI 兼容端点
     * （{@code /compatible-mode/v1}），与 {@code qwenChatModel} 走同一套凭据。
     *
     * <p>{@code metadataMode} 必须显式给：Builder 上它没有默认值，不给就是 NPE，
     * 而那个 NPE 出现在第一次 {@code embed} 调用时，看起来像"通义挂了"。
     *
     * <p>维度写死在配置里（默认 1024），与迁移里 {@code vector(1024)} 成对。
     * 不一致时 Postgres 直接报错——这是想要的：静默截断会让两个不同的问题看起来很相似。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel cacheEmbeddingModel(ModelProperties model, CacheProperties cache) {
        ModelProperties.Qwen qwen = model.getQwen();
        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(qwen.getReadTimeout())
                .build();
        ClientOptions options = ClientOptions.builder()
                .baseUrl(qwen.getBaseUrl())
                .apiKey(qwen.getApiKey())
                .httpClient(httpClient)
                .timeout(qwen.getReadTimeout())
                // 重试关掉：向量算不出来就退化成"没命中"，在这里重试三轮只是拖慢一次提问。
                .maxRetries(0)
                .checkJacksonVersionCompatibility(false)
                .build();
        OpenAIClient client = new OpenAIClientImpl(options);

        log.info("已装配向量模型：baseUrl={}, model={}, dimensions={}",
                qwen.getBaseUrl(), cache.embeddingModel(), cache.embeddingDimensions());

        return OpenAiEmbeddingModel.builder()
                .openAiClient(client)
                .metadataMode(MetadataMode.EMBED)
                .options(OpenAiEmbeddingOptions.builder()
                        .model(cache.embeddingModel())
                        .dimensions(cache.embeddingDimensions())
                        .build())
                .build();
    }

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
    public CacheAdvisor cacheAdvisor(SemanticCache cache, EmbeddingModel cacheEmbeddingModel,
                                     CacheProperties properties,
                                     ObjectProvider<ToolPermissionRegistry> permissions,
                                     ObjectProvider<MeterRegistry> meterRegistry) {
        return new CacheAdvisor(cache, cacheEmbeddingModel, properties,
                permissions.orderedStream().toList(), meterRegistry.getIfAvailable());
    }
}
