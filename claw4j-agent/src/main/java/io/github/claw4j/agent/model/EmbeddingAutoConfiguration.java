package io.github.claw4j.agent.model;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;
import io.github.claw4j.capability.secret.SpringSecretResolver;
import io.github.claw4j.secret.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

/**
 * 向量模型：**一个部署只有一份**，缓存的与知识库的消费者共用。
 *
 * <p><b>它为什么独立于缓存</b>（P10）：不带 {@code claw4j.cache.enabled} 这个条件。
 * 语义缓存是省钱开关、知识库是功能，两件事的变更节奏完全不同——绑在一起的话，
 * 关掉缓存会连带让知识库起不来。
 * 条件只剩"配了通义 key"，两个消费者各自用，谁也不绑谁。
 *
 * <p><b>为什么只有通义这一家</b>：DeepSeek 没有 embedding 接口。所以向量能力跟着
 * {@code claw4j.model.qwen.*} 那套凭据走，不新增一组配置、也不新增一把钥匙。
 *
 * <p><b>为什么用 {@code OpenAiEmbeddingModel}</b>：通义提供的是 OpenAI 兼容端点
 * （{@code /compatible-mode/v1}），与 {@code qwenChatModel} 同一个接缝。
 */
@AutoConfiguration
@ConditionalOnClass({EmbeddingModel.class, OpenAIClient.class})
@Conditional(QwenModelCondition.class)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingAutoConfiguration.class);

    /**
     * 向量模型。
     *
     * <p>{@code metadataMode} 必须显式给：Builder 上它没有默认值，不给就是 NPE，
     * 而那个 NPE 出现在第一次 {@code embed} 调用时，看起来像"通义挂了"。
     *
     * <p>重试关掉：缓存那边算不出向量就退化成"没命中"，知识库那边索引失败会在启动时
     * 明确报错——两处都不需要在这里重试三轮，那只是拖慢一次提问。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(ModelProperties model, EmbeddingProperties embedding,
                                        Environment environment) {
        ModelProperties.Qwen qwen = model.getQwen();
        SecretResolver secrets = SpringSecretResolver.of(environment);
        SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(qwen.getReadTimeout())
                .build();
        ClientOptions options = ClientOptions.builder()
                .baseUrl(qwen.getBaseUrl())
                // 与聊天模型同一个接缝：每次请求现取，真值不进 ClientOptions（P08）。
                .credential(BearerTokenCredential.create(() -> qwen.apiKey().render(secrets)))
                .httpClient(httpClient)
                .timeout(qwen.getReadTimeout())
                .maxRetries(0)
                .checkJacksonVersionCompatibility(false)
                .build();
        OpenAIClient client = new OpenAIClientImpl(options);

        log.info("已装配向量模型：baseUrl={}, model={}, dimensions={}, 凭据={}（缓存与知识库共用）",
                qwen.getBaseUrl(), embedding.model(), embedding.dimensions(),
                qwen.apiKey().text());

        return OpenAiEmbeddingModel.builder()
                .openAiClient(client)
                .metadataMode(MetadataMode.EMBED)
                .options(OpenAiEmbeddingOptions.builder()
                        .model(embedding.model())
                        .dimensions(embedding.dimensions())
                        .build())
                .build();
    }
}
