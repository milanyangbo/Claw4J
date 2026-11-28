package io.github.claw4j.agent.toolaccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 工具属于哪一组（OpenAI 那边叫 namespace，框架的检索参数叫 {@code categoryFilter}）。
 *
 * <p>它解决的是"工具多了之后检索范围太宽"：模型知道要什么，但候选里混着几十个不相干的工具，
 * 命中的那几个被别的东西压下去。有了组，检索可以收窄到一组。
 *
 * <p><b>不预置任何工具名映射</b>。预置 {@code file: [Read, Glob, Grep]} 等于把某一批工具写死进
 * 产品，换一批工具就失效——那正是本票要消掉的形状。所以只有两条来源：配置写明的，以及名字前缀
 * （{@code github_issue_list} → {@code github}）。前缀这条不需要任何人维护，代价是名字里没有
 * 下划线时"一组只有一个工具"，等于没分组——那是部署该配 {@code categories} 的信号。
 */
public final class ToolCategories {

    /** 至少有几个工具才算"一组"。只有一个的那种分组没有收窄价值，单独点名。 */
    private static final int MIN_GROUP_SIZE = 2;

    private final Map<String, String> explicit;

    private ToolCategories(Map<String, String> explicit) {
        this.explicit = explicit;
    }

    /**
     * 从配置建。配置的方向是"组 → 工具"，这里反过来存成"工具 → 组"，因为每轮问的是后者。
     *
     * @param configured 组 → 工具名；{@code null} 或空表示全部按前缀推导
     * @return 类别表
     */
    public static ToolCategories of(Map<String, List<String>> configured) {
        if (configured == null || configured.isEmpty()) {
            return new ToolCategories(Map.of());
        }
        Map<String, String> inverted = new LinkedHashMap<>();
        configured.forEach((category, toolNames) -> {
            if (category == null || category.isBlank() || toolNames == null) {
                return;
            }
            toolNames.forEach(toolName -> {
                if (toolName != null && !toolName.isBlank()) {
                    inverted.put(toolName, category);
                }
            });
        });
        return new ToolCategories(Map.copyOf(inverted));
    }

    /**
     * 某个工具属于哪一组：配置优先，否则取名字第一个下划线之前的部分。
     *
     * @param toolName 工具名
     * @return 组名；{@code null} 或空名字返回空串
     */
    public String categoryOf(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }
        String configured = explicit.get(toolName);
        if (configured != null) {
            return configured;
        }
        int underscore = toolName.indexOf('_');
        return underscore > 0 ? toolName.substring(0, underscore) : toolName;
    }

    /**
     * 拼进检索提示词的那一段：告诉模型这个目录里<b>到底有哪些能力</b>，以及怎么用
     * {@code categoryFilter} 收窄到一组。
     *
     * <p>它同时承担两件事，别混起来：
     * <ol>
     *   <li><b>事实</b>——这些组存在。少列一组，模型就可能把"没列"读成"没有"，然后对着一个
     *       其实能查的领域说"做不到"；</li>
     *   <li><b>用法</b>——收窄只对 ≥2 个工具的组有意义。单件工具（{@code Read}、{@code bmi_calc}）
     *       收窄等于没证，但"它存在"同样是事实，所以照样点名。</li>
     * </ol>
     *
     * <p><b>这里不设上限</b>。2026-11-28 之前有一道 {@code PROMPT_LIMIT = 10} 的截断，按字母序
     * 砍掉末尾几组：{@code weather} / {@code tv} 正好落在砍线外面，句子又读起来像穷举，模型据此
     * 判定"这个部署没有天气能力"，五次问天气零次检索。教训不是"上限该调大"，而是<b>任何上限都会
     * 有一组落在外面，而"落在外面"这件事对模型是不可见的</b>。所以宁可长一点：组多说明这个部署
     * 能力强，那正是模型该知道的事。真嫌长，解法是把 {@code categories} 配好、让组更少更粗，
     * 不是把清单砍一截。
     *
     * <p>组按工具数降序、同数按名字升序排：大组更能代表这个部署的业务面，也避免字母序把最后一个
     * 字母开头的组永远排在末尾。
     *
     * @param toolNames 这个目录里的全部工具名（不是本轮可见的那几个）
     * @return 提示词片段；没有任何成形内容时返回空串
     */
    public String promptLine(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "";
        }
        Map<String, List<String>> members = new TreeMap<>();
        for (String toolName : toolNames) {
            String category = categoryOf(toolName);
            if (!category.isEmpty()) {
                members.computeIfAbsent(category, ignored -> new ArrayList<>()).add(toolName);
            }
        }
        if (members.isEmpty()) {
            return "";
        }

        List<Map.Entry<String, List<String>>> groups = new ArrayList<>();
        List<String> singles = new ArrayList<>();
        members.forEach((category, names) -> {
            if (names.size() >= MIN_GROUP_SIZE) {
                groups.add(Map.entry(category, List.copyOf(names)));
            } else {
                // 单件工具：点名的是工具名而不是推导出来的组名。模型要的是"能查什么"，
                // 前缀推出来的那个词（air、bmi）本身不是它能调用的东西。
                singles.add(names.get(0));
            }
        });
        groups.sort(Comparator.<Map.Entry<String, List<String>>>comparingInt(entry -> entry.getValue().size())
                .reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder text = new StringBuilder();
        text.append("这个目录这一刻有 ").append(toolNames.size()).append(" 个工具");
        if (!groups.isEmpty()) {
            text.append("，按能力分成 ").append(groups.size()).append(" 组：\n");
            List<String> parts = new ArrayList<>();
            groups.forEach(entry -> parts.add(entry.getKey() + "(" + entry.getValue().size() + ")"));
            text.append(String.join(" / ", parts)).append("\n");
        }
        if (!singles.isEmpty()) {
            text.append(groups.isEmpty() ? "，其中 " : "另有 ").append(singles.size())
                    .append(" 个工具各自成一组：").append(String.join(" / ", singles)).append("。\n");
        }
        text.append("要收窄就把 categoryFilter 填成组名（只有 1 个工具的组收窄没有意义）；")
                .append("拿不准属于哪组就别填，直接按能力描述查。\n");
        return text.toString();
    }
}
