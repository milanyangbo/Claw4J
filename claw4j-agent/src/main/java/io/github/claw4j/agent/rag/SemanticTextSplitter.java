package io.github.claw4j.agent.rag;

import io.github.claw4j.chunk.ChunkPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 语义切块：句子之间判语义，块超 token 上限才硬切。
 *
 * <p><b>这一层只干框架相关的事</b>：算句子组的向量、把块拼回文本、超长交回
 * {@link TokenTextSplitter}。切句、分组、断点、成块都在 {@link ChunkPlanner}（`claw4j-core`，
 * 只碰 JDK）。分开的理由是算法不该知道 Spring AI 存在；它也因此能被单独测。
 *
 * <p><b>算法照 LangChain 的 {@code SemanticChunker}</b>，参数用它的默认值
 * （{@code buffer_size=1}、{@code percentile} 95、{@code min_chunk_size} 不合并），
 * 只换中文断句正则、并叠加一条"句首转折词断块"的产品规则。完整裁决见 ADR-0028。
 *
 * <p><b>为什么 {@code chunk-size} 的硬切留在这里</b>：它按 token 算，要 jtokkit，而 core 不能依赖
 * 任何 tokenizer。所以"超长"是这一层的事，不是算法层的事——字数也**只在这一步**出现。
 *
 * <p><b>降级分两层</b>：embedding 挂了只丢"话题跃迁"这条判据，转折词那条不需要向量，
 * 照常生效。整篇退回纯 token 会把一条还活着的判据一起丢掉，没有理由那样做。
 */
public class SemanticTextSplitter extends TextSplitter {

    private static final Logger log = LoggerFactory.getLogger(SemanticTextSplitter.class);

    /** 少于这个句数就没有"相邻组"可言，向量那一路直接跳过（省一次请求）。 */
    private static final int MIN_SENTENCES_FOR_VECTORS = 3;

    private final EmbeddingModel embeddings;

    /** token 上限的兜底。与"切分判据"无关，只在块超限时被调用。 */
    private final TextSplitter tokenSplitter;

    public SemanticTextSplitter(EmbeddingModel embeddings, int chunkSize) {
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.tokenSplitter = TokenTextSplitter.builder().withChunkSize(chunkSize).build();
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> sentences = ChunkPlanner.sentences(text);
        if (sentences.isEmpty()) {
            return List.of();
        }

        // 转折词那条不需要向量，先算；向量那一路成功后再并进去。
        List<Integer> breaks = ChunkPlanner.withTransitionBreaks(sentences, List.of());
        if (sentences.size() >= MIN_SENTENCES_FOR_VECTORS) {
            breaks = ChunkPlanner.withTransitionBreaks(sentences, semanticBreaks(sentences));
        }

        List<String> chunks = new ArrayList<>();
        for (int[] block : ChunkPlanner.blocks(sentences, breaks)) {
            String joined = String.join("", sentences.subList(block[0], block[1]));
            chunks.addAll(withinTokenLimit(joined));
        }
        return List.copyOf(chunks);
    }

    /**
     * 话题跃迁的断点。任何一步不对劲都返回空列表——**只丢这一条判据**，
     * 不让一次接口抖动变成"这份文档索引失败"。
     */
    private List<Integer> semanticBreaks(List<String> sentences) {
        List<String> groups = ChunkPlanner.groups(sentences, ChunkPlanner.BUFFER_SIZE);
        List<float[]> vectors;
        try {
            vectors = embeddings.embed(groups);
        }
        catch (RuntimeException failure) {
            log.warn("语义切块：{} 句的向量算不出来，这一篇只按转折词切：{}",
                    groups.size(), failure.getMessage());
            return List.of();
        }
        if (vectors == null || vectors.size() != groups.size()) {
            // 宁可不用向量，也不要错位配对：错位的断点比没有断点糟，而且它不报错。
            log.warn("语义切块：向量条数 {} 与句子组数 {} 不一致，这一篇只按转折词切",
                    vectors == null ? 0 : vectors.size(), groups.size());
            return List.of();
        }
        return ChunkPlanner.distanceBreakpoints(vectors, ChunkPlanner.BREAKPOINT_PERCENTILE);
    }

    /** 块超 token 上限才硬切；没超就原样（这一步是唯一看字数的地方）。 */
    private List<String> withinTokenLimit(String block) {
        if (block.isBlank()) {
            return List.of();
        }
        List<String> pieces = new ArrayList<>();
        for (Document piece : tokenSplitter.apply(List.of(new Document(block)))) {
            String text = piece.getText();
            if (text != null && !text.isBlank()) {
                pieces.add(text);
            }
        }
        return pieces;
    }
}
