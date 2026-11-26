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
 * @param index       看不见的那部分用哪种索引找回来，见 {@link Index}；不写取 {@link Index#LUCENE}<br>
 *                    <b>库的默认与应用的不同</b>：库不替调用方决定依赖向量库，所以留 {@code lucene}；
 *                    {@code claw4j-app} 的 {@code application.yml} 配的是 {@code hybrid} +
 *                    {@code keyword-weight: 0.5}（2026-11-26 实测 R@5 0.880，唯一过线的一档）。
 * @param maxResults  一次检索最多返回几个候选工具（框架约定 1–10，默认 5）
 * @param lucene      {@code index=lucene} 那一档的参数
 * @param mode        工具面怎么露，见 {@link Mode}；不写取 {@link Mode#AUTO}
 * @param revealBelow {@code mode=auto} 的阈值：工具数不超过它就不检索；0 或负数取 10
 * @param pinned      常驻工具名：每轮都在清单里，不经过检索；写错的名字忽略并告警
 * @param categories  类别（namespace）→ 工具名；不配则按名字前缀推导，见 {@link ToolCategories}
 * @param hybrid      {@code index=hybrid} 那一档的参数
 * @param zhDescription 工具描述要不要在运行时译成中文，见 {@link ZhDescription}
 * @param rules       人写的正则规则，命中就把点名的工具置顶，见 {@link Rule}；默认空
 */
@ConfigurationProperties("claw4j.advisor.tool-search")
public record ToolSearchProperties(Index index, int maxResults, Lucene lucene, Mode mode, int revealBelow,
                                   List<String> pinned, Map<String, List<String>> categories, Hybrid hybrid,
                                   ZhDescription zhDescription, List<Rule> rules) {

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

    /**
     * 看不见的那部分用哪种索引找回来，两档。
     *
     * <p>2026-11-26 收成两档（票 21）：{@code regex}（自研中文二元组）与 {@code vector}（纯向量）
     * 都删了。前者在实测里是最低的 0.360，而它的独特价值——"人写正则指定工具"——已经由
     * {@link #rules()} 承接（规则是断言，比"扩词再排序"更直接）；后者与
     * {@code hybrid} + {@code keyword-weight: 0} 功能等价（实测同为 0.820），
     * 留两档入口让"纯向量"这件事少一个开关，也就少一处配置错误。
     */
    public enum Index {

        /**
         * Lucene 全文检索：TF-IDF 打分，中文按字切分。
         *
         * <p>纯本地计算、不要向量库，是<b>库的默认档</b>：它让"不配任何东西"也有 0.820 的 R@5
         * （2026-11-26，60 题）。它读的是译文（{@link ToolSearch#index} 里包了翻译层），
         * 否则中文问法只能撞上中文描述的那几个干扰项——那一版 base 档是 0.000。
         *
         * <p>它不进提示词，只影响"搜到什么"。
         */
        LUCENE,

        /**
         * 关键字 ∪ 向量，按名次融合（{@link HybridToolIndex}）：两路各自召回，RRF 只吃名次。
         *
         * <p>为什么不是加权求和：两路的分值量纲不同（TF-IDF 是文档分，向量是余弦），求和要先归一化，
         * 而归一化系数只能靠调；名次不用调，也不需要两路可比。
         *
         * <p>实测 0.880（{@code keyword-weight: 0.5}），是唯一过质量线的那一档，所以
         * {@code claw4j-app} 用它当默认。它要第二份依赖：<b>工具专用</b>的向量库
         * （{@link ToolVectorStore}），缺 {@code EmbeddingModel} 或数据库时启动失败。
         *
         * <p>纯向量就是它 + {@code keyword-weight: 0}（权重为 0 的那一路整个不查）。
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
            return this == HYBRID;
        }
    }

    /**
     * {@code index=hybrid} 那一档的参数。{@code lucene} 读不到它。
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
     * 工具描述本地化的开关（票 11）。
     *
     * <p>为什么需要它：框架自带的工具描述大多是英文，而用户说的是中文。常驻（{@link #pinned}）解决了
     * 基座那几个"根本找不到"，但长尾（MCP、技能、子 Agent、业务工具）还在检索里，词面撞不上就是撞不上。
     *
     * <p>为什么是运行时翻而不是人写映射表：{@code eval/tools/retrieval/gold.yaml} 里那一档「中文问英文描述」
     * 的分数（票 09 基线：regex R@1 0.20、纯向量 0.60）说明了差多少；而人写的表跟着工具描述漂，
     * 漂了还看不出来。
     *
     * <p><b>两档都喂译文</b>（票 21 起）：向量那一路靠它跨语言，Lucene 那一路靠它把中文 query 从
     * "一个词都不重合"救回来（base 档 0.000 → 0.900）。2026-11-26 之前在自研二元组那一档上是反的
     * ——描述变长、二元组碰撞变多，喂译文从 0.15 掉到 0.10；那一档已随 {@code index=regex} 退役。
     *
     * @param enabled 要不要翻。默认关：它要在第一次拿到这份清单时花掉若干次 utility 调用
     * @param tools   只翻这些工具（名字来自 {@code @Tool(name=…)}）。空 = 自动判定：描述里汉字少于
     *                {@value ToolDescriptionTranslator#MIN_CJK_CHARS} 个才翻。名字写错不报错也不翻
     */
    public record ZhDescription(boolean enabled, List<String> tools) {

        public ZhDescription {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /**
     * 一条"这句话就该用这个工具"的断言（票 21 第 2 批）。
     *
     * <p>为什么要有它：相似度解决不了确定性。{@code 这个月报销多少} 与"导出财务报表"任何一档都算不出
     * 高分，但业务上答案就是那一个工具；这类对应关系由人写下来比让模型猜可靠，出问题时也能指到一行配置。
     *
     * <p>为什么用正则而不是同义词表：同义词表只能"扩词"，扩出来的词还要再参与相似度排序，命中与否
     * 取决于别的候选；规则是"点名"——命中即置顶，与其余候选无关。正则引擎与框架
     * {@code RegexToolIndex} 是同一个（{@link java.util.regex.Pattern}），不用另学一套语法。
     *
     * <p>代价要认：规则不会自己跟着工具描述漂。工具改名、下线，规则就静默不命中，所以写错的工具名
     * 会打一行 WARN，规则本身也要有对应的用例兜着。
     *
     * @param pattern 匹配整句 query 的正则（{@code find}，不是整句相等），如 {@code 报销|发票|开票}
     * @param tools   命中后要置顶的工具名（{@code @Tool(name=…)} 的值），按此顺序排在最前
     */
    public record Rule(String pattern, List<String> tools) {

        /**
         * 缺一个就抛：一条没有 pattern 或没有 tools 的规则是配置错误，静默忽略会让"我明明配了"查不出来。
         *
         * <p>抛在这里而不是等第一次检索：配置错要在启动时红，而不是在上线后表现为"这条规则好像没生效"。
         */
        public Rule {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("claw4j.advisor.tool-search.rules 有一条没写 pattern");
            }
            if (tools == null || tools.isEmpty()) {
                throw new IllegalArgumentException("claw4j.advisor.tool-search.rules 的 " + pattern
                        + " 没写 tools：规则命中后不知道要给哪个工具");
            }
            tools = List.copyOf(tools);
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
        index = index == null ? Index.LUCENE : index;
        maxResults = maxResults <= 0
                ? DEFAULT_MAX_RESULTS : Math.min(maxResults, FRAMEWORK_MAX_RESULTS_CAP);
        lucene = lucene == null ? new Lucene(null) : lucene;
        mode = mode == null ? Mode.AUTO : mode;
        revealBelow = revealBelow <= 0 ? DEFAULT_REVEAL_BELOW : revealBelow;
        pinned = pinned == null ? List.of() : List.copyOf(pinned);
        categories = categories == null ? Map.of() : Map.copyOf(categories);
        hybrid = hybrid == null ? new Hybrid(null) : hybrid;
        zhDescription = zhDescription == null ? new ZhDescription(false, List.of()) : zhDescription;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * 只要一个候选数的那一版：其余全取默认。
     *
     * @param maxResults 一次返回几个候选；0 或负数取默认
     */
    public ToolSearchProperties(int maxResults) {
        this(null, maxResults, null, null, 0, null, null, null, null, null);
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
        this(index, maxResults, lucene, null, 0, null, null, null, null, null);
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
