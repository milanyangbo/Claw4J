package io.github.claw4j.agent.toolaccess;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;
import java.util.Map;

/**
 * 工具面：露多少给模型（{@link Mode}），以及看不见的那部分怎么找回来（{@link Index}）。
 *
 * <p>这两件事必须分开配，因为它们是两个维度：露出决定"模型知不知道有这类能力"，索引决定
 * "知道有、但找不到"时能不能救回来。合成一个开关的写法，最后总是靠"给某类工具写特例"来补，
 * 而那正是本配置要消掉的东西——这里没有任何具体工具名。
 *
 * <p><b>2026-11-26 起与 ADR-0021 原条目相反</b>：原条目写"行为不随工具数量变化"，理由是
 * "不想让部署规模决定语义"，代价估的是"工具少也得多花一轮检索"。实测（票 05）这一轮不是花在
 * 边缘场景，而是花在最常见的一类问法上——{@code examples 目录下有哪些 md} 这种话，模型 5/5
 * 都不去检索，直接答"我没有能列目录的工具"。所以改成按规模滑档，见 {@link Mode#AUTO}。
 *
 * @param index       看不见的那部分用哪种索引找回来，见 {@link Index}；不写取 {@link Index#REGEX}
 * @param maxResults  一次检索最多返回几个候选工具（框架约定 1–10，默认 5）
 * @param lucene      {@code index=lucene} 那一档的参数
 * @param mode        工具面怎么露，见 {@link Mode}；不写取 {@link Mode#AUTO}
 * @param revealBelow {@code mode=auto} 的阈值：工具数不超过它就不检索；0 或负数取 10
 * @param pinned      常驻工具名：每轮都在清单里，不经过检索；写错的名字忽略并告警
 * @param categories  类别（namespace）→ 工具名；不配则按名字前缀推导，见 {@link ToolCategories}
 * @param hybrid      {@code index=hybrid} 那一档的参数
 */
@ConfigurationProperties("claw4j.advisor.tool-search")
public record ToolSearchProperties(Index index, int maxResults, Lucene lucene, Mode mode, int revealBelow,
                                   List<String> pinned, Map<String, List<String>> categories, Hybrid hybrid) {

    /**
     * 工具面怎么露给模型。
     *
     * <p>三档而不是一个布尔开关：检索这一层不是"开或关"，而是"什么时候值得为省 token 付一轮往返"。
     * 工具很少时那一轮纯属浪费，工具很多时不检索就超上下文——同一个部署在这两档之间会移动。
     */
    public enum Mode {

        /** 永不检索：全部工具每轮都在清单里，也不建索引。工具只有几个时这样最省。 */
        OFF,

        /** 一直检索：模型每轮只看得见检索工具 + 常驻 + 已检索到的。工具很多的部署用这一档。 */
        ALWAYS,

        /**
         * 按数量滑档（默认）：工具数（不含检索工具自己）不超过 {@code reveal-below} 就全塞，超过才检索。
         *
         * <p>为什么默认它：本仓当前 20–30 个工具，判下来仍是检索，行为与改动之前完全一致；
         * 而只挂三五个业务工具的部署自动变成全塞，不用改配置。
         */
        AUTO
    }

    /** {@code index=vector} 之外那三档见 {@link Index}。 */
    public enum Index {

        /**
         * 正则分词（默认）：{@link ChineseToolIndex} 在框架的 {@code RegexToolIndex} 上加了中文二元组
         * 与同义词表，整句不用空格也能命中。
         */
        REGEX,

        /**
         * Lucene 全文检索：打分与排序比正则正规，但框架把分析器写死成 {@code StandardAnalyzer}，
         * 中文按字切分——召回不一定比默认那档好。它不进提示词，只影响"搜到什么"。
         */
        LUCENE,

        /**
         * 向量召回：按语义找工具，不要求字面重合。
         *
         * <p>用的是<b>工具专用</b>的向量库（{@link ToolVectorStore}），不是容器里知识库那一个：
         * 共用一张表会让工具描述跟着 BM25 与向量两路被召回进业务问答。缺 {@code EmbeddingModel}
         * 或数据库时启动失败，不回落成"每次都搜不到"的空库。
         */
        VECTOR,

        /**
         * 关键字 ∪ 向量，按名次融合（{@link HybridToolIndex}）：两路各自召回，RRF 只吃名次。
         *
         * <p>为什么不是加权求和：两路的分值量纲不同（正则是命中次数，向量是余弦），求和要先归一化，
         * 而归一化系数只能靠调；名次不用调，也不需要两路可比。
         *
         * <p>它要的东西比 {@link #VECTOR} 只多一样——关键字那一路是纯本地计算，不花钱。
         */
        HYBRID;

        /**
         * 这一档要不要一个向量库。
         *
         * <p>问在这里而不是让装配去 {@code switch}：加档时改一处，漏改的地方会编译不过
         * （{@code switch} 表达式没有 default，少一个分支就是语法错误）。
         *
         * @return 要时为真
         */
        public boolean needsVectorStore() {
            return this == VECTOR || this == HYBRID;
        }
    }

    /**
     * {@code index=hybrid} 那一档的参数。其余三档读不到它。
     *
     * @param keywordWeight 关键字那一路占多少，见 {@link #DEFAULT_KEYWORD_WEIGHT}
     */
    public record Hybrid(Double keywordWeight) {

        /**
         * 缺省的权重：两路各一半。
         *
         * <p>不调成偏关键字（比如 0.7）：偏它的唯一理由是"关键字那一路更准"，而票 05 的证据是
         * 反过来——词面匹配在中文问法上大量空手。要偏等评测集建起来再说，那时这是第一个该调的旋钮。
         */
        public static final double DEFAULT_KEYWORD_WEIGHT = 0.5;

        /**
         * 越界夹到 {@code [0, 1]}，缺省取 {@value #DEFAULT_KEYWORD_WEIGHT}。
         *
         * <p>用包装类型而不是 {@code double}：0 是一个有意义的取值（纯向量），不能与"没配"共用
         * 同一个数——那正是 {@link Lucene#minScore} 用 {@code Float} 的同一条理由。
         */
        public Hybrid {
            keywordWeight = keywordWeight == null || keywordWeight.isNaN()
                    ? DEFAULT_KEYWORD_WEIGHT : Math.clamp(keywordWeight, 0.0, 1.0);
        }
    }

    /**
     * {@code index=lucene} 那一档的参数。其余两档读不到它。
     *
     * @param minScore 命中分下限（0–1）；低于它的候选不返回
     */
    public record Lucene(Float minScore) {

        /** 框架 {@code LuceneToolIndex} 无参构造用的就是它。 */
        public static final float DEFAULT_MIN_SCORE = 0.25f;

        /**
         * 缺省取 {@value #DEFAULT_MIN_SCORE}：与框架那一版一致，换档不换行为。
         */
        public Lucene {
            minScore = minScore == null ? DEFAULT_MIN_SCORE : minScore;
        }
    }

    /**
     * 一次检索返回几个候选。
     *
     * <p>取 5：模型一次只挑一两个来调，返回十个等于把省下来的 token 又还回去一部分。
     */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** {@code mode=auto} 的缺省阈值：Anthropic 的口径是少于约 10 个工具时全量比检索更快。 */
    public static final int DEFAULT_REVEAL_BELOW = 10;

    /** 框架在工具描述里写的是 1–10（Spring AI {@code ToolSearchTool}:50），这里提前夹住。 */
    private static final int FRAMEWORK_MAX_RESULTS_CAP = 10;

    /**
     * 缺省都在这里收口：配置绑定与"手工构造"两条路落到同一处。
     *
     * <p>用原始类型而不是包装类型管 {@code maxResults} 与 {@code revealBelow}：0 与"没配"本来就是
     * 同一件事。{@code index} / {@code lucene} / {@code mode} / 两个集合用引用类型，因为"没配"要能
     * 区分出来。
     *
     * <p>标 {@link ConstructorBinding} 是因为这一个 record 有多个构造器：不标的话绑定层不知道走哪个，
     * 会退化到"找无参构造"，报 {@code No default constructor found}。
     */
    @ConstructorBinding
    public ToolSearchProperties {
        index = index == null ? Index.REGEX : index;
        maxResults = maxResults <= 0
                ? DEFAULT_MAX_RESULTS : Math.min(maxResults, FRAMEWORK_MAX_RESULTS_CAP);
        lucene = lucene == null ? new Lucene(null) : lucene;
        mode = mode == null ? Mode.AUTO : mode;
        revealBelow = revealBelow <= 0 ? DEFAULT_REVEAL_BELOW : revealBelow;
        pinned = pinned == null ? List.of() : List.copyOf(pinned);
        categories = categories == null ? Map.of() : Map.copyOf(categories);
        hybrid = hybrid == null ? new Hybrid(null) : hybrid;
    }

    /**
     * 只要一个候选数的那一版：其余全取默认。
     *
     * @param maxResults 一次返回几个候选；0 或负数取默认
     */
    public ToolSearchProperties(int maxResults) {
        this(null, maxResults, null, null, 0, null, null, null);
    }

    /**
     * 指定索引与候选数的那一版：露出策略取默认。
     *
     * <p>留着是因为"换索引"与"露出多少"是两件事，改一件不该逼调用方填另一件的四个参数。
     *
     * @param index      索引
     * @param maxResults 一次返回几个候选
     * @param lucene     lucene 那档的参数
     */
    public ToolSearchProperties(Index index, int maxResults, Lucene lucene) {
        this(index, maxResults, lucene, null, 0, null, null, null);
    }

    /**
     * 全部走默认值。配置绑定在键缺失时传的也是 0 与 null，两条路落到同一处。
     *
     * @return 默认配置
     */
    public static ToolSearchProperties defaults() {
        return new ToolSearchProperties(0);
    }

    /**
     * 这一轮的形状：全塞还是检索。
     *
     * @param toolCount 这一轮请求里的工具数（不含检索工具自己）
     * @return 是不是全塞
     */
    public boolean revealsAll(int toolCount) {
        return switch (mode) {
            case OFF -> true;
            case ALWAYS -> false;
            case AUTO -> toolCount <= revealBelow;
        };
    }
}
