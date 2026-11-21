package io.github.claw4j.model.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 向量请求的条数上限：闸在 {@link BatchingEmbeddingModel#call}，所以任何批量入口都安全。
 *
 * <p>不碰网络：真模型是假的，只记"每次请求几条、请求的是哪几条"。
 * 这里一律从 {@code embed(List)} 进——它是调用方真正用的入口，也是默认实现转向 {@code call} 的地方。
 */
class BatchingEmbeddingModelTest {

    /** 通义 text-embedding-v3 的单请求 input 上限。 */
    private static final int PROVIDER_LIMIT = 10;

    @Test
    @DisplayName("25 条按 10/10/5 分三批：一次发整篇会被 400 拒掉")
    void batchesNeverExceedProviderLimit() {
        List<List<String>> calls = new ArrayList<>();
        BatchingEmbeddingModel embeddings = new BatchingEmbeddingModel(recordingModel(calls));

        embeddings.embed(texts(25));

        assertThat(calls).as("25 条、每批上限 10 条 → 3 批").extracting(List::size).containsExactly(10, 10, 5);
        assertThat(calls).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(PROVIDER_LIMIT));
    }

    @Test
    @DisplayName("跨批的向量按原顺序拼回：顺序错了下游是在给两件不同的东西配对")
    void vectorsKeepInputOrder() {
        BatchingEmbeddingModel embeddings = new BatchingEmbeddingModel(recordingModel(new ArrayList<>()));

        List<float[]> vectors = embeddings.embed(texts(25));

        assertThat(vectors).hasSize(25);
        for (int i = 0; i < vectors.size(); i++) {
            assertThat(vectors.get(i)[0]).as("第 %d 条向量应属于第 %d 条文本", i, i).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("合并后的 index 是全局序号：沿用每批的 0 起序号会让下游按 index 放置时错位")
    void mergedResultsAreReindexed() {
        BatchingEmbeddingModel embeddings = new BatchingEmbeddingModel(recordingModel(new ArrayList<>()));

        List<Embedding> results = embeddings.embedForResponse(texts(25)).getResults();

        assertThat(results).extracting(Embedding::getIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 25).boxed().toList());
    }

    @Test
    @DisplayName("用量按批相加：合并成一条响应后仍要看得见这次一共花了多少输入 token")
    void usageIsSummedAcrossBatches() {
        BatchingEmbeddingModel embeddings = new BatchingEmbeddingModel(recordingModel(new ArrayList<>()));

        EmbeddingResponse response = embeddings.embedForResponse(texts(25));

        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(25);
    }

    @Test
    @DisplayName("不超过上限就原样一次发：不为了分批而把一次请求拆成多次")
    void singleRequestWhenWithinLimit() {
        List<List<String>> calls = new ArrayList<>();
        BatchingEmbeddingModel embeddings = new BatchingEmbeddingModel(recordingModel(calls));

        embeddings.embed(texts(PROVIDER_LIMIT));

        assertThat(calls).as("正好 10 条也只发一次").hasSize(1);
    }

    @Test
    @DisplayName("向量库那条路（三参 embed + 它自己的 batching strategy）同样受闸：这就是撞过两次的那个点")
    void documentEmbeddingPathIsBatchedToo() {
        List<List<String>> calls = new ArrayList<>();
        BatchingEmbeddingModel embeddings = new BatchingEmbeddingModel(recordingModel(calls));
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            documents.add(new Document("t" + i));
        }

        // PgVectorStore 默认按 token 预算分批，一份文档切出的 11 块会落在同一批里。
        List<float[]> vectors = embeddings.embed(documents, EmbeddingOptions.builder().build(),
                batch -> List.of(batch));

        assertThat(vectors).hasSize(11);
        assertThat(calls).extracting(List::size).containsExactly(10, 1);
    }

    @Test
    @DisplayName("dimensions 与单条求向量都透传给真模型：装饰器不该改这两件事")
    void delegatesDimensionsAndSingleEmbedding() {
        EmbeddingModel delegate = recordingModel(new ArrayList<>());
        when(delegate.dimensions()).thenReturn(1024);

        BatchingEmbeddingModel embeddings = new BatchingEmbeddingModel(delegate);

        assertThat(embeddings.dimensions()).isEqualTo(1024);
        assertThat(embeddings.embed("t7")).hasSize(1);
    }

    @Test
    @DisplayName("没有真模型就不该造出来：否则第一次取向量才报 NPE")
    void rejectsNullDelegate() {
        assertThatThrownBy(() -> new BatchingEmbeddingModel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("真模型");
    }

    /**
     * 假模型：记下每次请求的 input，返回与入参等长、且按文本序号编码向量的响应。
     *
     * <p>向量值取文本里的序号，是为了让"顺序有没有错"变成可断言的事实——都返回同一个常量的话，
     * 顺序错了也看不出来。用量按 input 条数报，便于验证跨批相加。
     */
    private static EmbeddingModel recordingModel(List<List<String>> calls) {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        // 三参 embed 会逐份文档问"要送什么文本"，默认实现在接口上；mock 不桩就返回 null。
        when(embeddings.getEmbeddingContent(any(Document.class)))
                .thenAnswer(invocation -> ((Document) invocation.getArgument(0)).getText());
        when(embeddings.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            List<String> inputs = request.getInstructions();
            calls.add(List.copyOf(inputs));
            List<Embedding> results = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                float value = Float.parseFloat(inputs.get(i).substring(1));
                // 每批的 index 都从 0 起：这正是需要外层重新编号的原因。
                results.add(new Embedding(new float[]{value}, i));
            }
            return new EmbeddingResponse(results, new EmbeddingResponseMetadata("fake",
                    new DefaultUsage(inputs.size(), 0, inputs.size())));
        });
        return embeddings;
    }

    /** 造 n 条文本：{@code t0 … t(n-1)}，序号写进文本，假模型据此编码向量。 */
    private static List<String> texts(int n) {
        List<String> texts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            texts.add("t" + i);
        }
        return texts;
    }
}
