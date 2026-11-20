package io.github.claw4j.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义切块的降级：向量那条判据挂了，转折词那条还得活着。
 *
 * <p>不碰网络、不碰容器：向量模型是假的。请求条数上限不在这里验——那道闸在
 * {@code BatchingEmbeddingModel}，这一层照常"一次要整篇"。
 */
class SemanticTextSplitterTest {

    /** 单块 token 上限。取默认值，本类不验硬切。 */
    private static final int CHUNK_TOKENS = 800;

    @Test
    @DisplayName("向量算不出来：不抛给调用方，整篇退回只按转折词切，块数变少但仍出块")
    void unavailableVectorsDegradeToTransitionOnly() {
        List<String> sentences = sentences(25);
        String text = join(sentences);

        // 前半篇与后半篇的向量明显不同：向量真的参与了断点，块数会多于"只按转折词"。
        int withVectors = chunkCount(new SemanticTextSplitter(jumpingVectors(sentences.size()), CHUNK_TOKENS), text);
        assertThat(withVectors).as("向量可用时中部有一次话题跃迁，块数应多于降级结果").isGreaterThan(1);

        int degraded = chunkCount(new SemanticTextSplitter(failingVectors(), CHUNK_TOKENS), text);
        assertThat(degraded).as("只剩转折词判据：这份语料没有转折词，于是整篇一块").isEqualTo(1);
    }

    /** 跑一遍切块，返回块数。 */
    private static int chunkCount(SemanticTextSplitter splitter, String text) {
        return splitter.apply(List.of(new Document(text))).size();
    }

    /** 假模型：前一半句子的向量朝一个方向，后一半换方向——制造一次话题跃迁。 */
    private static EmbeddingModel jumpingVectors(int sentenceCount) {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        int[] emitted = {0};
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            List<float[]> result = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                boolean firstHalf = emitted[0]++ < sentenceCount / 2;
                result.add(firstHalf ? new float[]{1, 0, 0} : new float[]{0, 1, 0});
            }
            return result;
        });
        return embeddings;
    }

    /** 假模型：一律抛错，模拟上游挂掉。 */
    private static EmbeddingModel failingVectors() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenThrow(new RuntimeException("400: 上游挂了"));
        return embeddings;
    }

    /** 造 n 句：每句都超过 {@code MIN_SENTENCE_CHARS}，免得被并回上一句。 */
    private static List<String> sentences(int n) {
        List<String> sentences = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            sentences.add("第" + i + "条说明，这里写够八个字。");
        }
        return sentences;
    }

    private static String join(List<String> sentences) {
        return String.join("", sentences);
    }
}
