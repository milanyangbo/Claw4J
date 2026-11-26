package io.github.claw4j.eval;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 工具检索的打分：Recall@1/@3/@5 与 MRR，按 {@link ToolGold#category()} 分开报。
 *
 * <p>只评**检索层**：给一句人话，索引的前 k 里有没有那个工具。模型拿到候选后选不选对是另一件事
 * （BFCL 那一层：AST 比对 + 会不会在"没有工具可用"时老实拒调），要真模型与多轮，不在本类。
 *
 * <p>为什么分开分类报：混成一个总分时，"中文问英文描述"那一档掉到 0.2 会被"业务工具那档 1.0"
 * 平均掉，而前者才是要修的那个洞。分类不是为了好看，是为了失败时知道该改哪边。
 *
 * <p><b>gold 为空</b>的题（"你好""谢谢"）不算 recall：它们的判据是"一个候选都别给"，
 * 给一个就是噪声。这类单列一档、按"零候选率"报——塞进 recall 平均会让"多给候选"看起来更好。
 */
public final class ToolRetrievalEval {

    /** 一次检索取几个候选。与线上默认（{@code max-results: 5}）同一个数，否则评的不是线上那件事。 */
    private static final int MAX_RESULTS = 5;

    /** 报告里的"全部"那一行。 */
    public static final String ALL = "全部";

    /**
     * 一档配置 × 一个分类的分数。
     *
     * @param config   配置名（{@code lucene} / {@code hybrid-0.5} …）
     * @param category 分类；{@link #ALL} 表示全部
     * @param total    这一档有几条题
     * @param recall1  前 1 名命中的比例
     * @param recall3  前 3 名命中的比例
     * @param recall5  前 5 名命中的比例
     * @param mrr      第一个命中项名次的倒数均值；没命中记 0
     */
    public record Score(String config, String category, int total,
                        double recall1, double recall3, double recall5, double mrr) {
    }

    /**
     * 给一份题库打分。
     *
     * <p>每次调用先把整份目录灌进索引，再逐题检索。灌用的是同一个会话号——分档之间互不影响，
     * 因为索引实例是调用方每档新建的；分题影响才是要防的，而每题换会话号在这里没有意义
     * （工具目录在同一档里是同一份）。
     *
     * @param config    配置名，只用于报告
     * @param index     要评的索引，调用方保证它是新建的、空的
     * @param catalogue 工具目录（名字 + 描述），就是线上那一份
     * @param golds     题库
     * @return 按分类 + 全部的两组分数
     */
    public static List<Score> evaluate(String config, ToolIndex index, List<ToolReference> catalogue,
                                       List<ToolGold> golds) {
        index.indexTools(config, catalogue);

        // 每档内部先累计，最后一并算比值：边算边除会引入逐步舍入，且分母丢了就没法重算。
        Map<String, Tally> byCategory = new LinkedHashMap<>();
        Tally all = new Tally();
        for (ToolGold gold : golds) {
            List<String> got = names(index.search(new ToolSearchRequest(config, gold.question(),
                    MAX_RESULTS, null)));
            Hits hits = Hits.of(gold, got);
            byCategory.computeIfAbsent(gold.category(), key -> new Tally()).add(hits);
            all.add(hits);
        }

        List<Score> scores = new ArrayList<>();
        scores.add(all.score(config, ALL));
        // 分类按名字排序而不是按题目出现顺序：两次跑出来行序一致，diff 才有意义。
        for (String category : new TreeSet<>(byCategory.keySet())) {
            scores.add(byCategory.get(category).score(config, category));
        }
        return scores;
    }

    /**
     * 把若干档分数排成一张定宽表，写日志与贴进票面用。
     *
     * @param scores 每档每类一行
     * @return 可直接打印的文本
     */
    public static String table(List<Score> scores) {
        StringBuilder text = new StringBuilder();
        text.append(String.format("%-14s %-10s %5s %8s %8s %8s %7s%n",
                "配置", "分类", "题数", "R@1", "R@3", "R@5", "MRR"));
        for (Score score : scores) {
            text.append(String.format("%-14s %-10s %5d %8.3f %8.3f %8.3f %7.3f%n",
                    score.config(), score.category(), score.total(),
                    score.recall1(), score.recall3(), score.recall5(), score.mrr()));
        }
        return text.toString();
    }

    private static List<String> names(ToolSearchResponse response) {
        if (response == null || response.toolReferences() == null) {
            return List.of();
        }
        return response.toolReferences().stream().map(ToolReference::toolName).toList();
    }

    /**
     * 一条题的命中情况。
     *
     * @param rank      第一个 gold 工具的名次；0 表示前 5 里没有
     * @param goldEmpty 这题是否"不该有候选"（题库里 {@code gold_tools: []}）
     * @param noCandidate 这一次检索是否一个候选都没给
     */
    private record Hits(int rank, boolean goldEmpty, boolean noCandidate) {

        static Hits of(ToolGold gold, List<String> got) {
            boolean empty = got.isEmpty();
            if (gold.goldTools().isEmpty()) {
                // 空的 gold = 不该有候选。有候选就是噪声，名次记 0，与"没翻到"同一个结果。
                return new Hits(0, true, empty);
            }
            for (int i = 0; i < got.size(); i++) {
                if (gold.goldTools().contains(got.get(i))) {
                    return new Hits(i + 1, false, empty);
                }
            }
            return new Hits(0, false, empty);
        }

        boolean within(int k) {
            return this.rank > 0 && this.rank <= k;
        }

        /**
         * 命中与否。
         *
         * <p>「零候选」只在**这题本来就不该有候选**时才算命中。原来的实现是
         * {@code noCandidate || within(k)}——不看 {@code goldEmpty}，于是"索引一个字都没返回"
         * 这个最坏的失效拿了满分，而且只在坏的时候生效（好配置上看不出来）。2026-11-26 发现：
         * `regex` 下 20 道 `zh-en` 里有 3 道返回空，自研记成 R@1 0.150，第三方口径是 0.000。
         */
        boolean matched(int k) {
            return this.goldEmpty ? this.noCandidate : within(k);
        }

        double reciprocalRank() {
            if (this.rank > 0) {
                return 1.0 / this.rank;
            }
            return this.goldEmpty && this.noCandidate ? 1.0 : 0.0;
        }
    }

    /** 一档里若干条题的累计。 */
    private static final class Tally {

        private int total;

        private int hit1;

        private int hit3;

        private int hit5;

        private double mrr;

        void add(Hits hits) {
            this.total++;
            if (hits.matched(1)) {
                this.hit1++;
            }
            if (hits.matched(3)) {
                this.hit3++;
            }
            if (hits.matched(5)) {
                this.hit5++;
            }
            this.mrr += hits.reciprocalRank();
        }

        Score score(String config, String category) {
            if (this.total == 0) {
                return new Score(config, category, 0, 0, 0, 0, 0);
            }
            return new Score(config, category, this.total,
                    (double) this.hit1 / this.total,
                    (double) this.hit3 / this.total,
                    (double) this.hit5 / this.total,
                    this.mrr / this.total);
        }
    }

    private ToolRetrievalEval() {
    }
}
