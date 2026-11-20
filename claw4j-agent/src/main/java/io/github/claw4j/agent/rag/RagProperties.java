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
 * @param parent        章节父块回填（small-to-big）。默认关，见 {@link Parent}
 * @param rewrite       检索前改写。默认关，见 {@link Rewrite}
 */
@ConfigurationProperties("claw4j.rag")
public record RagProperties(String directory, Integer topK, Double minSimilarity, Integer chunkSize,
                            Hybrid hybrid, Rerank rerank, Parent parent, Rewrite rewrite) {

    /**
     * 一次检索最终注入多少段，默认 10。
     *
     * <p>行业给的上下文宽度普遍是 10–20：Anthropic 的 Contextual Retrieval 实验里 top-20 的
     * 失败率明显低于 top-10 与 top-5，多数 RAG 实践也落在这一段。5 是偏紧的一端，
     * 而本仓库的实测显示 k 从 5 加到 8 分数不动——说明多给几段不会掉分，只是当时池子里没有更多可给。
     *
     * <p><b>改这个数会换尺子</b>：Recall@k 随 k 单调不减，所以 k=5 的分数与 k=10 的分数不能直接比。
     * 评测侧（{@code eval/rag}）的 main cutoff 取的是候选 k 里最大的那个，与这里的取值对齐。
     */
    public static final int DEFAULT_TOP_K = 10;

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
         * 每路 25 条。
         *
         * <p>这个数是量出来的，不是按比例拍的：融合会按 id 去重，两路有大量重叠，所以
         * <b>融合池约等于每路条数</b>，不是两路之和。实测（68 条评测集）：
         *
         * <pre>
         * 每路 25 → 融合后平均 24.6 条（关重排时量的，那才是完整池子）
         * 每路 15 → 融合后平均 11.9 条，最大 15
         * </pre>
         *
         * <p>要重排看前 20 条，池子就必须 ≥ 20：每路 15 时池子只有 12 条，重排等于在 12 条里
         * 排 10 条，筛选空间几乎为零——重排候选配成 20 也形同虚设。
         *
         * <p>为什么不加到 30：这一层几乎不花钱（一次 SQL + 一次向量近邻，都在本地），但多了
         * 没有用——重排只收 20 条，多出来的永远进不了重排。真正按条数计费的是重排。
         */
        public static final int DEFAULT_CANDIDATES_PER_ROUTE = 25;

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

        /**
         * 默认 20 条送进重排。
         *
         * <p>必须大于 {@code top-k}：{@link RagProperties#rerankCandidates()} 取两者的最大值，若两者
         * 相等，重排的输入条数就等于最终条数，它从"筛选 + 排序"退化成"只排序"——能选的范围消失了，
         * 而这件事在结果上看不出来（分数照样是一个分数）。
         */
        public static final int DEFAULT_CANDIDATES = 20;

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
        parent = parent == null ? Parent.defaults() : parent;
        rewrite = rewrite == null ? Rewrite.defaults() : rewrite;
    }

    /** 开了就必须有目录：没有目录的知识库检索永远返回空，而"空"与"没有这段"分不开。 */
    public boolean hasDirectory() {
        return directory != null;
    }

    /**
     * 章节父块回填（small-to-big）。
     *
     * <p>小块召回准、上下文碎：命中 350 字的一段，适用范围与例外都落在兄弟块里。打开后，
     * 命中的子块会被"同一文档同一章节的全部块"拼成的父块替代（见 {@code SectionExpander}）。
     *
     * <p><b>默认关</b>：它用 token 换上下文完整度，收益要靠对照实验证明，不能因为"业界这么做"
     * 就默认开。开着的时候上下文会变长（段数变少、每段更长），而模型看到的段数变少这件事
     * 与 top-k 是两个方向，必须分开量。
     *
     * @param enabled  是否回填
     * @param maxChars 父块最多多少字符；超过就不聚合，保留原子块——宁可不合并，也不把一章几千字
     *                 塞进提示词
     */
    public record Parent(Boolean enabled, Integer maxChars) {

        /** 2400 字符约等于 3 块（默认块长 800 token 上下），再大就该考虑摘要而不是拼接。 */
        public static final int DEFAULT_MAX_CHARS = 2400;

        public Parent {
            enabled = enabled != null && enabled;
            maxChars = (maxChars == null || maxChars <= 0) ? DEFAULT_MAX_CHARS : maxChars;
        }

        static Parent defaults() {
            return new Parent(null, null);
        }
    }

    /**
     * 检索前改写（{@link GuardedQueryRewriter} + 框架的 {@code RewriteQueryTransformer}）。
     *
     * <p>默认关：它每轮多一次模型调用（延迟 + 成本），而收益只在"问题用词与文档完全不重叠"时
     * 出现。
     *
     * <p><b>两个正则都由部署方给，代码里没有词表</b>：什么算"数值"、什么算"必须原样保留的标识"，
     * 在售后合同、医疗指南、英文手册里是三套答案。{@code application.yml} 里那份是中文业务文档的
     * 取值，换领域改那两行即可。空 = 不做那一道检查。
     *
     * @param enabled     是否改写
     * @param timeout     等多久；超时按原问题检索
     * @param target      拼进提示词的目标检索系统描述，如「知识库」「产品手册」
     * @param prompt      自定义提示词；null 用内置中文模板，必须含 {@code {query}}
     * @param skipPattern 命中就跳过改写的正则；null = 从不跳过
     * @param keepPattern 改写结果必须原样保留的片段正则；null = 不做保留校验
     */
    public record Rewrite(Boolean enabled, Duration timeout, String target, String prompt,
                          String skipPattern, String keepPattern) {

        /**
         * 3 秒。
         *
         * <p>改写挂在检索之前，用户等的是首字，所以不能等太久；但 1.5 秒对 deepseek-chat 这种
         * 一次几百 token 的同步调用偏紧，实测命中率会掉一半——那时改写等于随机生效，
         * 归因会更难。3 秒是"能等到"与"不算太慢"之间的取值。
         */
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

        /** 提示词里 {target} 的默认取值。它只影响改写方向，不影响检索。 */
        public static final String DEFAULT_TARGET = "知识库";

        public Rewrite {
            enabled = enabled != null && enabled;
            timeout = (timeout == null || timeout.isZero() || timeout.isNegative())
                    ? DEFAULT_TIMEOUT : timeout;
            target = (target == null || target.isBlank()) ? DEFAULT_TARGET : target.trim();
            prompt = (prompt == null || prompt.isBlank()) ? null : prompt;
        }

        static Rewrite defaults() {
            return new Rewrite(null, null, null, null, null, null);
        }
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
        return new RagProperties(null, null, null, null, null, null, null, null);
    }
}
