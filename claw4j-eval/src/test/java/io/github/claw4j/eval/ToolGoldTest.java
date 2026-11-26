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

    /**
     * 描述是英文的工具（2026-11-26 逐个打印核实）。
     *
     * <p>{@code Task} 名字像英文，描述其实是中文（"把一个需要好几步的任务交给专职 Subagent…"），
     * 所以它不在名单里；反过来 {@code BashOutput} / {@code KillShell} 名字带大写，描述也是英文。
     *
     * <p>这份名单与 {@code ToolCatalog} 会漂：工具换了描述、或新加了工具，这里要跟着改——这正是
     * 一条数据用例的定位，它挡的是"分类放错"，不是"工具变了"。
     */
    private static final Set<String> ENGLISH_DESCRIPTION_TOOLS = Set.of(
            "AskUserQuestionTool", "TaskOutput", "Skill", "TodoWrite", "BashOutput", "KillShell",
            "WebFetch", "Read", "Grep", "Glob", "Bash");

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

        assertThatThrownBy(() -> ToolGold.from(Map.of(
                "id", "x", "question", "你好", "category", "base", "gold_tools", List.of())))
                .as("空 gold 只剩一个含义：漏写。它会让人以为题目正常、实际分母里少了一道该扣分的题")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gold_tools");
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
                new ToolGold("b", "代码里哪些地方还留着 TODO", "base", List.of("Grep")));

        List<ToolRetrievalEval.Score> perfect = ToolRetrievalEval.evaluate("perfect",
                new StubToolIndex(List.of("querySales")), List.of(), golds);
        assertThat(byCategory(perfect, "business").recall1()).isEqualTo(1.0);

        // 一个"永远返回全部工具"的索引：business 那档照样命中，base 那档也一样——
        // 两档要各自看得见，别把一档的结果算到另一档头上。
        List<ToolRetrievalEval.Score> noisy = ToolRetrievalEval.evaluate("noisy",
                new StubToolIndex(List.of("querySales", "Read", "Grep", "Glob", "Bash")), List.of(), golds);
        assertThat(byCategory(noisy, "business").recall1())
                .as("同一份题、不同索引，两档的差别要各自看得见")
                .isEqualTo(1.0);
        assertThat(byCategory(noisy, "base").recall5())
                .as("Grep 排在第 3 位：@5 命中、@1 不中——两档分开报要能体现出这个差别")
                .isEqualTo(1.0);
        assertThat(byCategory(noisy, "base").recall1()).isZero();
    }

    @Test
    @DisplayName("真题库能加载：20 条以上、三类齐全、id 不重复")
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
                .as("三类缺一类，就等于那个失效模式没人看：基座 / 业务 / 中文问英文")
                .containsExactlyInAnyOrderElementsOf(ToolGold.CATEGORIES);
    }

    /**
     * 这一档要够厚：一格题的分值就是 1/题数，稀了之后任何真实收益都淹没在量化误差里。
     *
     * <p>5 题时一格 0.20，而票 12 的 `TOLERANCE` 是 0.05——判据本身比噪声还粗，那一档的分数
     * 怎么读都是错的。20 题把它压到 0.05，与回归门同一个量级。
     */
    @Test
    @DisplayName("zh-en 至少 20 题：5 题时一格就是 0.20，比回归门的容差还粗")
    void zhEnHasEnoughQuestions() throws IOException {
        long count = loadGold().stream()
                .filter(gold -> gold.category().equals("zh-en"))
                .count();

        assertThat(count)
                .as("zh-en 是唯一能判「中文化值不值」的一档，它的题量决定了那个判断的分辨率")
                .isGreaterThanOrEqualTo(20);
    }

    /**
     * 票 18 之前这一档只有 4 题：一格 0.25，比 `TOLERANCE`（0.05）还粗四倍。
     *
     * <p>症状是"R@5 从 0.25 到 0.75 只差两道题"——读那个数的人和看噪声没有区别。
     * 而且它两种配置下有三道直接 miss，"索引还能不能工作"这条底线其实说不清。
     *
     * <p>为什么下限是 10 而不是 20：这一档的工具池只有 Read/Grep/Glob/Bash 四个，
     * 再往上凑就是同一件事换说法，边际信息量为零。10 题把一格压到 0.10，
     * 与回归门同一个量级，够了。
     */
    @Test
    @DisplayName("base 至少 10 题：4 题时一格 0.25，读那个数跟看噪声没区别")
    void baseHasEnoughQuestions() throws IOException {
        long count = loadGold().stream()
                .filter(gold -> gold.category().equals("base"))
                .count();

        assertThat(count)
                .as("基座工具常驻、掉分不影响线上，但它是「索引还能不能工作」的底线，4 题撑不起这个判断")
                .isGreaterThanOrEqualTo(10);
    }

    /**
     * 这一档的定义是**中文问法 × 英文描述**，gold 必须落在英文描述那一组里。
     *
     * <p>2026-11-26 发现的问题：原来 5 道里有 3 道的 gold（`systemStatus` / `recall_session` /
     * `intent`）描述本来就是中文，测的是"中文问中文"——一件更容易的事。那 0.60 的 R@1 之所以
     * 不难看，一部分原因就在这里，而不是检索好。
     *
     * <p>名单是常量而不是从工具目录现算：从目录算的话，把描述全中文化之后这条永远通过，
     * 而"这一档还测不测得到跨语言"这件事就没人回答了。工具描述真改了要人来改这份名单。
     */
    @Test
    @DisplayName("zh-en 只能指向英文描述的工具：gold 落在中文那组时，这一档测的是更容易的事")
    void zhEnOnlyTargetsEnglishDescriptions() throws IOException {
        for (ToolGold gold : loadGold()) {
            if (!gold.category().equals("zh-en")) {
                continue;
            }
            assertThat(gold.goldTools())
                    .as("这一档的 gold 必须是描述为英文的工具（中文描述的那几个归 business）：" + gold.id())
                    .isNotEmpty()
                    .allSatisfy(tool -> assertThat(ENGLISH_DESCRIPTION_TOOLS)
                            .as("「%s」的描述是中文，放 zh-en 档会让这一档的分数虚高", tool)
                            .contains(tool));
        }
    }

    /**
     * 空手不是命中：有 gold 的题检索返回空，必须记 0 分。
     *
     * <p>2026-11-26 发现的原实现：`noCandidate`（一个候选都没给）被当成了"命中"，不区分这题
     * **该不该有候选**。于是最坏的失效——索引一个字都没返回——拿了满分：`regex` 下 20 道
     * `zh-en` 里有 3 道返回空，就被记成 R@1 = 0.150，而第三方口径（TREC）同一批是 0.000。
     *
     * <p>这个方向的错特别坏：它奖励"查不到任何东西"，并且只在坏的时候生效——好配置上看不出来。
     */
    @Test
    @DisplayName("空手不算命中：有 gold 的题返回空，R@1/R@5/MRR 全是 0")
    void emptyResultIsNotAHit() {
        List<ToolGold> golds = List.of(new ToolGold("t-1", "这个网址写了什么", "zh-en", List.of("WebFetch")));

        ToolRetrievalEval.Score blind = ToolRetrievalEval.evaluate("blind",
                new StubToolIndex(List.of()), List.of(), golds).getFirst();

        assertThat(blind.recall1()).isZero();
        assertThat(blind.recall5()).isZero();
        assertThat(blind.mrr())
                .as("零候选只对「不该给候选」的题算命中；有 gold 的题返回空是最坏的失效")
                .isZero();
    }

    /**
     * `business` 这一档必须含"外部系统工具"那四道。
     *
     * <p>票 13 时它们被换成别的问法（评测上下文没接 MCP，指向 `mail_*` / `github_*` 的题永远
     * miss）。票 14 接上了示例 server，原题回来——这一档若只剩"平台自带工具"，就等于没测
     * 最容易检索不到的那一类：名字长、描述带英文、同域干扰多。
     */
    @Test
    @DisplayName("business 覆盖外部系统：mail_* 与 github_* 各两道")
    void businessCoversExternalSystems() throws IOException {
        Set<String> businessTools = new java.util.HashSet<>();
        for (ToolGold gold : loadGold()) {
            if (gold.category().equals("business")) {
                businessTools.addAll(gold.goldTools());
            }
        }

        assertThat(businessTools)
                .as("这四道题是「外部系统工具找不找得到」的唯一证据；换成别的问法后，这一类就没人测了")
                .contains("mail_list_unread", "mail_send", "github_issue_list", "github_pr_checks");
    }

    private static ToolRetrievalEval.Score byCategory(List<ToolRetrievalEval.Score> scores, String category) {
        return scores.stream().filter(score -> score.category().equals(category)).findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有这一档：" + category));
    }

    @SuppressWarnings("unchecked")
    private static List<ToolGold> loadGold() throws IOException {
        Path file = TestPaths.repositoryRoot().resolve("eval/tools/retrieval/gold.yaml");
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
