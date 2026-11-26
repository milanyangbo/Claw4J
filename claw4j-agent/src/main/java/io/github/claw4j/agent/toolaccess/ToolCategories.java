package io.github.claw4j.agent.toolaccess;

import java.util.ArrayList;
import java.util.Collection;
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

    /** 提示词里最多列几组：列一屏组名比不列更糟，模型会开始猜。 */
    private static final int PROMPT_LIMIT = 10;

    /** 至少有几个工具才算"一组"。只有一个的那种分组没有收窄价值，列出来只是噪声。 */
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
     * 拼进检索提示词的那一行：告诉模型有哪些组可以收窄。
     *
     * <p>不列的话这个参数等于不存在——它的描述只有一句英文"Optional filter to narrow search"，
     * 模型不会自己想到去填。只列有意义的组（≥2 个工具），否则一屏都是单工具组。
     *
     * @param toolNames 这一轮的工具名
     * @return 一行说明；没有成形的组时返回空串
     */
    public String promptLine(Collection<String> toolNames) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String toolName : toolNames) {
            String category = categoryOf(toolName);
            if (!category.isEmpty()) {
                counts.merge(category, 1, Integer::sum);
            }
        }
        List<String> groups = new ArrayList<>();
        counts.forEach((category, count) -> {
            if (count >= MIN_GROUP_SIZE && groups.size() < PROMPT_LIMIT) {
                groups.add(category);
            }
        });
        if (groups.isEmpty()) {
            return "";
        }
        return "这些工具分成了几组，检索时可以带上 categoryFilter 收窄到一组：" + String.join(" / ", groups) + "。\n";
    }
}
