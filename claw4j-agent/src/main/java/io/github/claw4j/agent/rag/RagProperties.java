package io.github.claw4j.agent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 知识库的几项配置。
 *
 * <p>字段全部用包装类型：配置绑定对缺失的键只能传 null，原始类型会被填成 0，而这里的 0 都是坏值
 * （topK 0 永远检索不到东西，块长 0 切不出块）。与 {@code CacheProperties} 同一个理由。
 *
 * <p>没有 {@code enabled} 字段：总开关由 {@code RagAutoConfiguration} 类上的
 * {@code @ConditionalOnProperty} 读 {@code claw4j.rag.enabled} 判定。同一个键在 record 里再存
 * 一份就是"两处判断同一个开关"，表现是"配了关但还在检索"（pitfalls 第十条）。
 *
 * <p>默认关（{@code matchIfMissing = false}）：知识库要一份文档目录与一次 embedding 调用才能建
 * 起来，绝大多数部署没有这两样。这与 {@code claw4j.skills.directory} 那几个键同一条约定。
 *
 * <p>开着知识库就是"带知识库的助手"：检索由 {@code RetrievalAugmentationAdvisor} 在每轮请求里
 * 自动完成，不是模型自己决定调不调。所以下面两个开关是成本旋钮，不是能力开关。
 *
 * @param directory     文档目录。开着却没配它等于启动失败，不是"空知识库"
 * @param topK          一次检索最终取回多少段
 * @param minSimilarity 相似度下限，只作用于向量那一路
 * @param chunkSize     一块最多多少 token（不是字符）。切块见 {@link SemanticTextSplitter}
 * @param hybrid        混合检索（向量 + 全文两路）。关掉只剩向量
 * @param rerank        重排。关掉只影响顺序，不影响召回
 */
@ConfigurationProperties("claw4j.rag")
public record RagProperties(String directory, Integer topK, Double minSimilarity, Integer chunkSize,
                            Hybrid hybrid, Rerank rerank) {

    public static final int DEFAULT_TOP_K = 5;

    /**
     * 相似度下限，默认 0.5。
     *
     * <p>这个数没有实测支撑，与缓存的 0.93 不是一回事。0.93 是对着两组问句量出来的（见
     * {@code CacheProperties#DEFAULT_SIMILARITY_THRESHOLD} 那张表）；这里没有"文档块与问题的
     * 相似度"的实测集，0.5 只是一个保守的出发点。
     *
     * <p>它只干一件粗活：把"几乎肯定不相关"的块挡掉。分数是余弦，调它之前先攒一批真实问题，
     * 否则就是把一个猜测换成另一个猜测。
     */
    public static final double DEFAULT_MIN_SIMILARITY = 0.5;

    /**
     * 一块最多多少 token，默认 800。
     *
     * <p>单位是 token 而不是字符：同一个数字在字符口径下意味着块小一半。切块见
     * {@link SemanticTextSplitter}。
     */
    public static final int DEFAULT_CHUNK_SIZE = 800;

    /**
     * 混合检索。
     *
     * @param enabled            关掉 = 只有向量那一路（上一版的行为）
     * @param candidatesPerRoute 每路各召回多少条，融合之后再交给重排
     */
    public record Hybrid(Boolean enabled, Integer candidatesPerRoute) {

        /**
         * 每路 15 条。
         *
         * <p>比最终 topK（5）大是必须的：融合与重排都只能从候选里挑，候选只有 5 条的话，
         * "两路都命中"这件事根本发生不了，名次永远是各路自己的前 5。15 是"足够让排名有区分度"
         * 与"不把整库倒出来"之间的取值。
         */
        public static final int DEFAULT_CANDIDATES_PER_ROUTE = 15;

        public Hybrid {
            enabled = enabled == null || enabled;
            candidatesPerRoute = (candidatesPerRoute == null || candidatesPerRoute <= 0)
                    ? DEFAULT_CANDIDATES_PER_ROUTE : candidatesPerRoute;
        }

        static Hybrid defaults() {
            return new Hybrid(null, null);
        }
    }

    /**
     * 重排。这是唯一一处直接调供应商 HTTP 的能力：框架没有 rerank 类型，见
     * {@code docs/design/knowledge-retrieval.md} 第六节。
     *
     * @param enabled    默认开。关掉只损失排序质量，不损失召回
     * @param candidates 送进重排的条数；小于 topK 时按 topK 用（截断之前不能比最终条数还少）
     * @param baseUrl    端点前缀。是 {@code compatible-api} 不是 {@code compatible-mode}，
     *                   与 chat / embedding 那两个不是同一个前缀，所以单独配一个而不是拼
     * @param model      rerank 模型名
     * @param timeout    超时。超时按"候选原样返回"处理，不抛
     */
    public record Rerank(Boolean enabled, Integer candidates, String baseUrl, String model, Duration timeout) {

        /** 通义的 rerank 兼容端点（与 chat 的 {@code /compatible-mode/v1} 不同前缀）。 */
        public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-api/v1";

        /**
         * 默认模型。
         *
         * <p>用 {@code qwen3-rerank} 而不是 {@code gte-rerank-v2}：后者将于 2026-05-30 下线，
         * 而前者单请求支持 500 条文档（这里最多送 10 条）。
         */
        public static final String DEFAULT_MODEL = "qwen3-rerank";

        /**
         * 2 秒。
         *
         * <p>重排挂在每轮对话的检索路径上，用户等的是首字延迟。2 秒还没回来就按原顺序走——
         * 排序差一点，比让用户多等几秒好。这个值不该等于"模型多久能算完"。
         */
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

        public static final int DEFAULT_CANDIDATES = 10;

        public Rerank {
            enabled = enabled == null || enabled;
            candidates = (candidates == null || candidates <= 0) ? DEFAULT_CANDIDATES : candidates;
            baseUrl = (baseUrl == null || baseUrl.isBlank())
                    ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl.trim());
            model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
            timeout = (timeout == null || timeout.isZero() || timeout.isNegative())
                    ? DEFAULT_TIMEOUT : timeout;
        }

        static Rerank defaults() {
            return new Rerank(null, null, null, null, null);
        }

        /** 端点拼接只该有一处：{@code baseUrl + "/reranks"} 写两次就会有一次是双斜杠。 */
        public String rerankUrl() {
            return baseUrl + "/reranks";
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    public RagProperties {
        directory = (directory == null || directory.isBlank()) ? null : directory.trim();
        topK = (topK == null || topK <= 0) ? DEFAULT_TOP_K : topK;
        minSimilarity = (minSimilarity == null || minSimilarity <= 0 || minSimilarity > 1)
                ? DEFAULT_MIN_SIMILARITY : minSimilarity;
        chunkSize = (chunkSize == null || chunkSize <= 0) ? DEFAULT_CHUNK_SIZE : chunkSize;
        hybrid = hybrid == null ? Hybrid.defaults() : hybrid;
        rerank = rerank == null ? Rerank.defaults() : rerank;
    }

    /** 开了就必须有目录：没有目录的知识库检索永远返回空，而"空"与"没有这段"分不开。 */
    public boolean hasDirectory() {
        return directory != null;
    }

    /**
     * 送进重排的条数：不能少于最终要返回的条数，否则"重排之后取 topK"会取到候选之外。
     * 两处配置各写一个数时，这种错只在结果只剩两三条时才看得出来。
     */
    public int rerankCandidates() {
        return Math.max(topK, rerank.candidates());
    }

    /** 全部走默认值，但 {@code directory} 为空——这时 {@code hasDirectory()} 为 false。 */
    public static RagProperties defaults() {
        return new RagProperties(null, null, null, null, null, null);
    }
}
