package io.github.claw4j.rag.retrieval;

import io.github.claw4j.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BM25 那一路的单测：空查询与启动前检查。
 *
 * <p>真容器里的端到端命中在 {@code Bm25SearchTest}（那边默认跳过）。
 *
 * <p>这里钉的是两件在真库上也看不出来的事：<b>空查询不该打库</b>（否则一次"没结果"会被记成
 * 查询解析失败），以及<b>引擎不可用时必须启动失败</b>（没有 bm25 索引时查询直接报错，
 * 不是变慢——留到第一次提问就只剩一条看不懂的 Postgres 报错）。
 */
class Bm25KeywordDocumentRetrieverTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final Bm25KeywordDocumentRetriever retriever =
            new Bm25KeywordDocumentRetriever(jdbc, "docs", 20);

    @Test
    @DisplayName("空查询与纯标点不打库：那是真实的没结果，不是故障")
    void blankAndPunctuationDoNotHitDatabase() {
        // 三种都拿桩：`Query` 的构造器拒绝空串与纯空白，而这两条分支必须被覆盖——
        // 它们挡的是"把一次没结果记成查询解析失败"。
        assertThat(retriever.retrieve(queryWith(""))).isEmpty();
        assertThat(retriever.retrieve(queryWith("   "))).isEmpty();
        assertThat(retriever.retrieve(queryWith("？？！……"))).isEmpty();
        assertThat(retriever.retrieve(null)).isEmpty();

        verifyNoInteractions(jdbc);
    }

    /** 一个只用来送文本的 Query 桩。 */
    private static Query queryWith(String text) {
        Query query = mock(Query.class);
        when(query.text()).thenReturn(text);
        return query;
    }

    @Test
    @DisplayName("只有一个字也要查：长度判断属于引擎，不属于我们")
    void singleCharacterStillSearches() {
        assertThat(Bm25KeywordDocumentRetriever.hasSearchable("退")).isTrue();
        assertThat(Bm25KeywordDocumentRetriever.hasSearchable("A2")).isTrue();
        assertThat(Bm25KeywordDocumentRetriever.hasSearchable("，")).isFalse();
        assertThat(Bm25KeywordDocumentRetriever.hasSearchable(null)).isFalse();
    }

    @Test
    @DisplayName("库里没有 pg_search：启动失败，消息里给换镜像与设回 substring 两条出路")
    void failsFastWhenPgSearchIsMissing() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        assertThatThrownBy(() -> Bm25KeywordDocumentRetriever.ensureIndex(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ParadeDB")
                .hasMessageContaining(RagProperties.Keyword.ENGINE_SUBSTRING);

        verify(jdbc, never()).execute(contains("using bm25"));
    }

    @Test
    @DisplayName("扩展在但索引建不出来：同样启动失败，不吞原始原因")
    void failsWhenIndexCreationFails() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        doThrow(new RuntimeException("syntax error at or near \"bm25\""))
                .when(jdbc).execute(contains("using bm25"));

        assertThatThrownBy(() -> Bm25KeywordDocumentRetriever.ensureIndex(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bm25 索引失败")
                .hasRootCauseMessage("syntax error at or near \"bm25\"");
    }

    @Test
    @DisplayName("正常路径：先建扩展再建索引，两条都带 if not exists")
    void createsExtensionAndIndex() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);

        Bm25KeywordDocumentRetriever.ensureIndex(jdbc);

        verify(jdbc).execute(contains("create extension if not exists pg_search"));
        verify(jdbc).execute(contains("using bm25 (id, (content::pdb.jieba))"));
    }
}
