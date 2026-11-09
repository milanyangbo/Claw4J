package io.github.claw4j.agent.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 重排：调通义的 {@code qwen3-rerank}，实现框架的 {@link DocumentPostProcessor}。
 *
 * <p><b>为什么直接调 HTTP</b>：Spring AI 2.0.2 全仓没有 rerank 类型——BOM 里没有模块，
 * 逐个 jar 扫 {@code rerank} 也是 0 命中，连 {@code RerankModel} 这个接口都不存在。
 * 所以"实现框架的 rerank 接口"这条路不成立，只能在框架留的
 * {@link DocumentPostProcessor} 槽位里自己发请求。这是本项目**唯一**一处
 * "官方没有、直接调 HTTP"的能力，记账在 {@code docs/design/knowledge-retrieval.md} 第六节。
 *
 * <p><b>为什么用 JDK 的 HttpClient 而不是 RestClient</b>：{@code claw4j-agent} 的 classpath
 * 上没有 {@code spring-web}（依赖树里查过）。为一个调用引一整个 web 模块不值得，
 * 而 Jackson 本来就在。
 *
 * <p><b>失败一律原样返回</b>：4xx/5xx、超时、响应缺 {@code results}、下标越界或重复——
 * 全部退回候选的原顺序。重排是**排序优化，不是检索**；它挂掉时结果必须还在，
 * 否则一次接口抖动就会让用户得到"知识库里没有这条"，而那与"文档确实没写"分不开。
 * 这一点比"排序对不对"重要得多，所以这里没有任何一条路径会抛出去。
 *
 * <p><b>上限要说清</b>：它是专用 reranker，给的是 query 与文档的相关性分，可解释、可截断。
 * 但它不判断"这段能不能回答问题"——段落写得含糊、缺前提条件，它照样可能给高分。
 * 所以回答仍然要带出处，让人能回去看原文。
 */
public class RerankDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RerankDocumentPostProcessor.class);

    private final HttpClient http;

    private final ObjectMapper json;

    private final String url;

    private final String model;

    private final Duration timeout;

    private final int topK;

    /** 每次请求现取：真值不进字段（P08 的同一条约束）。取不到时降级为不重排。 */
    private final Supplier<String> apiKey;

    public RerankDocumentPostProcessor(HttpClient http, ObjectMapper json, String url, String model,
                                       Duration timeout, int topK, Supplier<String> apiKey) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.url = Objects.requireNonNull(url, "url");
        this.model = Objects.requireNonNull(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.topK = topK;
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        // 一条候选没有"顺序"可排；两条以下重排也不可能改变集合内容。
        if (documents.size() < 2 || query == null || query.text() == null || query.text().isBlank()) {
            return documents;
        }
        try {
            return reorder(documents, rerank(query.text(), documents));
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("重排被中断，这一轮按候选原顺序继续");
            return documents;
        }
        catch (Exception failure) {
            log.warn("重排失败（{}），这一轮按候选原顺序继续：{}",
                    failure.getClass().getSimpleName(), failure.getMessage());
            return documents;
        }
    }

    private List<Result> rerank(String text, List<Document> documents) throws Exception {
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            log.debug("没有可用的重排凭据，跳过重排");
            return List.of();
        }
        String body = json.writeValueAsString(new Request(model, text,
                documents.stream().map(Document::getText).toList(),
                documents.size(), false));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            // 只记状态码，不记 body：错误响应里可能回显请求内容（我们刚把整段文档发过去了）。
            log.warn("重排端点返回 {}，这一轮按候选原顺序继续", response.statusCode());
            return List.of();
        }
        Response parsed = json.readValue(response.body(), Response.class);
        return parsed == null || parsed.results() == null ? List.of() : parsed.results();
    }

    /**
     * 按返回的下标重排，截到 {@code topK}。
     *
     * <p>下面每一种"不合法的返回"都只丢那一项，不整体放弃：
     * 模型/服务给出半个答案时，丢掉的部分按原顺序补在后面，比"整轮不重排"更接近用户预期。
     * 一条都不可用时才退回原顺序。
     */
    private List<Document> reorder(List<Document> documents, List<Result> results) {
        if (results.isEmpty()) {
            return documents;
        }
        List<Document> reordered = new ArrayList<>(documents.size());
        Set<Integer> taken = new HashSet<>();
        for (Result result : results) {
            if (result == null) {
                continue;
            }
            int index = result.index();
            if (index < 0 || index >= documents.size() || !taken.add(index)) {
                continue;
            }
            reordered.add(documents.get(index));
            if (reordered.size() >= topK) {
                break;
            }
        }
        if (reordered.isEmpty()) {
            return documents;
        }
        // 没被点到的候选按原顺序补在后面，补到 topK 为止——它们的名次本来就在末尾。
        for (int index = 0; index < documents.size() && reordered.size() < topK; index++) {
            if (taken.add(index)) {
                reordered.add(documents.get(index));
            }
        }
        return reordered;
    }

    /** 请求体。字段名用注解钉死：端点是 OpenAI 兼容格式，snake_case 是它的契约。 */
    record Request(String model, String query, List<String> documents,
                   @JsonProperty("top_n") int topN,
                   @JsonProperty("return_documents") boolean returnDocuments) {
    }

    /** 响应体。只要 {@code results}：文本我们已经有了，{@code return_documents=false} 让它别回传。 */
    record Response(List<Result> results) {
    }

    /**
     * @param index           对应输入 {@code documents} 中的下标
     * @param relevanceScore  0~1，同一请求内可比、跨请求不可比（官方文档原话）
     */
    record Result(int index, @JsonProperty("relevance_score") double relevanceScore) {
    }
}
