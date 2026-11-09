package io.github.claw4j.agent.rag;

import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
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
 * 混合检索：向量 + 全文两路，用 {@link ReciprocalRankFusionJoiner} 融合。
 *
 * <p><b>为什么两路都在一个类里</b>：`RetrievalAugmentationAdvisor.Builder#documentRetriever`
 * 是**单数字段**，而它交给 `DocumentJoiner` 的每个 query 只带一路结果（字节码里
 * `List.of(retriever 的结果)` 又包了一层）。所以框架留的"多路"入口在 advisor 这条路上用不到，
 * 两路只能在这一个 retriever 内部跑完。融合仍然走官方的 {@link DocumentJoiner} 接口。
 *
 * <p><b>权限在这里判，不在工具层</b>：换成 advisor 之后检索不是工具调用，
 * `AuthorizationAdvisor` 管不到它。但 `before()` 会把 `ChatClientRequest.context()` 整体复制进
 * {@link Query#context()}，而 {@code Principal} 本来就在那里（{@link ContextKeys#PRINCIPAL}），
 * 所以判据不变，只是位置从"工具被调之前"搬到"检索之前"。
 *
 * <p><b>没有身份就返回空，不抛</b>：没有身份不是故障，是一条不该看到资料的请求。
 * 抛出去会让这一轮对话失败，而正确答案是"像没有知识库那样回答"。
 *
 * <p><b>一路挂了不影响另一路</b>：向量服务超时、全文 SQL 报错，各自退化成空列表并 WARN；
 * 两路都挂才抛。这与语义缓存相反——检索是回答的依据，"查不到"必须与"没查"分得开。
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    /**
     * 知识库在授权表里占的"表名"。
     *
     * <p><b>它是伪表名</b>：{@code doc} 不是业务表，但鉴权问的是同一个问题——"这个身份能不能看
     * 这份数据"。复用 {@code read} 动作 + 一个表名，好处是"能不能看知识库"与"能不能查销售单"
     * 用同一套授权表表达，不必新增一个动作名（那意味着所有角色配置都要改一遍才能检索）。
     *
     * <p>代价：只给了 {@code region} 的角色（如 {@code guest}）默认看不到知识库。那是**想要**的
     * 默认，但不在 {@code application.yml} 里写出来，就会变成"配了知识库，可某些人问什么都说没有"。
     */
    static final String DOC_TABLE = "doc";

    private static final ToolPermission DOC_PERMISSION = ToolPermission.read(DOC_TABLE);

    private final DocumentRetriever vector;

    /** 全文那一路。{@code hybrid.enabled=false} 时是 null——不是"装了但不调用"，是不装。 */
    private final DocumentRetriever keyword;

    private final DocumentJoiner joiner;

    public HybridDocumentRetriever(DocumentRetriever vector, DocumentRetriever keyword,
                                   DocumentJoiner joiner) {
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
        List<Document> fused = joiner.join(Map.of(query, List.of(vectorHits, keywordHits)));
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
     * 这一轮能不能看知识库。
     *
     * <p>读的是 {@link Query#context()}：advisor 在那里放了 `ChatClientRequest.context()` 的副本。
     * 判据与工具时代同一个 {@link ToolPermission}，所以"谁能看知识库"的口径没有变。
     */
    private static boolean permitted(Query query) {
        Map<String, Object> context = query.context();
        Object value = context == null ? null : context.get(ContextKeys.PRINCIPAL);
        if (!(value instanceof Principal principal)) {
            log.warn("这一轮没有身份（context 里没有 {}），跳过知识库检索", ContextKeys.PRINCIPAL);
            return false;
        }
        return principal.canUse(DOC_PERMISSION);
    }
}
