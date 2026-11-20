package io.github.claw4j.agent.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 给向量模型套一层"单请求最多 10 条 input"的闸。
 *
 * <p>为什么闸在这里而不是在各个调用方：单请求上限是<b>模型的性质</b>，不是调用方的性质。
 * 通义 {@code text-embedding-v3} 一次只收 10 条，而我们这边每一个"我要一批向量"的调用方都
 * 不知道这件事——向量库入库时会把一份文档的全部块一次交出去，切块器会把一篇的全部句子组
 * 一次交出去。任何一处忘了分批，症状都是 {@code 400 batch size is invalid}：轻则整篇文档
 * 退化成降级切块，重则索引启动就失败。这个上限只写在这一个地方，调用方照常按"一次要一批"写。
 *
 * <p>这不是假设。第一版把分批写在切块器里，跑评测就撞上了第二个调用点
 * （{@code PgVectorStore.add} 一次交 11 块）；第二版把闸放在 {@code embed(List)} 上，同一次
 * 评测又撞了第三次——{@link EmbeddingModel#embed(List, org.springframework.ai.embedding.EmbeddingOptions,
 * org.springframework.ai.embedding.BatchingStrategy)} 这条默认实现是直接 {@code call(request)} 的，
 * 根本不经过 {@code embed(List)}。所以闸落在 {@link #call}：Spring AI 里所有批量入口
 * （{@code embed(List)}、{@code embedForResponse}、三参 {@code embed}）最后都汇到这里。
 *
 * <p>跨批合并的口径：
 * <ul>
 *   <li>{@code index} 重新编成全局序号——它是"这条请求里的第几条"，跨批沿用会让下游按 index 放置时错位；</li>
 *   <li>用量按批相加（输入 token 相加，输出恒 0，向量请求没有输出 token），
 *       每批自己的 observation 仍由真模型上报，不会因为合并而丢；</li>
 *   <li>{@code nativeUsage} 不保留：它是单条请求的原生结构，拼起来只会得到一份假的。</li>
 * </ul>
 *
 * <p>失败语义：一批失败就把异常抛给调用方，不吞、不重试。调用方各有各的降级
 * （切块器退成只按转折词，索引则让启动失败并说清哪份文档），在这里重试只会把一次抖动
 * 拖成启动变慢。
 */
public class BatchingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(BatchingEmbeddingModel.class);

    /**
     * 单请求最多几条 input：通义 {@code text-embedding-v3} 的接口上限。
     *
     * <p>写成常量而不是配置键：换一家上限更大的模型，多分几批只是多发几次请求，不坏；
     * 而多一个配置就多一处要维护的默认值，还得有人记得它跟模型名一起改。
     */
    private static final int MAX_INPUTS_PER_REQUEST = 10;

    private final EmbeddingModel delegate;

    /**
     * @param delegate 真模型
     * @throws IllegalArgumentException 传入 {@code null}
     */
    public BatchingEmbeddingModel(EmbeddingModel delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("要包一个真模型：没有 delegate 就没有向量");
        }
        this.delegate = delegate;
    }

    /**
     * 唯一的闸：超过上限就切片逐批取，顺序拼回。
     *
     * @param request 一次要的整批
     * @return 与入参等长、同序的响应
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Objects.requireNonNull(request, "Request must not be null");
        List<String> inputs = request.getInstructions();
        if (inputs == null || inputs.size() <= MAX_INPUTS_PER_REQUEST) {
            return delegate.call(request);
        }
        log.debug("向量请求 {} 条，按每批 {} 条切成 {} 批", inputs.size(), MAX_INPUTS_PER_REQUEST,
                (inputs.size() + MAX_INPUTS_PER_REQUEST - 1) / MAX_INPUTS_PER_REQUEST);

        List<Embedding> merged = new ArrayList<>(inputs.size());
        String model = null;
        int promptTokens = 0;
        for (int from = 0; from < inputs.size(); from += MAX_INPUTS_PER_REQUEST) {
            int to = Math.min(from + MAX_INPUTS_PER_REQUEST, inputs.size());
            // 顺序请求而不是并行：向量与入参按位置配对，打乱顺序不会报错，只会让下游算错。
            EmbeddingResponse response = delegate.call(
                    new EmbeddingRequest(inputs.subList(from, to), request.getOptions()));
            for (Embedding embedding : response.getResults()) {
                merged.add(new Embedding(embedding.getOutput(), merged.size(), embedding.getMetadata()));
            }
            if (response.getMetadata() != null) {
                model = model == null ? response.getMetadata().getModel() : model;
                promptTokens += tokensOf(response.getMetadata().getUsage());
            }
        }
        return new EmbeddingResponse(merged,
                new EmbeddingResponseMetadata(model, new DefaultUsage(promptTokens, 0, promptTokens)));
    }

    /** 单条求向量不绕闸：一条输入不可能超上限，多一层判断只是多一次分支。 */
    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    /** 一批的输入 token 数；上游没报用量时按 0 计，不猜。 */
    private static int tokensOf(Usage usage) {
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        return promptTokens == null ? 0 : promptTokens;
    }
}
