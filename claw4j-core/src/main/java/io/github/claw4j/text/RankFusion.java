package io.github.claw4j.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 把几路检索结果合成一个排名：Reciprocal Rank Fusion。
 *
 * <p><b>为什么不是分数加权</b>：向量通道给的是余弦相似度（0~1），关键词通道给的是
 * {@code ts_rank}（实测 0.268330 这种量级）。两者量纲不同，直接相加要先归一化，而归一化
 * 系数会随文档集变化——那是"调到看起来对"。RRF 只用**排名**：
 *
 * <pre>
 * score(d) = Σ 1 / (k + rank_i(d))
 * </pre>
 *
 * 参数只有一个 {@code k}，且它的取值对结果不敏感（论文与实践中 60 是缺省）。这让"混合"
 * 这件事变得可断言：一路漏掉的东西，只要另一路把它排得足够前，它就会浮上来。
 *
 * <p><b>为什么它是纯函数而不是在 SQL 里做</b>：融合要看到两路的完整排名，而两路是两个查询。
 * 在应用侧做意味着这段逻辑可以被单测（给两组名字，断言顺序），而写在 SQL 里只能靠真库
 * 观察结果——那测的是"这一次的结果"，不是"融合规则"。
 */
public final class RankFusion {

    /**
     * 缺省 k。取 60 不是因为这里调过，是因为它是这个算法的通用缺省值：
     * 它决定"第 1 名比第 10 名强多少"，60 时这个比例约 1.2 倍，足够温和到
     * 让"两路都排第二"胜过"一路排第一"。
     */
    public static final int DEFAULT_K = 60;

    private RankFusion() {
    }

    public static <T> List<T> fuse(List<List<T>> channels, Function<T, String> key) {
        return fuse(channels, key, DEFAULT_K);
    }

    /**
     * @param channels 每一路的结果，已按相关度降序；路与路之间的顺序不参与打分
     * @param key      同一份东西在两路里的身份。用它合并——用对象相等会把"同一条文档块
     *                 从两个查询里取回来"当成两条
     * @param k        平滑常数，必须为正
     */
    public static <T> List<T> fuse(List<List<T>> channels, Function<T, String> key, int k) {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(key, "key");
        if (k <= 0) {
            throw new IllegalArgumentException("RRF 的 k 必须为正：" + k);
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, T> items = new LinkedHashMap<>();
        for (List<T> channel : channels) {
            int rank = 0;
            for (T item : channel) {
                rank++;
                String id = key.apply(item);
                items.putIfAbsent(id, item);
                scores.merge(id, 1.0 / (k + rank), Double::sum);
            }
        }
        List<String> order = new ArrayList<>(scores.keySet());
        // 稳定排序：同分时保持首次出现的顺序，也就是"先被向量路看到的那条在前"。
        // 不这么做的话，同分的结果在两次调用之间可能换序，模型看到的上下文就跟着变。
        order.sort(Comparator.comparingDouble((String id) -> scores.get(id)).reversed());
        return order.stream().map(items::get).toList();
    }
}
