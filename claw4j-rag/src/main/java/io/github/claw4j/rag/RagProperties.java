package io.github.claw4j.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

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
 * @param keyword       全文那一路的引擎与词项规则（引擎二选一，规则只对 substring 生效），见 {@link Keyword}
 * @param rerank        重排。关掉只影响顺序，不影响召回
 * @param parent        章节父块回填（small-to-big）。默认关，见 {@link Parent}
 * @param rewrite       检索前改写。默认关，见 {@link Rewrite}
 * @param answer        回答的范围约束。默认开，见 {@link Answer}
 */
@ConfigurationProperties("claw4j.rag")
public record RagProperties(String directory, Integer topK, Double minSimilarity, Integer chunkSize,
                            Hybrid hybrid, Keyword keyword, Rerank rerank, Parent parent, Rewrite rewrite,
                            Answer answer) {

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
     * 全文那一路的词项规则：一条查询怎么被拆成"拿去 {@code ilike} 的子串"。
     *
     * <p>这些值**全都带领域假设**，所以走配置而不是写死在代码里：
     *
     * <ul>
     *   <li>锚点要求 ≥3 字符——中文合同里的型号常常是两位（{@code A1}、{@code A2}），
     *       英文手册里的版本号又常常带点（{@code v1.2}），同一个长度门槛不可能两边都对；
     *   <li>整句只在 12 个字符内才当词项——12 个汉字是一句话，12 个英文字符只有一两个词；
     *   <li>权重 3 对 1 是"字面精确 vs 覆盖度"的比值，换语言与语料就该重标。
     * </ul>
     *
     * <p>默认值就是配置化之前写死在 {@link KeywordDocumentRetriever} 里的那套，行为不变。
     *
     * @param anchorPattern     长锚点：以字母或数字开头、≥3 字符的连续 ASCII 片段
     * @param shortModelPattern 短型号：一个字母 + 1~2 位数字，两侧不接字母数字
     * @param cjkPattern        bigram 的切分范围：只在这些串内部两两切，不跨标点
     * @param maxAnchors        一条查询最多几个锚点（长短锚点共用这个名额）
     * @param maxTerms          一条查询最多几个词项；SQL 里每个词项是一个 {@code or} 加一个 {@code case}
     * @param wholeMaxChars     整句不超过多少字符才当词项
     * @param wholeWeight       整句与锚点的权重（锚点复用这个值）
     * @param bigramWeight      中文 bigram 的权重
     */
    public record Keyword(String engine, String anchorPattern, String shortModelPattern, String cjkPattern,
                          Integer maxAnchors, Integer maxTerms, Integer wholeMaxChars,
                          Integer wholeWeight, Integer bigramWeight) {

        /**
         * 子串匹配引擎：{@code content ilike '%词项%'}，自己拆词项。
         *
         * <p>任何 PG 都能跑，不要求额外扩展。代价写在 {@link KeywordDocumentRetriever} 的类注释里：
         * 它没有分词，所以"什么算词"只能靠下面这些形状规则去猜。
         */
        public static final String ENGINE_SUBSTRING = "substring";

        /**
         * BM25 引擎：ParadeDB {@code pg_search} + jieba 分词 + IDF。
         *
         * <p>要求数据库换成 ParadeDB 镜像。换过去之后<b>下面八项全部不生效</b>——分词、
         * IDF、长度归一都在引擎里，那些规则存在的理由（猜词）消失了。这不是"两种实现细节不同"，
         * 是两种能力：{@code A1}/{@code A2} 这类两字符型号在前者需要专门加一条形状规则，
         * 在后者是默认行为。
         */
        public static final String ENGINE_BM25 = "bm25";

        /** 长锚点。下限 3 是躲开 {@code a}、{@code 1} 这类噪声。 */
        public static final String DEFAULT_ANCHOR_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{2,}";

        /**
         * 短型号。默认认"一个字母 + 1~2 位数字"，两侧不接字母数字。
         *
         * <p>为什么单独一条：长锚点要求 ≥3 字符，而产品型号恰好两位。漏掉 {@code A2}，
         * 「A2 买一年延保多少钱」里就只剩"延保""一年""多少"这些泛词——它们指向延保政策，
         * 指不到整机价格表，而价格表才是这道题的另一半证据。
         *
         * <p>两侧不接字母数字，是为了不从 {@code SKU2024A2} 里切出 {@code 4A} 这种假锚点。
         *
         * <p>它是**按长相认**，不是按词表认：任何"字母+数字"的编号都走这一条，代码里没有
         * 型号清单。换领域只需改正则（比如 {@code AB-12} 要加连字符、中文型号要另一条）。
         */
        public static final String DEFAULT_SHORT_MODEL_PATTERN =
                "(?<![A-Za-z0-9])[A-Za-z]\\d{1,2}(?![A-Za-z0-9])";

        /** 连续汉字（含日文假名）。bigram 只在这类串内部切，跨标点拼出的组合文档里不存在。 */
        public static final String DEFAULT_CJK_PATTERN = "[\\u4e00-\\u9fff\\u3040-\\u30ff]+";

        /** 一条查询最多几个锚点。再多就是整句被切碎了：命中率上升，精度下降。 */
        public static final int DEFAULT_MAX_ANCHORS = 4;

        /** 一条查询最多几个词项。每个词项在 SQL 里是两个占位符，不封顶会拼出上千字符的语句。 */
        public static final int DEFAULT_MAX_TERMS = 12;

        /** 整句只在不超过这么多字符时当词项：长句加进去只是一条必然不命中的 {@code or}。 */
        public static final int DEFAULT_WHOLE_MAX_CHARS = 12;

        /** 整句与锚点的权重：命中一次就足以把它和"只沾了个虚词"的块分开。 */
        public static final int DEFAULT_WHOLE_WEIGHT = 3;

        /** bigram 的权重：它是覆盖度信号，单独命中一个不足以说明相关。 */
        public static final int DEFAULT_BIGRAM_WEIGHT = 1;

        public Keyword {
            engine = (engine == null || engine.isBlank())
                    ? ENGINE_SUBSTRING : engine.strip().toLowerCase(Locale.ROOT);
            // 拼错不回落：静默回落的表现是"改了配置没反应"，而这里两条路的代价差一个镜像。
            // 抛在绑定期，启动就看得见；换成运行期才发现，人只会以为"BM25 没效果"。
            if (!ENGINE_SUBSTRING.equals(engine) && !ENGINE_BM25.equals(engine)) {
                throw new IllegalArgumentException("claw4j.rag.keyword.engine 只能是 "
                        + ENGINE_SUBSTRING + " 或 " + ENGINE_BM25 + "，收到：" + engine);
            }
            anchorPattern = (anchorPattern == null || anchorPattern.isBlank())
                    ? DEFAULT_ANCHOR_PATTERN : anchorPattern.strip();
            shortModelPattern = (shortModelPattern == null || shortModelPattern.isBlank())
                    ? DEFAULT_SHORT_MODEL_PATTERN : shortModelPattern.strip();
            cjkPattern = (cjkPattern == null || cjkPattern.isBlank())
                    ? DEFAULT_CJK_PATTERN : cjkPattern.strip();
            maxAnchors = (maxAnchors == null || maxAnchors <= 0) ? DEFAULT_MAX_ANCHORS : maxAnchors;
            maxTerms = (maxTerms == null || maxTerms <= 0) ? DEFAULT_MAX_TERMS : maxTerms;
            wholeMaxChars = (wholeMaxChars == null || wholeMaxChars <= 0)
                    ? DEFAULT_WHOLE_MAX_CHARS : wholeMaxChars;
            wholeWeight = (wholeWeight == null || wholeWeight <= 0) ? DEFAULT_WHOLE_WEIGHT : wholeWeight;
            bigramWeight = (bigramWeight == null || bigramWeight <= 0)
                    ? DEFAULT_BIGRAM_WEIGHT : bigramWeight;
        }

        static Keyword defaults() {
            return new Keyword(null, null, null, null, null, null, null, null, null);
        }

        /**
         * 是否走 BM25 引擎。
         *
         * <p>只在这一处判断：装配选实现、启动任务决定建哪种索引，两处都读它。散开写
         * {@code ENGINE_BM25.equals(...)} 的后果是"装配走了 BM25、索引却按 substring 建"——
         * 表现是启动不报错、每次检索都失败。
         *
         * @return 配的是 BM25 时为 true
         */
        public boolean isBm25() {
            return ENGINE_BM25.equals(engine);
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
    public record Rerank(Boolean enabled, Integer candidates, String baseUrl, String model, Duration timeout,
                         String apiKey) {

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
         * 默认复用 qwen 那一家的句柄。
         *
         * <p>重排端点（{@code dashscope.aliyuncs.com}）与 qwen 的 chat 端点同一套凭据，所以默认指向
         * 同一个 secret。写成独立配置而不是去读 {@code claw4j.model.providers}：本模块不依赖
         * agent 的模型路由表，反过来 agent 也不该为了重排多知道一份东西。
         */
        public static final String DEFAULT_API_KEY = "secret:qwen_api_key";

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
            apiKey = (apiKey == null || apiKey.isBlank()) ? DEFAULT_API_KEY : apiKey.trim();
        }

        static Rerank defaults() {
            return new Rerank(null, null, null, null, null, null);
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
        keyword = keyword == null ? Keyword.defaults() : keyword;
        rerank = rerank == null ? Rerank.defaults() : rerank;
        parent = parent == null ? Parent.defaults() : parent;
        rewrite = rewrite == null ? Rewrite.defaults() : rewrite;
        answer = answer == null ? Answer.defaults() : answer;
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
     * 回答的范围约束：把检索到的资料交给模型时，限定"只答被问到的部分"。
     *
     * <p>问题不在"资料用不用"，在"用的时候用多少"：检索回来的块是 350 字，答案常常只有一句，
     * 块一旦被判为相关，模型就有权把整块搬进回答——问"退货几天"，运费谁承担、生鲜例外
     * 也一并带上。行业里这一步通常靠长度约束代理（LangChain 的 rag-prompt 写的是
     * "three sentences maximum"），本模块不用句数：分级账期、例外清单这类合法答案会被截断。
     *
     * <p><b>默认开</b>，因为关掉之后的行为是旧的"多条信息请整合"——那等于鼓励把多段都写进回答。
     *
     * @param enabled      是否用新的范围约束。关 = 回到旧三条，用于对照实验
     * @param instructions 自定义"用法"整段文本；null 用 {@code RagAutoConfiguration} 里的内置措辞。
     *                     <b>不能含花括号</b>（&#123; / &#125;）：那是模板占位符，混进来会让
     *                     {@code {query}} 与 {@code {context}} 的渲染错位，所以启动时就拦
     */
    public record Answer(Boolean enabled, String instructions) {

        public Answer {
            enabled = enabled == null || enabled;
            instructions = (instructions == null || instructions.isBlank()) ? null : instructions.strip();
        }

        static Answer defaults() {
            return new Answer(null, null);
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
        return new RagProperties(null, null, null, null, null, null, null, null, null, null);
    }
}
