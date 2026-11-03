package io.github.claw4j.agent.rag;

import io.github.claw4j.agent.model.EmbeddingAutoConfiguration;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
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
 * <p><b>向量模型从 {@link EmbeddingAutoConfiguration} 来</b>，不在这里装配：缓存与知识库
 * 共用同一个模型、同一份凭据，两处各装一个的话，"用哪个模型"会有两个答案。
 *
 * <p><b>索引在启动后跑一次</b>（{@link ApplicationRunner}），不是每次提问都跑：
 * 目录是事实来源，进程生命周期内它不会变；要重新索引就重启。这一版不提供"模型主动重建索引"
 * 的工具——写知识库是运维的事，让模型能写就是给它一个把提示注入落库的口子。
 */
@AutoConfiguration(after = EmbeddingAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({JdbcTemplate.class, EmbeddingModel.class, KnowledgeBase.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(value = "claw4j.rag.enabled", havingValue = "true")
@EnableConfigurationProperties(RagProperties.class)
public class RagAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagAutoConfiguration.class);

    /**
     * 知识库在授权表里占的"表名"。
     *
     * <p><b>它是伪表名</b>：{@code claw4j_rag.chunk} 不是业务表，但鉴权问的是同一个问题——
     * "这个身份能不能看这份数据"。复用 {@code read} 动作 + 一个表名，好处是"能不能看知识库"
     * 与"能不能查销售单"用同一套授权表表达，不必新增一个动作名（新增动作名意味着所有角色
     * 配置都要改一遍才能检索）。
     *
     * <p>代价要在 {@code application.yml} 的注释里写出来：只给了 {@code region} 的角色
     * （如 {@code guest}）默认看不到知识库。那是**想要**的默认，但不写出来就会变成
     * "配了知识库，可某些人问什么都说没有"。
     */
    static final String DOC_TABLE = "doc";

    @Bean
    @ConditionalOnMissingBean(KnowledgeBase.class)
    public KnowledgeBase knowledgeBase(JdbcTemplate jdbc, EmbeddingModel embeddings,
                                       RagProperties properties) {
        return new PgVectorKnowledgeBase(jdbc, embeddings, properties);
    }

    /**
     * 索引器。目录没配就在这里失败——**装配期**失败比启动后失败好：
     * 后者会先打一行"应用已启动"，然后才在一个 {@code ApplicationRunner} 里炸，
     * 看起来像"起来了又挂了"。
     */
    @Bean
    @ConditionalOnMissingBean(KnowledgeIndexer.class)
    public KnowledgeIndexer knowledgeIndexer(JdbcTemplate jdbc, EmbeddingModel embeddings,
                                             RagProperties properties,
                                             PlatformTransactionManager transactions) {
        if (!properties.hasDirectory()) {
            throw new IllegalStateException(
                    "claw4j.rag.enabled=true 但没配 claw4j.rag.directory："
                            + "知识库没有文档来源，检索会永远返回空。配一个目录，或者把它关掉");
        }
        log.info("知识库已启用：目录 {}、每路取 {} 条、向量下限 {}、块长 {} 字符",
                properties.directory(), properties.topK(), properties.minSimilarity(),
                properties.maxChars());
        return new KnowledgeIndexer(jdbc, embeddings, properties, transactions);
    }

    /** 启动后同步一次。失败会让启动失败——空知识库不该被当成"正常启动"。 */
    @Bean
    public ApplicationRunner knowledgeIndexRunner(KnowledgeIndexer indexer) {
        return args -> indexer.index();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeTools.class)
    public KnowledgeTools knowledgeTools(KnowledgeBase knowledge) {
        return new KnowledgeTools(knowledge);
    }

    /**
     * 工具来源。
     *
     * <p><b>不要写 {@code @ConditionalOnMissingBean(ToolCallbackProvider.class)}</b>：
     * 那条条件的语义是"容器里已经有 provider 了就让位"，于是这个来源一旦排在别的来源后面
     * 就静默消失——不报错、不告警，只是模型手上没这个工具（{@code SalesExampleAutoConfiguration}
     * 里踩过同一个坑）。
     */
    @Bean
    @ConditionalOnBean(KnowledgeTools.class)
    public ToolCallbackProvider knowledgeToolCallbackProvider(KnowledgeTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    /**
     * 权限：{@code read} + 伪表 {@code doc}。
     *
     * <p>与工具放在同一个类里是刻意的：只有写这个工具的人知道它会碰什么（ADR-0007）。
     */
    @Bean
    public ToolPermissionRegistry knowledgeToolPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(
                KnowledgeTools.TOOL_NAME, ToolPermission.read(DOC_TABLE)));
    }

    /**
     * 档位：{@link ToolTier#MESSAGING}。
     *
     * <p>与"查表"同一档，理由也一样：知识库回答的是业务问题（政策、规定、口径），
     * 而 {@code messaging} 是"只做消息与问答"那种最常见的落地形态。把它推到
     * {@code coding} 或 {@code full}，等于让一个只会聊天的部署连政策都查不到——
     * 那不是"裁剪能力"，是"这个助手不会回答业务问题"。
     *
     * <p>不放进 {@code minimal}：那一档只有找工具、看状态、改计划、回忆会话四件事，
     * 是"最小能动"的集合；检索文档需要索引、向量与一张表，不属于"最小"。
     */
    @Bean
    public ToolTierRegistry knowledgeToolTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(KnowledgeTools.TOOL_NAME), ToolTier.MESSAGING);
    }
}
