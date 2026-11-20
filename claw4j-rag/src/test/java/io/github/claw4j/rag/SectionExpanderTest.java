package io.github.claw4j.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 父块回填的单测。
 *
 * <p>不连库：一条 SQL 的结果用 mock 给。这里要验的是"怎么分组、怎么替代、什么时候不聚合"，
 * 都是纯逻辑；SQL 本身在 {@code SearchSqlTest} 里对着真容器验。
 */
class SectionExpanderTest {

    private static final String SECTION = "第二章 退货";

    @Test
    @DisplayName("同章节的两条命中合成一条父块，位置占组内最靠前的名次")
    void sameSectionBecomesOneParent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("vector_store"), any(RowMapper.class), anyString(), anyString(), anyString()))
                .thenReturn(List.of(block("【售后 › 第二章 退货】\n第一段。", 3),
                        block("【售后 › 第二章 退货】\n第二段。", 4)));

        List<Document> result = new SectionExpander(jdbc, "docs", 2400)
                .process(query(), List.of(hit("a.md", SECTION, 3), hit("b.md", "别的章节", 1),
                        hit("a.md", SECTION, 4)));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().getText())
                .as("父块占第一条的位置，前缀只留一份")
                .startsWith("【售后 › 第二章 退货】\n第一段。")
                .contains("第二段。");
        assertThat(result.getFirst().getText().split("【", -1)).as("前缀不该重复").hasSize(2);
        assertThat(result.getFirst().getMetadata().get(RagMetadata.COVERED)).isEqualTo("3,4");
        assertThat(result.get(1).getMetadata().get(RagMetadata.PATH)).as("别的章节不并进来").isEqualTo("b.md");
    }

    @Test
    @DisplayName("跨章节不合并：两章拼一起，重排分数会被无关部分拉平")
    void differentSectionsStaySeparate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        List<Document> result = new SectionExpander(jdbc, "docs", 2400)
                .process(query(), List.of(hit("a.md", "第一章", 1), hit("a.md", "第二章", 2)));

        assertThat(result).hasSize(2);
        assertThat(result).noneSatisfy(document ->
                assertThat(document.getMetadata()).containsKey(RagMetadata.COVERED));
    }

    @Test
    @DisplayName("拼起来超上限就不聚合：宁可不合并，也不让一章把上下文吃光")
    void oversizedSectionIsLeftAlone() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String longBody = "字".repeat(2000);
        when(jdbc.query(contains("vector_store"), any(RowMapper.class), anyString(), anyString(), anyString()))
                .thenReturn(List.of(block("【售后】\n" + longBody, 1), block("【售后】\n" + longBody, 2)));

        List<Document> result = new SectionExpander(jdbc, "docs", 2400)
                .process(query(), List.of(hit("a.md", SECTION, 1), hit("a.md", SECTION, 2)));

        assertThat(result).as("两条原子块原样保留").hasSize(2);
        assertThat(result).noneSatisfy(document ->
                assertThat(document.getMetadata()).containsKey(RagMetadata.COVERED));
    }

    @Test
    @DisplayName("库里这个章节只有一块：不聚合，单块命中时父块只是把上下文变长")
    void singleBlockSectionIsNotExpanded() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("vector_store"), any(RowMapper.class), anyString(), anyString(), anyString()))
                .thenReturn(List.of(block("【售后】\n只有这一段。", 4)));

        List<Document> result = new SectionExpander(jdbc, "docs", 2400)
                .process(query(), List.of(hit("a.md", SECTION, 4), hit("a.md", SECTION, 5)));

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("没有 section 的块不参与聚合：旧数据不能被拼成一条来路不明的父块")
    void blocksWithoutSectionAreLeftAlone() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        Map<String, Object> metadata = RagMetadata.of("a.md", "docs", "1", "hash", null);
        List<Document> input = List.of(new Document("没有前缀", metadata),
                new Document("也没有", RagMetadata.of("a.md", "docs", "2", "hash", null)));

        assertThat(new SectionExpander(jdbc, "docs", 2400).process(query(), input)).isSameAs(input);
    }

    @Test
    @DisplayName("候选只剩一条时直接返回：一条没有可合并的对象")
    void singleCandidateShortCircuits() {
        List<Document> input = List.of(hit("a.md", SECTION, 1));

        assertThat(new SectionExpander(mock(JdbcTemplate.class), "docs", 2400).process(query(), input))
                .isSameAs(input);
    }

    private static Document hit(String path, String section, int index) {
        return new Document("【售后 › " + section + "】\n第 " + index + " 段正文",
                RagMetadata.of(path, "docs", String.valueOf(index), "hash", section));
    }

    private static Document block(String content, int index) {
        return new Document(content, RagMetadata.of(null, null, String.valueOf(index), "hash", null));
    }

    private static Query query() {
        return new Query("退货", new ArrayList<>(), Map.of());
    }
}
