package io.github.claw4j.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.claw4j.agent.model.EmbeddingAutoConfiguration;
import io.github.claw4j.agent.model.EmbeddingProperties;
import io.github.claw4j.agent.model.ModelProperties;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.capability.secret.SpringSecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

/**
 * 知识库的装配（P10）。
 *
 * <p><b>默认关</b>（{@code matchIfMissing = false}）：知识库要一份文档目录、一次 embedding
 * 调用，而绝大多数部署没有这两样。这与 {@code claw4j.skills.directory} 那几个键同一条约定——
 * 不配就是没有这一层，而不是"配了一个空目录"。
 *
 * <p><b>开着就必须能用</b>：缺目录、目录不存在、目录里没有可索引的文件、embedding 算不出来，
 * 四种都是**启动失败并点名**，与 P09「未分档即失败」、P11「开着却清单为空即失败」同构。
 * 反过来（启动成功但库是空的）更糟：每次检索都答"没有相关资料"，而那与"这段确实没写"
 * 在用户看来完全一样。
 *
 * <p><b>检索由官方的 {@link RetrievalAugmentationAdvisor} 在每轮请求里自动完成</b>：
 * 不是模型自己决定调不调（原来那个 {@code searchDocs} 工具已删除——留着就是每轮两次检索）。
 * 所以"开着知识库"等于"这个部署是带知识库的助手"，每轮一次检索是它的预期成本。
 *
 * <p><b>三处必须改的默认行为</b>（反编译 {@code before()} 与 {@code ContextualQueryAugmenter}
 * 得到，见 {@code docs/design/knowledge-retrieval.md} 第三节）：
 *
 * <ol>
 *   <li>默认模板会把用户消息整个换掉并要求"只按上下文回答" → 传自定义模板，无关就不引用；</li>
 *   <li>{@code allowEmptyContext} 默认 false，空检索时直接回"无法回答" → 设 true；</li>
 *   <li>{@code before()} 没有 try/catch，检索抛异常即整轮失败 → 接受（"库挂了"不该伪装成
 *       "文档没写"），但重排必须在实现内部吞掉。</li>
 * </ol>
 *
 * <p><b>向量模型从 {@link EmbeddingAutoConfiguration} 来</b>，不在这里装配：缓存与知识库
 * 共用同一个模型、同一份凭据，两处各装一个的话，"用哪个模型"会有两个答案。
 *
 * <p><b>索引在启动后跑一次</b>（{@link ApplicationRunner}），不是每次提问都跑：
 * 目录是事实来源，进程生命周期内它不会变；要重新索引就重启。不提供"模型主动重建索引"
 * 的工具——写知识库是运维的事，让模型能写就是给它一个把提示注入落库的口子。
 */
@AutoConfiguration(after = EmbeddingAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        beforeName = "io.github.claw4j.agent.AgentAutoConfiguration")
@ConditionalOnClass({JdbcTemplate.class, EmbeddingModel.class, RetrievalAugmentationAdvisor.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(value = "claw4j.rag.enabled", havingValue = "true")
@EnableConfigurationProperties(RagProperties.class)
public class RagAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagAutoConfiguration.class);

    /**
     * 知识库的向量表所在 schema。**表名与 schema 名只在这里写一次**：索引器、关键词那一路
     * 与建表的一侧都引用它。两处各写一遍字符串的后果是"表建在 A、查的是 B"，
     * 而那种错不会报错，只会让"库是空的"永远为真。
     */
    static final String VECTOR_SCHEMA = "claw4j_rag";

    /** 官方默认表名。由 {@code PgVectorStore} 建（{@code initializeSchema}），迁移里不建。 */
    static final String VECTOR_TABLE = "vector_store";

    /**
     * {@code content} 上的 trigram 索引名。
     *
     * <p>PG 的标识符上限 63 字符，这个名字 32 个，留了余量。
     */
    static final String TRIGRAM_INDEX = "ix_rag_vector_store_content_trgm";

    /**
     * 排在记忆之后。
     *
     * <p>{@code before()} 取的是 {@code prompt.getInstructions()}，记忆 advisor 还没加载历史时
     * 那个列表里只有本轮消息。顺序写错的表现是"检索用不到上文"——多轮里问"那它呢"，
     * 检索拿到的是"那它呢"三个字，而它本来可能是一句完整的问题。
     *
     * <p>也不用负数：默认的 {@code 0} 会与 {@code AgentContextAdvisor}（{@code HIGHEST_PRECEDENCE + 2}）
     * 抢在前面，虽然两者改的不是同一条消息，但"人格还没拼好就检索"没有好处。
     */
    static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 10;

    /**
     * 开着就必须有目录，**在构造期判定**。
     *
     * <p>放在这里而不是某个 {@code @Bean} 方法里，是因为 bean 的创建顺序不由我们决定：
     * 向量库那个 bean 会去连库建表，如果它先于索引器被创建，"没配目录"这件事就永远轮不到
     * 被说出来——报出来的是"连不上库"。判据属于配置，就该配置一绑好就判。
     */
    public RagAutoConfiguration(RagProperties properties) {
        if (!properties.hasDirectory()) {
            throw new IllegalStateException(
                    "claw4j.rag.enabled=true 但没配 claw4j.rag.directory："
                            + "知识库没有文档来源，检索会永远返回空。配一个目录，或者把它关掉");
        }
    }

    /**
     * 官方默认表名、官方距离函数、不建向量索引（HNSW / IVFFlat 都是近似召回，
     * 漏掉真正相关的那段不会以任何方式被看见；块数上万之后再单独一个迁移开）。
     */
    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore knowledgeVectorStore(JdbcTemplate jdbc, EmbeddingModel embeddings,
                                            EmbeddingProperties embedding) {
        return PgVectorStore.builder(jdbc, embeddings)
                .schemaName(VECTOR_SCHEMA)
                .vectorTableName(VECTOR_TABLE)
                .dimensions(embedding.dimensions())
                .idType(PgVectorStore.PgIdType.UUID)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(true)
                .build();
    }

    /**
     * 两路召回 + 融合。
     *
     * <p><b>向量那一路是官方的 {@link VectorStoreDocumentRetriever}</b>，只装配；
     * 全文那一路是我们实现同一个官方接口的 {@link KeywordDocumentRetriever}；
     * 融合是实现官方 {@code DocumentJoiner} 的 {@link WeightedScoreJoiner}（0.7 / 0.3，与 OpenClaw 同权重）。
     *
     * <p>{@code hybrid.enabled=false} 时**不装**全文那一路（传 null），而不是"装了不调用"——
     * 后者会让"配置说关了、代码里还在跑"这种偏差多一处藏身地。
     */
    @Bean
    @ConditionalOnMissingBean(DocumentRetriever.class)
    public DocumentRetriever knowledgeDocumentRetriever(VectorStore vectorStore, JdbcTemplate jdbc,
                                                        RagProperties properties) {
        RagProperties.Hybrid hybrid = properties.hybrid();
        DocumentRetriever vector = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(hybrid.candidatesPerRoute())
                .similarityThreshold(properties.minSimilarity())
                .build();
        DocumentRetriever keyword = hybrid.enabled()
                ? new KeywordDocumentRetriever(jdbc, KnowledgeIndexer.SOURCE_KIND,
                        hybrid.candidatesPerRoute())
                : null;
        return new HybridDocumentRetriever(vector, keyword, new WeightedScoreJoiner());
    }

    /**
     * 重排。两个条件都是"要么完整可用、要么不装"：
     *
     * <ul>
     *   <li>{@code rerank.enabled=false} → 不装（装配层判断，类里不再有第二个开关）；</li>
     *   <li>凭据**在调用时取**，取不到就不重排（WARN 一行）。不在启动时判：凭据可能轮换，
     *       {@code SpringSecretResolver} 明确不缓存，把"装不装 bean"钉死在启动那一刻
     *       等于让一次轮换后的恢复必须重启进程。</li>
     * </ul>
     *
     * <p>取不到 key 不等于启动失败：重排是排序优化，不是检索的前提。
     */
    @Bean
    @ConditionalOnProperty(value = "claw4j.rag.rerank.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(RerankDocumentPostProcessor.class)
    public RerankDocumentPostProcessor knowledgeReranker(ModelProperties model, RagProperties properties,
                                                         Environment environment, ObjectMapper json) {
        ModelProperties.Qwen qwen = model.getQwen();
        SecretResolver secrets = SpringSecretResolver.of(environment);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.rerank().timeout())
                .build();
        log.info("知识库重排已启用：{}（模型 {}，候选 {} 条，超时 {}）",
                properties.rerank().rerankUrl(), properties.rerank().model(),
                properties.rerankCandidates(), properties.rerank().timeout());
        return new RerankDocumentPostProcessor(http, json, properties.rerank().rerankUrl(),
                properties.rerank().model(), properties.rerank().timeout(),
                properties.rerankCandidates(),
                () -> qwen.apiKey().render(secrets));
    }

    /**
     * 编排器。这是整个知识库能力的入口——没有它就只剩一个没人调的 retriever 工厂。
     *
     * <p>它注册成 {@link Advisor}：{@code AgentAutoConfiguration#chatClient} 用
     * {@code ObjectProvider<Advisor>} 收容器里所有的 advisor，这里不点名任何 ChatClient。
     */
    @Bean
    @ConditionalOnMissingBean(RetrievalAugmentationAdvisor.class)
    public Advisor knowledgeAugmentationAdvisor(DocumentRetriever retriever, RagProperties properties,
                                                ObjectProvider<RerankDocumentPostProcessor> reranker) {
        ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(new PromptTemplate(PROMPT_TEMPLATE))
                .allowEmptyContext(true)
                .documentFormatter(RagAutoConfiguration::formatDocuments)
                .build();
        RerankDocumentPostProcessor processor = reranker.getIfAvailable();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                // 官方默认的 joiner：多路已经在 retriever 内部融过，这里每个 query 只有一路，
                // 它做"按 id 去重 + 按 score 倒序"。**所以融合分必须写进 Document 的 score**
                // （见 WeightedScoreJoiner）——否则它按 0.0 把只有全文那一路命中的块甩到末尾，
                // 融合的顺序当场作废。这不是"绕过官方实现"，是照它的排序信号喂数据。
                .documentJoiner(new ConcatenationDocumentJoiner())
                // 顺序即链路的顺序：先重排（换顺序、不截断），再截断到 top-k（唯一的截断点）。
                // 重排关掉时截断照样在，提示词不会被候选池撑满。
                .documentPostProcessors(postProcessors(processor, properties.topK()))
                .queryAugmenter(augmenter)
                .order(ORDER)
                .build();
    }

    /**
     * 重排在前面跑，截断最后跑；重排没装就只剩截断。
     *
     * <p><b>截断不跟着重排走</b>：重排是可以关的（默认开但是可选），而"注入几段"不是可选项。
     * 两件事绑一起的话，关掉重排就会把两路候选池（默认 30 段）整个塞进提示词，
     * 而那只在关掉一个开关时才出现，平时看不见。
     */
    static List<DocumentPostProcessor> postProcessors(RerankDocumentPostProcessor reranker, int topK) {
        TopKDocumentPostProcessor truncation = new TopKDocumentPostProcessor(topK);
        return reranker == null
                ? List.of(truncation)
                : List.of(reranker, truncation);
    }

    /**
     * 增强模板。**这是对官方默认模板的替换**，不是可选的风格调整：
     *
     * <p>官方默认模板要求"只根据上下文回答，不在上下文里就说不知道"，会把用户消息整个换成
     * 一个 RAG 提问。装在一个通用助手上，问"你好"也会被要求"只按资料回答"——人格、工具、
     * 常识全被压住。这里改成"资料相关就用、无关就忽略"，既保留 RAG 的好处，又不劫持对话。
     *
     * <p>{@code {query}} 与 {@code {context}} 两个占位符是 {@code PromptAssert} 要求的，
     * 少一个会在构建时抛。
     */
    static final String PROMPT_TEMPLATE = """
            下面是从内部知识库里检索到的资料，可能相关、也可能不相关。

            ---------------------
            {context}
            ---------------------

            用法：
            1. 资料确实回答了问题，就据此回答，并在句末用资料开头的编号标出处（例如「见 [2]」）。
            2. 资料与问题无关，就忽略它，按你平时的方式回答，不要提"资料里没有"。
            3. 不要在资料之外编造规定、数字或流程。

            问题：{query}

            回答：
            """;

    /**
     * 每段的注入格式：{@code [n] 路径 第 N 段} + 正文。
     *
     * <p>「带出处」这条从"工具返回值"搬到了这里。拼法只该有一处——两处拼就会写出两种格式，
     * 而其中一种一定漏掉出处，模型照着漏的那种引用，用户就回不到原文。
     */
    static String formatDocuments(List<Document> documents) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String citation = RagMetadata.citation(document);
            text.append('[').append(i + 1).append("] ").append(citation).append('\n')
                    .append(document.getText()).append('\n');
        }
        return text.toString();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeIndexer.class)
    public KnowledgeIndexer knowledgeIndexer(JdbcTemplate jdbc, VectorStore vectorStore,
                                            EmbeddingModel embeddings, RagProperties properties) {
        log.info("知识库已启用：目录 {}、最终取 {} 段、每路候选 {}、相似度下限 {}、块长 {} token、"
                        + "混合检索 {}、重排 {}",
                properties.directory(), properties.topK(),
                properties.hybrid().candidatesPerRoute(), properties.minSimilarity(),
                properties.chunkSize(),
                properties.hybrid().enabled() ? "开" : "关",
                properties.rerank().enabled() ? "开" : "关");
        return new KnowledgeIndexer(jdbc, vectorStore, embeddings, properties);
    }

    /**
     * 启动后：先建 trigram 索引，再同步一次目录。
     *
     * <p><b>索引为什么在这里建</b>：`vector_store` 由框架建，迁移跑的时候它还不存在，
     * 所以 `V5` 只建扩展。索引建不出来只 WARN——它影响的是速度，不是结果。
     *
     * <p>同步失败会让启动失败：空知识库不该被当成"正常启动"。
     */
    @Bean
    public ApplicationRunner knowledgeIndexRunner(KnowledgeIndexer indexer, JdbcTemplate jdbc) {
        return args -> {
            ensureTrigramIndex(jdbc);
            indexer.index();
        };
    }

    /**
     * 建全文那一路的索引。
     *
     * <p>{@code if not exists} 让它幂等：每次启动都执行一次，第二次是空操作。
     *
     * <p>失败只 WARN：没有这个索引时 `ilike '%q%'` 走全表扫描，结果一模一样，只是慢。
     * 把一个性能优化做成启动失败，等于"索引没建起来就用不了知识库"。
     */
    private static void ensureTrigramIndex(JdbcTemplate jdbc) {
        try {
            jdbc.execute("create index if not exists " + TRIGRAM_INDEX
                    + " on " + VECTOR_SCHEMA + "." + VECTOR_TABLE
                    + " using gin (content gin_trgm_ops)");
        }
        catch (RuntimeException failure) {
            log.warn("在 {}.{}(content) 上建 trigram 索引失败，全文检索会走全表扫描（结果不变）：{}",
                    VECTOR_SCHEMA, VECTOR_TABLE, failure.getMessage());
        }
    }
}
