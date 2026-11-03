package io.github.claw4j.agent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库的几项配置。
 *
 * <p><b>字段全部用包装类型</b>：配置绑定对缺失的键只能传 null，原始类型会被填成 0 ——
 * 而这里的 0 都是坏值（topK 0 = 永远检索不到东西，块长 0 = 切不出块）。与
 * {@code CacheProperties} 同一个理由，同一个坑不踩第二次。
 *
 * <p><b>没有 {@code enabled} 字段</b>：总开关由 {@code RagAutoConfiguration} 类上的
 * {@code @ConditionalOnProperty} 读 {@code claw4j.rag.enabled} 判定。同一个键在 record 里
 * 再存一份就是"两处判断同一个开关"，表现是"配了关但还在检索"（pitfalls 第十条）。
 *
 * <p><b>默认关</b>（{@code matchIfMissing = false}）：知识库要一份文档目录与一次 embedding
 * 调用才能建起来，而绝大多数部署没有这两样。这与 {@code claw4j.skills.directory} 那几个键
 * 同一条约定——不配就是没有这一层，不是"配了一个空目录"。
 *
 * @param directory     文档目录。**开着却没配它等于启动失败**，不是"空知识库"
 * @param topK          每路各取多少条、融合后再取多少条
 * @param minSimilarity 向量通道的下限。**这个数没有实测依据**（见常量注释），
 *                      所以它只做"把明显不相关的挡掉"，真正的排序交给 RRF 的排名
 * @param maxChars      一块文档最多多少字符
 */
@ConfigurationProperties("claw4j.rag")
public record RagProperties(String directory, Integer topK, Double minSimilarity, Integer maxChars) {

    public static final int DEFAULT_TOP_K = 5;

    /**
     * 向量通道的下限，默认 <b>0.5</b>。
     *
     * <p><b>必须说清楚：这个数没有实测支撑</b>，与缓存的 0.93 不是一回事。0.93 是对着两组
     * 问句量出来的（见 {@code CacheProperties#DEFAULT_SIMILARITY_THRESHOLD} 那张表）；
     * 这里没有"文档块与问题的相似度"的实测集，所以 0.5 只是一个保守的出发点。
     *
     * <p>它只干一件粗活：把"几乎肯定不相关"的块挡在融合之外。真正的排序交给
     * {@code RankFusion} 的排名——那里不需要跨通道的可比分数，也就不需要一个调准的阈值。
     * 调它之前先攒一批真实问题，否则就是把一个猜测换成另一个猜测。
     */
    public static final double DEFAULT_MIN_SIMILARITY = 0.5;

    public static final int DEFAULT_MAX_CHARS = 800;

    public RagProperties {
        directory = (directory == null || directory.isBlank()) ? null : directory.trim();
        topK = (topK == null || topK <= 0) ? DEFAULT_TOP_K : topK;
        minSimilarity = (minSimilarity == null || minSimilarity <= 0 || minSimilarity > 1)
                ? DEFAULT_MIN_SIMILARITY : minSimilarity;
        maxChars = (maxChars == null || maxChars <= 0) ? DEFAULT_MAX_CHARS : maxChars;
    }

    /** 开了就必须有目录：没有目录的知识库检索永远返回空，而"空"与"没有这段"分不开。 */
    public boolean hasDirectory() {
        return directory != null;
    }

    public static RagProperties defaults() {
        return new RagProperties(null, null, null, null);
    }
}
