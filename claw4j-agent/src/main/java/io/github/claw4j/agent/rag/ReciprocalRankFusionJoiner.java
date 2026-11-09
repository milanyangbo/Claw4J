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
 * 两路融合：**Reciprocal Rank Fusion，只看名次，不碰分数**。
 *
 * <p><b>为什么不用官方的 {@code ConcatenationDocumentJoiner}</b>：它按 {@code Document.getScore()}
 * 倒序。而两路的分数不同源——向量那一路是余弦（0~1），全文那一路是命中次数（整数，甚至为空）。
 * 用它得到的稳定结果是"向量那一路永远排在前面"：错得很安静，因为输出看起来总是有条理的。
 *
 * <p><b>为什么是 RRF 而不是加权求和</b>：加权要先归一，而"归一到多少"没有依据，又是一个拍出来的
 * 常数。RRF 只要求每路给得出**顺序**——那是两路都天然有的东西。公式
 * {@code score(d) = Σ 1 / (k + rank(d))}，k=60 是论文默认值（Cormack et al., 2009）。
 *
 * <p><b>去重键是 {@link Document#getId()}</b>：两路查的是同一张表，同一个块在两路命中时 id 相同。
 * 求和本身就合并了，不需要额外的去重步骤——重复加到同一个键上的分数更高，这正是"两路都认可"
 * 该有的结果。
 *
 * <p><b>融合分写回 {@link Document#getScore()}</b>，不留在内部 map 里。两个来源都指向同一件事：
 * OpenClaw 的 {@code mergeHybridResults} 给结果对象赋的是 {@code score: rankingScore}（融合分就是
 * 它对外的那一个分）；而 Spring AI 的排序信号本来就只有一个——{@code Document#getScore()}，
 * advisor 里紧接着跑的官方 {@code ConcatenationDocumentJoiner} 是
 * {@code sorted(comparingDouble(Document::getScore).reversed())}。不写回的话，它会把
 * "只有全文那一路命中的块"（score 为 null → 按 0.0）整个甩到末尾，RRF 排出来的顺序当场作废，
 * 且同分之间还取决于 HashMap 的迭代顺序。
 *
 * <p>同类里只做三件事：按 id 合并、同一路内去重、把融合分写进 {@code score}。**不截断**
 * （截断只有一处，在 {@code TopKDocumentPostProcessor}，见 ADR-0020 的检索修订段）。
 */
public class ReciprocalRankFusionJoiner implements DocumentJoiner {

    /**
     * 60。论文默认值。
     *
     * <p>它的作用是压平"第一名与第二名"的差距：k 越小，头部越陡，某一路的第一名就能压过
     * 另一路的第一名；k 越大，两路越平等。60 对应"两路大致可信"——我们的向量与全文
     * 各有各的盲区，没有谁该压过谁。
     */
    static final int K = 60;

    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        if (documentsForQuery == null || documentsForQuery.isEmpty()) {
            return List.of();
        }
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();

        for (List<List<Document>> routes : documentsForQuery.values()) {
            if (routes == null) {
                continue;
            }
            for (List<Document> route : routes) {
                if (route == null || route.isEmpty()) {
                    continue;
                }
                Set<String> seenInRoute = new LinkedHashSet<>();
                int rank = 0;
                for (Document document : route) {
                    rank++;
                    if (document == null || document.getId() == null) {
                        continue;
                    }
                    // 同一路里重复出现只算第一次的名次：重复拿分等于把一路的分量偷偷加倍。
                    if (!seenInRoute.add(document.getId())) {
                        continue;
                    }
                    fused.merge(document.getId(), 1.0 / (K + rank), Double::sum);
                    byId.putIfAbsent(document.getId(), document);
                }
            }
        }

        List<String> ordered = new ArrayList<>(byId.keySet());
        // 稳定排序：融合分相同时保持"先被看到的那一路"的顺序。
        ordered.sort(Comparator.comparingDouble((String id) -> fused.getOrDefault(id, 0.0)).reversed());
        List<Document> merged = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            // mutate() 会带上 id / text / metadata，只换 score：下游（含官方的
            // ConcatenationDocumentJoiner）按 score 排序，融合分才会成为它的顺序。
            merged.add(byId.get(id).mutate().score(fused.get(id)).build());
        }
        return merged;
    }
}
