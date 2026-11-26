package io.github.claw4j.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一条工具检索评测的 gold：用户怎么问、应当翻到哪个（哪几个）工具。
 *
 * <p>与 RAG 那份 {@link RetrievalGold} 分开，因为判据不同：那边判"答案的出处有没有被召回"（要绑句子、
 * 要处理跨块），这边判"工具名在不在前 k"——工具名是稳定的标识符，不需要锚点，也不存在跨块。
 * 合成一份会得到一个既绑句子又绑名字的记录，两种判据都不是它。
 *
 * <p><b>{@code goldTools} 允许为空</b>：那是"不该检索"的题（"你好""谢谢"）。空不是"没写"，
 * 是一个有意义的取值——索引给出任何候选都算噪声。为了不把这两件事混起来，空列表按"期望零候选"
 * 统计，进单独那一类，不进 recall 的平均。
 *
 * @param id         kebab-case，报告与报错靠它定位
 * @param question   问给检索器的那句话（用户真的会这么说，不点工具名）
 * @param category   分类，用来分开报分：base / business / zh-en / no-search
 * @param goldTools  期望翻到的工具名；空表示"不该有候选"
 */
public record ToolGold(String id, String question, String category, List<String> goldTools) {

    /** yaml 里认得的顶层键。多一个就是写错了（宁可当场报错，也别让打错的键被静默忽略）。 */
    private static final Set<String> KEYS = Set.of("id", "question", "category", "gold_tools");

    /** 认得的分类。写错分类不会报错但会让那一档悄悄少一条题，所以在这里收口。 */
    public static final Set<String> CATEGORIES = Set.of("base", "business", "zh-en", "no-search");

    public ToolGold {
        id = requireText(id, "gold.id");
        question = requireText(question, "gold.question");
        category = requireText(category, "gold.category");
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("gold " + id + " 的 category 是 " + category
                    + "，只认 " + CATEGORIES + "：分类写错会让那一档悄悄少一条题");
        }
        goldTools = goldTools == null ? List.of() : List.copyOf(goldTools);
    }

    /**
     * 从 YAML 解析出来的一条校验成 gold。
     *
     * <p>严格校验的理由与 {@link RetrievalGold#from} 同一条：打错一个键名不该变成"这条题没生效"，
     * 那种错会让分数虚高而没人发现。
     *
     * @param raw YAML 解析出来的一个条目
     * @return 校验过的 gold
     * @throws IllegalArgumentException 缺字段、键写错、分类不认识
     */
    public static ToolGold from(Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("gold 是一个空条目");
        }
        String where = "gold " + raw.get("id");
        for (String key : raw.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException(where + " 有不认识的键：" + key + "（认得的只有 " + KEYS + "）");
            }
        }
        Object value = raw.get("gold_tools");
        List<String> tools = new ArrayList<>();
        if (value != null) {
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException(where + " 的 gold_tools 应当是列表：" + value);
            }
            for (Object item : list) {
                tools.add(requireText(String.valueOf(item), where + " 的 gold_tools 项"));
            }
        }
        return new ToolGold(text(raw, "id"), text(raw, "question"), text(raw, "category"), tools);
    }

    private static String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new IllegalArgumentException("gold " + raw.get("id") + " 缺少 " + key);
        }
        return String.valueOf(value);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
