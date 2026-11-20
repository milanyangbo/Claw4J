package io.github.claw4j.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 检索链路：两路召回 → 融合 → 重排 → 截断到 top-k。
 *
 * <p>线上每轮对话的检索都走这里：{@code RagAutoConfiguration} 把它注册成 bean，
 * {@code RetrievalAugmentationAdvisor} 拿到的 {@code documentRetriever} 就是它。实现
 * {@link DocumentRetriever}，对调用方只留「给前 k 段」一件事。
 *
 * <p>顺序定义在这个类里。把截断调到重排前面、或者漏掉重排，装配代码照样编译、照样启动，没有测试会红，
 * 用户看到的只是答得更差。收成一个对象之后，改顺序会红在 {@code KnowledgeRetrievalTest} 里。
 *
 * <p>链外的 joiner 必须直通，理由见 {@link #passThroughJoiner()}。
 */
public class KnowledgeRetrievalPipeline implements DocumentRetriever {

    /**
     * 两路召回。收具体类型而不是 {@link DocumentRetriever}：这一层的语义就是「向量 + 全文 + 加权融合」，
     * 换掉等于换掉这个类，而不是塞一个别的实现进来。
     */
    private final HybridDocumentRetriever retriever;

    /** 融合（{@link WeightedScoreJoiner} 在检索器内部跑，这一步只做按 id 去重与按融合分排序）。 */
    private final DocumentJoiner joiner;

    /**
     * 重排。没装时为 null——重排可关，截断不可关。
     *
     * <p>收 {@link DocumentPostProcessor} 而不是 {@link RerankDocumentPostProcessor}：这一层只要求
     * 「换顺序、不改条数」，收具体类的话，验这条链就得真发一次 HTTP。
     */
    private final DocumentPostProcessor reranker;

    /**
     * 唯一截断点。它不跟着重排走：重排可关，「注入几段」不可选（见 {@link TopKDocumentPostProcessor}）。
     */
    private final DocumentPostProcessor truncation;

    /**
     * 由四段部件拼出这条链。
     *
     * @param retriever 两路召回
     * @param joiner    去重与按融合分排序
     * @param reranker  重排，没装时传 null
     * @param topK      最终留几段
     */
    public KnowledgeRetrievalPipeline(HybridDocumentRetriever retriever, DocumentJoiner joiner,
                                      DocumentPostProcessor reranker, int topK) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.joiner = Objects.requireNonNull(joiner, "joiner");
        this.reranker = reranker;
        this.truncation = new TopKDocumentPostProcessor(topK);
    }

    /**
     * 走完线上那条链，返回最终注入提示词的段落。
     *
     * @param query 本次查询，context 里必须带身份
     * @return 前 {@code topK} 段，按名次
     */
    @Override
    public List<Document> retrieve(Query query) {
        return truncation.process(query, candidates(query));
    }

    /**
     * 重排之后、截断之前的完整候选池。
     *
     * <p>给评测用：截到 top-k 之后，报告只能说「缺的那段不在前 5 条」，而「排第 9」与「压根不在
     * 候选里」要改的地方完全不同（一个是 top-k 或重排，一个是切块或召回）。
     *
     * <p>评分侧按 k 截之后再算：k 不超过 top-k 时，这里的 Recall@k 就是模型真正看到的那几段；
     * k 更大时它只是候选池里的名次，模型的上下文并没有那么长。
     *
     * @param query 本次查询
     * @return 候选池，按重排后的名次
     */
    public List<Document> candidates(Query query) {
        Objects.requireNonNull(query, "query");
        List<Document> hits = retriever.retrieve(query);
        if (hits.isEmpty()) {
            // 空结果不往下走：joiner 与重排对空列表都是空操作，但重排会白记一行 DEBUG，
            // 而"这一轮什么都没召回"与"召回了但被排到最后"是两件要分开看的事。
            return List.of();
        }
        List<Document> ordered = joiner.join(Map.of(query, List.of(hits)));
        return reranker == null ? ordered : reranker.process(query, ordered);
    }

    /**
     * 链外那个 joiner：只把一路结果摊平，不去重、不排序。
     *
     * <p>用它的前提是「一个 query 只有一路结果」，而这正是本链的形状（两路已经在
     * {@link HybridDocumentRetriever} 里合成一路）。默认的 {@code ConcatenationDocumentJoiner}
     * 在这里是有害的：它按 {@code score} 倒序，而重排不改 score。
     *
     * @return 直通 joiner
     */
    public static DocumentJoiner passThroughJoiner() {
        // 两层列表：外层是每个 query，内层是这一轮几路检索各自的结果。摊平就是全部段落，
        // 顺序原样沿用——不 collect 到 Map 再取出，那种写法会按 id 去重并重新排序。
        return documents -> documents.values().stream()
                .flatMap(lanes -> lanes.stream().flatMap(List::stream))
                .toList();
    }
}
