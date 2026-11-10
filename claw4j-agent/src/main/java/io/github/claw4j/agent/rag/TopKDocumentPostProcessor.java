package io.github.claw4j.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;
import java.util.Objects;

/**
 * 最终截断：注入到提示词里的资料只有 {@code top-k} 段。
 *
 * <p>需要一个单独的类，是因为框架的检索链路 retriever → joiner → post-processors → augmenter
 * 里没有"最终取几条"的部件：{@code VectorStoreDocumentRetriever#topK} 是召回条数（候选池有
 * 15×2 条），而 {@code DocumentPostProcessor} 在 2.0.2 里没有现成实现。所以"只留 top-k"
 * 只能落在 post-processor 这一步，那是链路里最后一个还能动这个列表的地方。
 *
 * <p>放在重排之后：重排要看比最终条数更多的候选，否则它只能在 5 条里挪位置。所以重排的输入是
 * 候选池、输出仍是候选池，截断交给这里。
 *
 * <p>它不是可选的优化：没有它，{@code claw4j.rag.top-k} 就只是一个没人读的数字，提示词里会塞进
 * 两路候选池的全部内容（默认 30 段）。这类偏差不报错、只变贵，顺便让资料编号与用户看到的答案
 * 失去对应关系。
 */
public class TopKDocumentPostProcessor implements DocumentPostProcessor {

    private final int topK;

    /**
     * @param topK 保留几段，必须为正
     */
    public TopKDocumentPostProcessor(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("top-k 必须为正数，收到 " + topK);
        }
        this.topK = topK;
    }

    /**
     * 保住前 {@code topK} 条，其余丢掉。不重排、不合并：顺序是上游（融合 + 重排）的事，
     * 这里再动一次就等于有两个排序规则。
     *
     * @param query     本次查询
     * @param documents 上游排好序的候选
     * @return 前 {@code topK} 条
     */
    @Override
    public List<Document> process(Query query, List<Document> documents) {
        Objects.requireNonNull(documents, "documents");
        if (documents.size() <= topK) {
            return documents;
        }
        return List.copyOf(documents.subList(0, topK));
    }
}
