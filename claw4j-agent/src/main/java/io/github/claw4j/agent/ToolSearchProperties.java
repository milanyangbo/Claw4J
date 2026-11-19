package io.github.claw4j.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 工具检索：用哪种索引、一次给几个候选。
 *
 * <p>没有「要不要开检索」的开关，也没有「工具少于几个就全塞」的阈值：前者会让检索在某个环境里
 * 被悄悄关掉，后者会让同一句问话在工具数量跨过阈值时换一套规则，而这两件事都没有地方能看出来。
 * 循环只有一种，见 {@link ToolSearch#toolLoop}。
 *
 * @param index      用哪种索引，见 {@link Index}；不写取 {@link Index#REGEX}
 * @param maxResults 一次检索最多返回几个候选工具（框架约定 1–10，默认 5）
 * @param lucene     {@code index=lucene} 那一档的参数
 */
@ConfigurationProperties("claw4j.advisor.tool-search")
public record ToolSearchProperties(Index index, int maxResults, Lucene lucene) {

    /**
     * 索引的三种实现。库里有三个，我们按需接：选哪一个决定"检索靠字面还是靠语义"。
     */
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
         * 向量召回：按语义找工具，不要求字面重合。需要容器里有一个 {@code VectorStore}
         * （pgvector 的自动配置能提供，但它还要 {@code EmbeddingModel} 与一张向量表）。
         */
        VECTOR
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

    /** 框架在工具描述里写的是 1–10（Spring AI {@code ToolSearchTool}:50），这里提前夹住。 */
    private static final int FRAMEWORK_MAX_RESULTS_CAP = 10;

    /**
     * 三个缺省都在这里收口：配置绑定与"手工构造"两条路落到同一处。
     *
     * <p>用原始类型而不是包装类型管 {@code maxResults}：0 与「没配」本来就是同一件事。
     * {@code index} 与 {@code lucene} 用引用类型，因为"没配"要能区分出来。
     *
     * <p>标 {@link ConstructorBinding} 是因为这一个 record 有两个构造器：不标的话绑定层
     * 不知道走哪个，会退化到"找无参构造"，报 {@code No default constructor found}。
     */
    @ConstructorBinding
    public ToolSearchProperties {
        index = index == null ? Index.REGEX : index;
        maxResults = maxResults <= 0
                ? DEFAULT_MAX_RESULTS : Math.min(maxResults, FRAMEWORK_MAX_RESULTS_CAP);
        lucene = lucene == null ? new Lucene(null) : lucene;
    }

    /**
     * 只要一个候选数的那一版：索引取默认、lucene 那档也用默认。
     *
     * @param maxResults 一次返回几个候选；0 或负数取默认
     */
    public ToolSearchProperties(int maxResults) {
        this(null, maxResults, null);
    }

    /**
     * 全部走默认值。配置绑定在键缺失时传的也是 0 与 null，两条路落到同一处。
     *
     * @return 默认配置
     */
    public static ToolSearchProperties defaults() {
        return new ToolSearchProperties(0);
    }
}
