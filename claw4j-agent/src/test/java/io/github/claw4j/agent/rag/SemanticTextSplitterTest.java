package io.github.claw4j.agent.rag;

import io.github.claw4j.chunk.ChunkPlanner;
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
 * 语义切块的分批请求：一次给全篇会被供应商拒掉，随后整篇降级——这条判据就等于没生效过。
 *
 * <p>不碰网络、不碰容器：向量模型是假的，只记"每次发了几条、发的是哪几条"。
 * 这一层验的是分批与降级的接线，切句与断点的算术在 {@code ChunkPlannerTest} 里。
 */
class SemanticTextSplitterTest {

    /** 单块 token 上限。取默认值，本类不验硬切。 */
    private static final int CHUNK_TOKENS = 800;

    /** 供应商单请求 input 上限（通义 text-embedding-v3）。 */
    private static final int PROVIDER_LIMIT = 10;

    @Test
    @DisplayName("25 句按 10/10/5 分三批发：一次塞整篇会被 400 拒掉")
    void batchesNeverExceedProviderLimit() {
        List<List<String>> calls = new ArrayList<>();
        EmbeddingModel embeddings = recordingModel(calls, List.of());

        List<String> sentences = sentences(25);
        split(new SemanticTextSplitter(embeddings, CHUNK_TOKENS), sentences);

        assertThat(calls).as("25 句、每批上限 10 条 → 3 批")
                .extracting(List::size)
                .containsExactly(10, 10, 5);
        assertThat(calls).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(PROVIDER_LIMIT));
    }

    @Test
    @DisplayName("批次按句序拼回：顺序错了，断点会落在不相邻的两组之间，而且不报错")
    void batchesKeepSentenceOrder() {
        List<List<String>> calls = new ArrayList<>();
        EmbeddingModel embeddings = recordingModel(calls, List.of());

        List<String> sentences = sentences(25);
        split(new SemanticTextSplitter(embeddings, CHUNK_TOKENS), sentences);

        List<String> sent = ChunkPlanner.sentences(join(sentences));
        assertThat(calls.stream().flatMap(List::stream).toList())
                .as("每批入参拼起来必须等于句子组的原始顺序")
                .containsExactlyElementsOf(ChunkPlanner.groups(sent, ChunkPlanner.BUFFER_SIZE));
    }

    @Test
    @DisplayName("第二批失败不抛给调用方：整篇退回只按转折词切，块数变少但仍出块")
    void failureInLaterBatchDegradesWholeDocument() {
        List<String> sentences = sentences(25);
        String text = join(sentences);

        // 前半篇与后半篇的向量明显不同：向量真的参与了断点，块数会多于"只按转折词"。
        int withVectors = chunkCount(new SemanticTextSplitter(jumpingVectors(sentences.size()), CHUNK_TOKENS), text);
        assertThat(withVectors).as("向量可用时中部有一次话题跃迁，块数应多于降级结果").isGreaterThan(1);

        List<List<String>> calls = new ArrayList<>();
        int degraded = chunkCount(new SemanticTextSplitter(recordingModel(calls, List.of(2)), CHUNK_TOKENS), text);

        assertThat(calls).as("第二批失败就停手，不再往后发").hasSize(2);
        assertThat(degraded).as("只剩转折词判据：这份语料没有转折词，于是整篇一块").isEqualTo(1);
    }

    /** 跑一遍切块，返回块数。 */
    private static int chunkCount(SemanticTextSplitter splitter, String text) {
        return splitter.apply(List.of(new Document(text))).size();
    }

    private static void split(SemanticTextSplitter splitter, List<String> sentences) {
        splitter.apply(List.of(new Document(join(sentences))));
    }

    /**
     * 假模型：按调用顺序记下每批入参，返回与入参等长的向量。
     *
     * @param calls      记录每批入参
     * @param failAtCall 第几次调用抛错（1 起数）；空列表表示永不失败
     * @return 假模型
     */
    private static EmbeddingModel recordingModel(List<List<String>> calls, List<Integer> failAtCall) {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            calls.add(List.copyOf(batch));
            if (!failAtCall.isEmpty() && calls.size() == failAtCall.get(0)) {
                throw new RuntimeException("400: batch size is invalid, it should not be larger than 10");
            }
            return vectors(batch.size(), 0, 0);
        });
        return embeddings;
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

    private static List<float[]> vectors(int size, float x, float y) {
        List<float[]> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(new float[]{x, y, 1});
        }
        return result;
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
