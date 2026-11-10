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
 * 把向量与全文两路命中按 0.7 / 0.3 加权合并成一个列表。
 *
 * <p>权重是固定值，与 OpenClaw 的 {@code hybrid.vectorWeight} / {@code textWeight} 一致
 * （{@code src/agents/memory-search.ts:165-166}）。不做成配置：多一个旋钮，"结果为什么变了"
 * 就多一个可能的答案。
 *
 * <p>全文那路的分是命中次数，先按本次查询的最大值归一（{@code hits / maxHits}）再乘 0.3，
 * 两路才都在 0~1。归一对应 OpenClaw 的 {@code createKeywordScoreScale}
 * （{@code extensions/memory-core/src/memory/hybrid.ts:66}）里"按本次最强命中度量"这一步；
 * 它还叠了 {@code score / (1 - score)} 的反饱和与向量上限缩放，本仓库都没有——那一步是为 BM25
 * 的饱和曲线准备的，而这里的全文分是计数，本来线性。
 *
 * <p>融合分写回 {@link Document#getScore()}：下游的 {@code ConcatenationDocumentJoiner} 按这个分
 * 排序，不写回去会被重排。OpenClaw 的 {@code mergeHybridResults}（同文件 {@code :81}）也是把
 * 融合分赋给结果的 score。
 *
 * <p>不截断：截断只发生在 {@link TopKDocumentPostProcessor}。代价是只被全文命中的块最多 0.3 分，
 * 排序上会输给余弦高于 0.43 的向量命中，但仍在候选里，只是名次靠后。
 */
public final class WeightedScoreJoiner implements DocumentJoiner {

    /** 向量那一路的权重。OpenClaw 的 {@code hybrid.vectorWeight}。 */
    static final double VECTOR_WEIGHT = 0.7;

    /** 全文那一路的权重。OpenClaw 的 {@code hybrid.textWeight}。 */
    static final double TEXT_WEIGHT = 0.3;

    /**
     * 按 {@code vectorHits}、{@code keywordHits} 的顺序融合两路命中。
     *
     * <p>参数顺序就是路别。{@link DocumentJoiner#join} 的 {@code Map} 签名不带路别信息，
     * 传反了会把余弦当命中次数用，而且不报错。
     *
     * @param vectorHits  向量命中，分是余弦（0~1）
     * @param keywordHits 全文命中，分是命中次数（{@link KeywordDocumentRetriever} 写的）
     * @return 融合后的列表，按融合分降序，同分时按出处定序
     */
    public List<Document> join(List<Document> vectorHits, List<Document> keywordHits) {
        double maxHits = 0;
        for (Document hit : keywordHits) {
            maxHits = Math.max(maxHits, textScoreOf(hit));
        }
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();

        // 向量先走：两路都命中时保留向量那一条，它的 score 是余弦，排障时用得上。
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
        // 同分时按出处（路径、段号）定序：只有全文命中的块常常同为一个命中数，不定序就会退化成
        // 迭代顺序，每次启动的排列都不一样。
        ordered.sort(Comparator
                .comparingDouble((String id) -> fused.getOrDefault(id, 0.0)).reversed()
                .thenComparing(id -> pathOf(byId.get(id)))
                .thenComparingInt(id -> ordinalOf(byId.get(id))));

        List<Document> merged = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            // 只换 score，id 与 metadata 保持原样：下游按 score 排序。
            merged.add(byId.get(id).mutate().score(fused.getOrDefault(id, 0.0)).build());
        }
        return List.copyOf(merged);
    }

    /**
     * 接口签名。一路或两路时转给 {@link #join(List, List)}（第 0 路向量、第 1 路全文）；
     * 更多路时没有路别信息，只做按 id 去重并保序，不猜着加权。
     *
     * <p>不要把它插进 advisor 的 {@code documentJoiner} 槽位：那里收到的是 retriever 已经融合过
     * 一次的列表，再当成向量那一路加权就等于把分又乘一次 0.7。advisor 那边用的是
     * {@link org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner}
     * （融合分已在 {@code score} 里，它只负责按 score 排）。
     *
     * @param documentsForQuery 每个查询对应的各路文档
     * @return 去重保序后的列表
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
