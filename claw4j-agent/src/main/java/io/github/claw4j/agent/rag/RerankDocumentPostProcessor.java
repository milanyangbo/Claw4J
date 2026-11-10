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
 * <p>直接调 HTTP，是因为 Spring AI 2.0.2 没有 rerank 类型：BOM 里没有模块，jar 里也没有
 * {@code RerankModel} 这样的接口。所以"实现框架的 rerank 接口"这条路不成立，只能在框架留的
 * {@link DocumentPostProcessor} 槽位里自己发请求。这是本项目唯一一处"框架没有、直接调 HTTP"
 * 的能力，记账在 {@code docs/design/knowledge-retrieval.md} 第六节。
 *
 * <p>用 JDK 的 {@code HttpClient} 而不是 RestClient：{@code claw4j-agent} 的 classpath 上没有
 * {@code spring-web}，为一个调用引一整个 web 模块不值得，而 Jackson 本来就在。
 *
 * <p>失败一律原样返回：4xx/5xx、超时、响应缺 {@code results}、下标越界或重复，全部退回候选的
 * 原顺序。重排是排序优化，不是检索；它挂掉时结果必须还在，否则一次接口抖动就会让用户得到
 * "知识库里没有这条"，而那与"文档确实没写"分不开。
 *
 * <p>只重排，不截断：输入是候选池前 {@code candidates} 条，输出还是这些条，只是换了顺序。
 * "最终只留 top-k"是 {@link TopKDocumentPostProcessor} 的事——两件事分开，才不会出现
 * "重排关了，提示词里就塞进 30 段"这种只在关掉某个开关时才出现的偏差。
 *
 * <p>上限：它是专用 reranker，给的是 query 与文档的相关性分，可解释、可截断，但不判断"这段能不能
 * 回答问题"——段落写得含糊、缺前提条件，它照样可能给高分。所以回答仍要带出处，让人回去看原文。
 */
public class RerankDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RerankDocumentPostProcessor.class);

    private final HttpClient http;

    private final ObjectMapper json;

    private final String url;

    private final String model;

    private final Duration timeout;

    private final int candidates;

    /** 每次请求现取：真值不进字段（P08 的同一条约束）。取不到时降级为不重排。 */
    private final Supplier<String> apiKey;

    /**
     * @param http       HTTP 客户端
     * @param json       JSON 编解码
     * @param url        重排端点
     * @param model      rerank 模型名
     * @param timeout    超时，超时按原顺序返回
     * @param candidates 送进重排的条数，至少 1
     * @param apiKey     取凭据真值的入口，每次请求现取
     */
    public RerankDocumentPostProcessor(HttpClient http, ObjectMapper json, String url, String model,
                                       Duration timeout, int candidates, Supplier<String> apiKey) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.url = Objects.requireNonNull(url, "url");
        this.model = Objects.requireNonNull(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.candidates = Math.max(1, candidates);
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        // 送进重排的条数由配置定：候选池比最终条数大，但没必要把整池都发过去（每次都是一次
        // HTTP + 一次计费）。顺序是上游融合给的，所以"取前 N 条"就是"融合后最靠前的 N 条"。
        List<Document> pool = documents.size() > candidates
                ? List.copyOf(documents.subList(0, candidates))
                : documents;
        // 一条候选没有"顺序"可排；两条以下重排也不可能改变集合内容。
        if (pool.size() < 2 || query == null || query.text() == null || query.text().isBlank()) {
            return pool;
        }
        try {
            return reorder(pool, rerank(query.text(), pool));
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("重排被中断，这一轮按候选原顺序继续");
            return pool;
        }
        catch (Exception failure) {
            log.warn("重排失败（{}），这一轮按候选原顺序继续：{}",
                    failure.getClass().getSimpleName(), failure.getMessage());
            return pool;
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
     * 按返回的下标重排。条数不变：没被点到的候选按原顺序补在后面。
     *
     * <p>下面每一种"不合法的返回"都只丢那一项，不整体放弃：服务给出半个答案时，丢掉的部分按原顺序
     * 补在后面，比整轮不重排更接近用户预期。一条都不可用时才退回原顺序。
     *
     * @param documents 候选池
     * @param results   服务返回的下标与分数
     * @return 重排后的列表，条数与入参相同
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
        }
        if (reordered.isEmpty()) {
            return documents;
        }
        // 没被点到的候选按原顺序补在后面——它们的名次本来就在末尾。
        for (int index = 0; index < documents.size(); index++) {
            if (taken.add(index)) {
                reordered.add(documents.get(index));
            }
        }
        return reordered;
    }

    /**
     * 请求体。字段名用注解钉死：端点是 OpenAI 兼容格式，snake_case 是它的契约。
     *
     * @param model           模型名
     * @param query           用户问题
     * @param documents       候选正文
     * @param topN            要返回几条
     * @param returnDocuments 是否回传正文，本类固定 false
     */
    record Request(String model, String query, List<String> documents,
                   @JsonProperty("top_n") int topN,
                   @JsonProperty("return_documents") boolean returnDocuments) {
    }

    /**
     * 响应体。只要 {@code results}：文本已经有了，{@code return_documents=false} 让它别回传。
     *
     * @param results 每条候选的下标与相关性分
     */
    record Response(List<Result> results) {
    }

    /**
     * @param index          对应输入 {@code documents} 中的下标
     * @param relevanceScore 0~1，同一请求内可比、跨请求不可比
     */
    record Result(int index, @JsonProperty("relevance_score") double relevanceScore) {
    }
}
