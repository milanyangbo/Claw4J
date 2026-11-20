package io.github.claw4j.rag;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 混合检索：向量 + 全文两路，用 {@link WeightedScoreJoiner} 融合（0.7 / 0.3）。
 *
 * <p>两路在同一个类里跑完：{@code RetrievalAugmentationAdvisor.Builder#documentRetriever} 是单值
 * 字段，它交给 {@link DocumentJoiner} 的每个 query 只带一路结果，框架留的"多路"入口在 advisor
 * 这条路上用不到。融合仍走 {@link DocumentJoiner} 接口。
 *
 * <p>判据只剩一条：<b>这一轮有没有身份</b>。原来还问"这个身份能不能看 {@code doc} 这张伪表"，
 * 那是业务数据授权，随权限登记表在 ADR-0030 里整块下线；知识库的细粒度 ACL
 * 属于第二步（记在 ADR 缺口表与 {@code guardrails.md}）。这是<b>行为放宽</b>，测试里显式立了一条
 * （{@code RagTest#anyPrincipalCanRetrieve}），别让它悄悄变化。
 *
 * <p>没有身份就返回空，不抛：那是"说不清是谁"的一条请求，抛出去会让这一轮失败，
 * 正确答案是"像没有知识库那样回答"。
 *
 * <p>一路挂了不影响另一路：向量超时、全文 SQL 报错，各自退化成空列表并 WARN，两路都挂才抛。
 * 检索是回答的依据，"查不到"必须与"没查"分得开。
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    private final DocumentRetriever vector;

    /** 全文那一路。{@code hybrid.enabled=false} 时是 null——不是装了不调用，是不装。 */
    private final DocumentRetriever keyword;

    /**
     * 融合。类型是具体的 {@link WeightedScoreJoiner} 而不是 {@link DocumentJoiner}：加权要知道哪一路
     * 是向量、哪一路是全文，而接口签名里没有这个信息，不该留一个能传任意 joiner 进来的口子。
     */
    private final WeightedScoreJoiner joiner;

    /**
     * @param vector  向量那一路，不能为 null
     * @param keyword 全文那一路，{@code hybrid.enabled=false} 时为 null
     * @param joiner  两路融合，不能为 null
     */
    public HybridDocumentRetriever(DocumentRetriever vector, DocumentRetriever keyword,
                                   WeightedScoreJoiner joiner) {
        this.vector = Objects.requireNonNull(vector, "vector");
        this.joiner = Objects.requireNonNull(joiner, "joiner");
        this.keyword = keyword;
    }

    @Override
    public List<Document> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        if (!permitted(query)) {
            return List.of();
        }
        if (keyword == null) {
            return vector.retrieve(query);
        }

        List<RuntimeException> failures = new ArrayList<>(2);
        List<Document> vectorHits = attempt(vector, query, "向量", failures);
        List<Document> keywordHits = attempt(keyword, query, "全文", failures);
        if (failures.size() == 2) {
            // 两路都挂：抛第一个，后面那个只记日志——两个异常叠在一起，调用方也只看得懂一个。
            log.error("知识库两路检索都失败，这一轮按检索失败处理", failures.get(1));
            throw failures.get(0);
        }
        if (vectorHits.isEmpty() && keywordHits.isEmpty()) {
            return List.of();
        }
        List<Document> fused = joiner.join(vectorHits, keywordHits);
        if (log.isDebugEnabled()) {
            // 融合后条数要现算：两路之和减掉的是重复命中的块，那正是"融合有没有起作用"的证据。
            log.debug("知识库「{}」：向量 {} 条、全文 {} 条，融合后 {} 条",
                    query.text(), vectorHits.size(), keywordHits.size(), fused.size());
        }
        return fused;
    }

    private static List<Document> attempt(DocumentRetriever retriever, Query query, String lane,
                                          List<RuntimeException> failures) {
        try {
            List<Document> found = retriever.retrieve(query);
            return found == null ? List.of() : found;
        }
        catch (RuntimeException failure) {
            failures.add(failure);
            log.warn("知识库{}那一路检索失败，这一路按空处理", lane, failure);
            return List.of();
        }
    }

    /**
     * 这一轮有没有身份。
     *
     * <p>读的是 {@link Query#context()}：advisor 在那里放了 {@code ChatClientRequest.context()} 的
     * 副本。有身份就检索——"能看哪些资料"这一层现在没有判据，见类注释。
     *
     * @param query 本次检索
     * @return 有身份时为 true
     */
    private static boolean permitted(Query query) {
        Map<String, Object> context = query.context();
        Object value = context == null ? null : context.get(ContextKeys.PRINCIPAL);
        if (value instanceof Principal) {
            return true;
        }
        log.warn("这一轮没有身份（context 里没有 {}），跳过知识库检索", ContextKeys.PRINCIPAL);
        return false;
    }
}
