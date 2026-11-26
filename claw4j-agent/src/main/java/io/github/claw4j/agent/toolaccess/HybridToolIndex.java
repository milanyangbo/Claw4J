package io.github.claw4j.agent.toolaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 关键字 ∪ 向量，按名次融合（票 07）。
 *
 * <p>为什么要有它：{@link ChineseToolIndex} 是词面匹配，中文问法对框架自带的英文描述命中数为 0。
 * 票 04 靠一张手写同义词表给网页那族搭了一座桥，桥是逐个搭的、不通用；描述的措辞一改就静默失效。
 * 向量那一路按语义找，正好补这个洞，代价是它也会把"意思相近但不对"的工具带进来。
 *
 * <p><b>RRF 而不是加权求和</b>：两路的分值量纲不同（正则是命中次数，向量是余弦相似度），
 * 求和要先归一化，而归一化系数只能靠调、而且每次换模型都要重调。名次不需要可比：
 * {@code score = w / (k + rank_kw) + (1 - w) / (k + rank_vec)}，{@code k} 取 60（Cormack 的常用值）。
 * 代价是两路都没进前列的候选一分不得——工具只有 5–20 个、每路先取 3 倍，够用。
 *
 * <p><b>分组在两翼之内</b>（见 {@link #of}）：包在融合外面的话，排在前面的那一组会先把名额占满，
 * 收窄之后反而"更少了"。框架的 {@code categoryFilter} 只有 {@link NamespacedToolIndex} 读，
 * 所以那层必须贴在每个检索实现上。
 *
 * <p>不做 rerank：候选只有 5–20 个，rerank 要一次模型调用，它的收益来自"几十段文档里的长噪声"，
 * 工具这边没有那个量级。
 *
 * <p>两路串行查。向量那一路是一次库查询，关键字那一路是一次本地正则——并行省下的不到一毫秒，
 * 换来一个线程池和一条要解释的失败路径。
 */
public class HybridToolIndex implements ToolIndex {

    /**
     * 检索回来的名单只在这里能看见。
     *
     * <p>指标只记个数（票 08）："回来 5 个"与"回来哪 5 个"是两件事，而后者才是排障时要看的——
     * 少了哪个工具、是不是被关键字那一路的噪声挤掉的，都得看名字。query 是模型每轮现写的，
     * 只进 DEBUG 日志，不进指标标签。
     */
    private static final Logger log = LoggerFactory.getLogger(HybridToolIndex.class);

    /**
     * RRF 的平滑常数：{@code k} 越大，名次之间的差距压得越平。
     *
     * <p>60 是 Cormack 那篇里的经验值，后来成了默认。它的作用是不让"第一名"通吃：名次 1 与 2 的
     * 分数差从 1/2 缩到 1/61，融合时两路的第一名才有机会并列、第二三名才追得上来。
     */
    static final int RRF_K = 60;

    /** 每路先多取几倍，融合才有腾挪空间——只取 N 的话，另一路的第一名根本没进来。 */
    private static final int WIDEN = 3;

    private final ToolIndex keyword;

    private final ToolIndex vector;

    private final double keywordWeight;

    /**
     * 只能用 {@link #of} 建：两翼必须已经被分组包过。
     *
     * <p>构造器留成包内可见是为了让"分组在融合里面"这件事无法被绕过——直接 new 出来的实例
     * 收窄时会先被别组顶掉名额，而那正是本类最容易写反的地方。
     *
     * @param keyword 关键字那一路，已分组
     * @param vector   向量那一路，已分组
     * @param keywordWeight 关键字占多少，0–1
     */
    HybridToolIndex(ToolIndex keyword, ToolIndex vector, double keywordWeight) {
        this.keyword = Objects.requireNonNull(keyword, "keyword");
        this.vector = Objects.requireNonNull(vector, "vector");
        this.keywordWeight = Math.clamp(keywordWeight, 0.0, 1.0);
    }

    /**
     * 合成一档 hybrid：两翼各自包上分组，融合在最外面。
     *
     * <p>这是唯一的入口，因为"谁包在里面"直接决定收窄对不对，不该留给调用方摆。
     *
     * @param categories    分组规则
     * @param keyword       关键字索引（未分组）
     * @param vector        向量索引（未分组）
     * @param keywordWeight 关键字占多少；0 = 纯向量，1 = 纯关键字（= 现状，用于对照）
     * @return 融合后的索引
     */
    public static HybridToolIndex of(ToolCategories categories, ToolIndex keyword, ToolIndex vector,
                                     double keywordWeight) {
        Objects.requireNonNull(categories, "categories");
        return new HybridToolIndex(new NamespacedToolIndex(keyword, categories),
                new NamespacedToolIndex(vector, categories), keywordWeight);
    }

    @Override
    public void indexTool(String sessionId, ToolReference toolReference) {
        this.keyword.indexTool(sessionId, toolReference);
        this.vector.indexTool(sessionId, toolReference);
    }

    @Override
    public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        this.keyword.indexTools(sessionId, toolReferences);
        this.vector.indexTools(sessionId, toolReferences);
    }

    @Override
    public void clearIndex(String sessionId) {
        this.keyword.clearIndex(sessionId);
        this.vector.clearIndex(sessionId);
    }

    @Override
    public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
        int requested = toolSearchRequest.maxResults() == null
                ? ToolSearchProperties.DEFAULT_MAX_RESULTS : toolSearchRequest.maxResults();
        ToolSearchRequest widened = new ToolSearchRequest(toolSearchRequest.sessionId(),
                toolSearchRequest.query(), requested * WIDEN, toolSearchRequest.categoryFilter());

        Map<String, Fused> fused = new LinkedHashMap<>();
        // 权重为 0 的那一路整个不查：查了再乘 0，候选仍会以 0 分进结果，权重 1 就不等于"纯关键字"了。
        ToolSearchResponse keywordHits = this.keywordWeight > 0 ? this.keyword.search(widened) : null;
        ToolSearchResponse vectorHits = this.keywordWeight < 1 ? this.vector.search(widened) : null;
        if (keywordHits != null) {
            accumulate(fused, keywordHits, this.keywordWeight);
        }
        if (vectorHits != null) {
            accumulate(fused, vectorHits, 1 - this.keywordWeight);
        }

        // 稳定排序：同分时保持"先出现者在前"，即关键字那一路先到。写成 comparator 里带 toolName
        // 也行，但那会让结果依赖名字的字典序——同一个工具改个名就换位置，排障时像是融合变了。
        List<Fused> ranked = new ArrayList<>(fused.values());
        ranked.sort(Comparator.comparingDouble(Fused::score).reversed());
        List<ToolReference> references = ranked.stream().limit(requested).map(Fused::reference).toList();
        if (log.isDebugEnabled()) {
            log.debug("工具检索 hybrid：query={} 关键字={} 向量={} 融合后={}", toolSearchRequest.query(),
                    namesOf(keywordHits), namesOf(vectorHits),
                    references.stream().map(ToolReference::toolName).toList());
        }

        return ToolSearchResponse.builder()
                .toolReferences(references)
                .totalMatches(references.size())
                .searchMetadata(ToolSearchResponse.SearchMetadata.builder()
                        .searchType(getClass().getSimpleName())
                        .query(toolSearchRequest.query())
                        .build())
                .build();
    }

    /**
     * 里面真正检索的两档。
     *
     * <p>给启动日志与测试用：装配摸得着的是融合这一层，而"两翼各是什么、有没有带分组"只在这里。
     *
     * @return 关键字那一路（已分组）
     */
    public ToolIndex keywordWing() {
        return this.keyword;
    }

    /**
     * @return 向量那一路（已分组）
     */
    public ToolIndex vectorWing() {
        return this.vector;
    }

    /**
     * 把一路的结果按名次折算成分数累加进去。
     *
     * <p>同一路里重名的只算第一次出现：框架的候选本来就按工具去重，但两路之间会撞，
     * 撞上的要累加而不是覆盖——"两路都排前面"正是它该排第一的原因。
     */
    private static void accumulate(Map<String, Fused> fused, ToolSearchResponse response, double weight) {
        if (response == null || response.toolReferences() == null) {
            return;
        }
        List<ToolReference> hits = response.toolReferences();
        for (int i = 0; i < hits.size(); i++) {
            ToolReference reference = hits.get(i);
            if (reference == null || reference.toolName() == null || reference.toolName().isBlank()) {
                continue;
            }
            double score = weight / (RRF_K + i + 1.0);
            fused.compute(reference.toolName(), (name, existing) -> existing == null
                    ? new Fused(reference, score) : existing.plus(score));
        }
    }

    /** 一路候选的工具名，只给日志用；没有结果时给空表而不是 null。 */
    private static List<String> namesOf(ToolSearchResponse response) {
        if (response == null || response.toolReferences() == null) {
            return List.of();
        }
        return response.toolReferences().stream().map(ToolReference::toolName).toList();
    }

    /** 一个候选与它当前的融合分。 */
    private record Fused(ToolReference reference, double score) {

        Fused plus(double extra) {
            return new Fused(this.reference, this.score + extra);
        }
    }
}
