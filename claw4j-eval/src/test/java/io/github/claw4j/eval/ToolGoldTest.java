package io.github.claw4j.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * 工具检索的题面与打分：题写错了当场报，分算错了当场红。
 *
 * <p>这里不评"哪个索引好"——那个要真工具目录与真 embedding（{@code @Tag("eval")} 那条手跑通道）。
 * 这里只钉两件：**题面是可信的**（键、分类、数量），**分数是随索引变的**（一个永远返回全部的索引
 * 不该得 0 分、一个永远空手的索引不该得满分）。
 *
 * <p>第二条看着像废话，但它挡的是最坏的那种评估：指标实现读错了字段，所有配置都得到同一个好看的
 * 分数，而人看着表格相信了。
 */
class ToolGoldTest {

    @Test
    @DisplayName("题面严格校验：不认识的键、不认识的分类、空问题都当场报")
    void rejectsBadEntries() {
        assertThatThrownBy(() -> ToolGold.from(Map.of(
                "id", "x", "question", "问一句", "category", "base", "gold_tool", List.of("Read"))))
                .as("键写成 gold_tool（少个 s）时若被静默忽略，这条题的 gold 就永远为空——分数虚高且没人发现")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gold_tool");

        assertThatThrownBy(() -> ToolGold.from(Map.of(
                "id", "x", "question", "问一句", "category", "busines", "gold_tools", List.of("Read"))))
                .as("分类打错一个字母，那一档就悄悄少一条题")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category");

        ToolGold ok = ToolGold.from(Map.of(
                "id", "ok", "question", "你好", "category", "no-search", "gold_tools", List.of()));
        assertThat(ok.goldTools())
                .as("空 gold 是合法的：它的意思是「不该有候选」，不是「没写」")
                .isEmpty();
    }

    @Test
    @DisplayName("名次算术：第 3 名命中不算 R@1，但算 R@3，MRR 是 1/3")
    void recallCountsHitWithinK() {
        List<ToolGold> golds = List.of(new ToolGold("t-1", "问一句", "business", List.of("querySales")));

        List<ToolRetrievalEval.Score> third = ToolRetrievalEval.evaluate("stub",
                new StubToolIndex(List.of("Read", "Grep", "querySales")), List.of(), golds);
        ToolRetrievalEval.Score all = third.getFirst();
        assertThat(all.recall1()).isZero();
        assertThat(all.recall3()).isEqualTo(1.0);
        assertThat(all.recall5()).isEqualTo(1.0);
        assertThat(all.mrr()).isCloseTo(1.0 / 3, offset(1e-9));

        List<ToolRetrievalEval.Score> miss = ToolRetrievalEval.evaluate("stub",
                new StubToolIndex(List.of("Read", "Grep", "Glob")), List.of(), golds);
        assertThat(miss.getFirst().recall5()).as("前 5 里没有它，@5 也是 0").isZero();
        assertThat(miss.getFirst().mrr()).isZero();
    }

    @Test
    @DisplayName("分数随索引变：全给与全空两头都得动，且按分类分开报")
    void metricsMoveWithTheIndex() {
        List<ToolGold> golds = List.of(
                new ToolGold("a", "上周成交额", "business", List.of("querySales")),
                new ToolGold("b", "你好", "no-search", List.of()));

        List<ToolRetrievalEval.Score> perfect = ToolRetrievalEval.evaluate("perfect",
                new StubToolIndex(List.of("querySales")), List.of(), golds);
        assertThat(byCategory(perfect, "business").recall1()).isEqualTo(1.0);

        // 一个"永远返回全部工具"的索引：业务那档照样命中，但闲聊那档收到候选 = 噪声，必须判没中。
        List<ToolRetrievalEval.Score> noisy = ToolRetrievalEval.evaluate("noisy",
                new StubToolIndex(List.of("querySales", "Read", "Grep", "Glob", "Bash")), List.of(), golds);
        assertThat(byCategory(noisy, "no-search").recall5())
                .as("闲聊收到候选就是噪声；判成命中的话，越乱给分越高")
                .isZero();
        assertThat(byCategory(noisy, "business").recall1())
                .as("同一份题、不同索引，两档的差别要各自看得见")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("真题库能加载：20 条以上、四类齐全、id 不重复")
    void goldFileLoads() throws IOException {
        List<ToolGold> golds = loadGold();
        assertThat(golds).as("第一版按 20 条起，够看出差别、又不至于写一天").hasSizeGreaterThanOrEqualTo(20);

        Set<String> categories = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (ToolGold gold : golds) {
            categories.add(gold.category());
            assertThat(ids.add(gold.id())).as("id 重复会让报告里两条题混成一条：" + gold.id()).isTrue();
        }
        assertThat(categories)
                .as("四类缺一类，就等于那个失效模式没人看：基座 / 业务 / 中文问英文 / 不该检索")
                .containsExactlyInAnyOrderElementsOf(ToolGold.CATEGORIES);
    }

    private static ToolRetrievalEval.Score byCategory(List<ToolRetrievalEval.Score> scores, String category) {
        return scores.stream().filter(score -> score.category().equals(category)).findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有这一档：" + category));
    }

    @SuppressWarnings("unchecked")
    private static List<ToolGold> loadGold() throws IOException {
        Path file = TestPaths.repositoryRoot().resolve("eval/tools/gold.yaml");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Map<String, Object>> rows = new Yaml().load(reader);
            List<ToolGold> golds = new ArrayList<>(rows.size());
            rows.forEach(row -> golds.add(ToolGold.from(row)));
            return golds;
        }
    }

    /**
     * 只按顺序交回固定候选的索引。
     *
     * <p>不打分、不排序、不看 query：本类验的是"分数算得对不对"，任何真实检索行为都会把它变成
     * "顺便验了索引"，而那是评测通道的事。
     */
    private static final class StubToolIndex implements ToolIndex {

        private final List<ToolReference> hits;

        StubToolIndex(List<String> names) {
            this.hits = names.stream()
                    .map(name -> ToolReference.builder().toolName(name).summary(name).build())
                    .toList();
        }

        @Override
        public void indexTool(String sessionId, ToolReference toolReference) {
        }

        @Override
        public void indexTools(String sessionId, List<ToolReference> toolReferences) {
        }

        @Override
        public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
            return ToolSearchResponse.builder().toolReferences(this.hits).totalMatches(this.hits.size()).build();
        }

        @Override
        public void clearIndex(String sessionId) {
        }
    }
}
