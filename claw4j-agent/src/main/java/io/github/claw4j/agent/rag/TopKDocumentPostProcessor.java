package io.github.claw4j.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;
import java.util.Objects;

/**
 * 最终截断：注入到提示词里的资料**只有 {@code top-k} 段**。
 *
 * <p><b>为什么需要一个单独的类</b>：Spring AI 的检索链路是
 * retriever → joiner → post-processors → augmenter，而它自己没有"最终取几条"的部件：
 * {@code VectorStoreDocumentRetriever#topK} 是**召回**条数（我们的候选池有 15×2 条），
 * {@code DocumentPostProcessor} 在 2.0.2 里连一个实现都没有。所以"只留 top-k"这件事
 * 只能落在 post-processor 这一步——那是链路里**最后一个**还能动这个列表的地方。
 *
 * <p><b>为什么放在重排之后</b>：重排要看比最终条数更多的候选（否则它只能在 5 条里挪位置），
 * 所以重排的输入是候选池、输出仍是候选池，截断交给这里。这与 OpenClaw 的顺序一致：
 * 它把 {@code slice(0, maxResults)} 放在融合与选择之后（{@code manager-search-retrieval.ts}），
 * 不是放在召回那一步。
 *
 * <p><b>它不是"可选的优化"</b>：没有它，{@code claw4j.rag.top-k} 就只是一个没人读的数字，
 * 而提示词里会塞进两路候选池的全部内容（默认 30 段）。这类偏差不报错、只变贵，
 * 顺便让"资料编号"与用户看到的答案失去对应关系。
 */
public class TopKDocumentPostProcessor implements DocumentPostProcessor {

    private final int topK;

    public TopKDocumentPostProcessor(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("top-k 必须为正数，收到 " + topK);
        }
        this.topK = topK;
    }

    /**
     * 保住前 {@code topK} 条，其余丢掉。**不重排、不合并**：顺序是上游（融合 + 重排）的事，
     * 这里再动一次就等于有两个排序规则。
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
