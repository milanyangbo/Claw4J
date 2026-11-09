package io.github.claw4j.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 两路融合：**加权求和**，矢量分 0.7 + 全文分 0.3。
 *
 * <p><b>权重是与 OpenClaw 对齐的固定值</b>（{@code src/agents/memory-search.ts:165}：
 * {@code hybrid: { vectorWeight: 0.7, textWeight: 0.3 }}），**不做成配置**——它那边的
 * {@code memory.search.query.hybrid.vectorWeight} 已经在死键清单里（配置项被撤了）。
 * 多一个旋钮，只会让"结果为什么变了"多一个可能的答案。
 *
 * <p><b>两路的分怎么变成可比的</b>：向量那一路给的是余弦（0~1）；全文那一路给的是**命中次数**
 * （整数，越大越相关）。融合前先把全文分归一：{@code share = hits / 本次查询的最大 hits}，
 * 也就是 OpenClaw 的 {@code createKeywordScoreScale} 里"按这一问的最强命中来度量"那一步。
 *
 * <p><b>没有照搬它的 {@code relevance = score / (1 - score)} 反饱和</b>：那一步是为了把 BM25
 * 的饱和曲线还原成线性，而我们的分是计数，本来就是线性的，套上去只会把分布压扁。
 * 归一之后两路都在 0~1，权重才真的是权重。
 *
 * <p><b>融合分写回 {@link Document#getScore()}</b>：advisor 里紧接着跑的官方
 * {@code ConcatenationDocumentJoiner} 是 {@code sorted(comparingDouble(Document::getScore).reversed())}
 * （null 按 0.0），不写回去它会把我们排好的顺序重排掉。OpenClaw 的 {@code mergeHybridResults}
 * 也是把融合分赋给结果的 {@code score}。
 *
 * <p><b>已知代价（写在这里，不藏）</b>：只有全文命中的块最多拿 0.3 分，因此**排序上会输给
 * 余弦高于 0.43 的向量命中**（0.7 × 0.43 ≈ 0.3）。这是 0.7/0.3 这个配比的直接后果：
 * 它的用途是"编号类查询别被向量挤掉召回"，不是"让字面命中排到最前"。召回不受影响——
 * 那一路的块照样在候选里，只是名次靠后；真要它靠前，改的是权重，不是这里。
 *
 * <p>同类里只做三件事：按 id 合并两路、同一路内去重、加权后写回 {@code score}。
 * **不截断**（截断只有一处，在 {@link TopKDocumentPostProcessor}）。
 */
public final class WeightedScoreJoiner implements DocumentJoiner {

    /** 向量那一路的权重。OpenClaw 的 {@code hybrid.vectorWeight}。 */
    static final double VECTOR_WEIGHT = 0.7;

    /** 全文那一路的权重。OpenClaw 的 {@code hybrid.textWeight}。 */
    static final double TEXT_WEIGHT = 0.3;

    /**
     * 两路融合。**顺序是契约**：第 0 路向量（分是余弦），第 1 路全文（分是命中次数）。
     *
     * <p>用显式的两参数版本而不是靠 {@code Map} 猜路别：{@link DocumentJoiner#join} 的签名里
     * 没有"哪一路是什么"的信息，猜错一次就是静默地把余弦当命中次数用。
     *
     * @param vectorHits  向量那一路的命中，分是余弦相似度
     * @param keywordHits 全文那一路的命中，分是命中次数（{@link KeywordDocumentRetriever} 写的）
     */
    public List<Document> join(List<Document> vectorHits, List<Document> keywordHits) {
        double maxHits = 0;
        for (Document hit : keywordHits) {
            maxHits = Math.max(maxHits, textScoreOf(hit));
        }
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();

        // 向量先走：同一块两路都命中时，保留向量那一条（它的 score 是余弦，重排与排障都用得上）。
        // 每一路内部先去重：同一块在一路里出现两次就给两次权重，等于把这一路的分量偷偷加倍。
        Set<String> seenInVector = new LinkedHashSet<>();
        for (Document hit : vectorHits) {
            String id = idOf(hit);
            if (id == null || !seenInVector.add(id)) {
                continue;
            }
            accumulate(fused, id, VECTOR_WEIGHT * scoreOf(hit));
            collect(byId, id, hit);
        }
        Set<String> seenInKeyword = new LinkedHashSet<>();
        for (Document hit : keywordHits) {
            String id = idOf(hit);
            if (id == null || !seenInKeyword.add(id)) {
                continue;
            }
            double share = maxHits <= 0 ? 0 : textScoreOf(hit) / maxHits;
            accumulate(fused, id, TEXT_WEIGHT * share);
            collect(byId, id, hit);
        }

        List<String> ordered = new ArrayList<>(byId.keySet());
        // 同分时按出处（路径、段号）定序：分数相等是常态（只有全文命中的块常常同为一个命中数），
        // 不定序就会退化成 HashMap 的迭代顺序，每次启动的顺序都不一样。OpenClaw 也是这个兜底。
        ordered.sort(Comparator
                .comparingDouble((String id) -> fused.getOrDefault(id, 0.0)).reversed()
                .thenComparing(id -> pathOf(byId.get(id)))
                .thenComparingInt(id -> ordinalOf(byId.get(id))));

        List<Document> merged = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            // mutate() 带上 id / text / metadata，只换 score：下游按 score 排序，融合分才是它的顺序。
            merged.add(byId.get(id).mutate().score(fused.getOrDefault(id, 0.0)).build());
        }
        return List.copyOf(merged);
    }

    /**
     * 官方签名。一路或两路时走 {@link #join(List, List)}（第 0 路向量、第 1 路全文）；
     * 更多路时没有路别信息，只做"按 id 去重 + 保序"，不猜着加权。
     *
     * <p>我们自己的调用点用两参数版本；这个实现是为了让类真的是一个 {@code DocumentJoiner}
     * ——接口实现方就该在官方签名下也有确定行为，哪怕退化。
     *
     * <p><b>不要把它插进 advisor 的 {@code documentJoiner} 槽位</b>：那里收到的是 retriever
     * 已经融合过一次的列表，再当成"向量那一路"加权就等于把分又乘了一次 0.7。
     * advisor 那边用的是官方的 {@link org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner}
     * （融合分已经在 {@code score} 里，它只负责按 score 排）。
     */
    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        if (documentsForQuery == null || documentsForQuery.isEmpty()) {
            return List.of();
        }
        List<Document> route0 = new ArrayList<>();
        List<Document> route1 = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (List<List<Document>> routes : documentsForQuery.values()) {
            if (routes == null) {
                continue;
            }
            for (int lane = 0; lane < routes.size(); lane++) {
                List<Document> route = routes.get(lane);
                if (route == null) {
                    continue;
                }
                List<Document> target = lane == 0 ? route0 : route1;
                for (Document document : route) {
                    if (document != null && seen.add(idOf(document))) {
                        target.add(document);
                    }
                }
            }
        }
        return join(route0, route1);
    }

    private static void accumulate(Map<String, Double> fused, String id, double contribution) {
        if (id != null && contribution > 0) {
            fused.merge(id, contribution, Double::sum);
        }
    }

    private static void collect(Map<String, Document> byId, String id, Document document) {
        if (id != null) {
            byId.putIfAbsent(id, document);
        }
    }

    private static String idOf(Document document) {
        return document == null ? null : document.getId();
    }

    /** 向量那一路的分：余弦。没有分就当 0，不猜一个"大概相关"。 */
    private static double scoreOf(Document document) {
        Double score = document == null ? null : document.getScore();
        return score == null || score.isNaN() ? 0 : score;
    }

    /** 全文那一路的分：命中次数，只会是非负数。 */
    private static double textScoreOf(Document document) {
        return Math.max(0, scoreOf(document));
    }

    private static String pathOf(Document document) {
        Object path = document == null ? null : document.getMetadata().get(RagMetadata.PATH);
        return path == null ? "" : path.toString();
    }

    private static int ordinalOf(Document document) {
        Object index = document == null ? null : document.getMetadata().get(RagMetadata.INDEX);
        return index instanceof Number number ? number.intValue() : 0;
    }
}
