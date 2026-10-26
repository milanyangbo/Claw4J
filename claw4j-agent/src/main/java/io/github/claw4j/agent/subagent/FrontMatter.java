package io.github.claw4j.agent.subagent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一份 markdown 的 front matter 与正文。
 *
 * <p><b>为什么自己解析，不用社区那个 {@code MarkdownParser}</b>：它是逐行找 {@code key: value}，
 * 没有冒号的行整行丢掉。于是一份用 YAML 列表写 {@code tools} 的定义，解析结果是"这一节是空的"——
 * 而"空"在本项目里恰好是"继承全部工具"的意思。一个写坏的配置因此变成了权限放大，
 * 而且不报错、不告警，只是那个子 Agent 忽然能发邮件了。
 *
 * <p>这里的判据反过来：<b>解析不出东西就必须说出来</b>，绝不返回一个会被下游当成许可的空结果。
 * 具体到三条：
 * <ul>
 *   <li>列表项写成 {@code - querySales} 或 {@code querySales, lookup} 都收（示例文件用的是前者）。</li>
 *   <li>写了节名却没给出任何一项，交给调用方按"定义错误"处理——{@link #has} 就是为了让
 *       "没写这一节"与"写了但空着"能分开。</li>
 *   <li>既不是 {@code key: value} 也不是列表项的行（比如折叠写法的多行文本）直接失败：
 *       静默丢掉它，等于让一个人以为自己的配置生效了。</li>
 * </ul>
 */
final class FrontMatter {

    private static final String DELIMITER = "---";

    private final Map<String, String> values;
    private final Map<String, List<String>> items;
    private final String body;

    private FrontMatter(Map<String, String> values, Map<String, List<String>> items, String body) {
        this.values = Map.copyOf(values);
        this.items = items.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        this.body = body;
    }

    /**
     * @param listKeys 允许用 {@code - 名字} 逐行写的键；其余的键只认同行值，
     *                 这样"列表写在别的键下面"会当场报错而不是变成一段看起来正常的值
     * @param source   只用于报错时指明是哪一份定义
     */
    static FrontMatter parse(String markdown, Path source, Set<String> listKeys) {
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(listKeys, "listKeys");

        List<String> lines = List.of(markdown.split("\r?\n", -1));
        int opening = 0;
        while (opening < lines.size() && lines.get(opening).isBlank()) {
            opening++;
        }
        if (opening == lines.size() || !DELIMITER.equals(lines.get(opening).trim())) {
            // 没有 front matter：整份文件都是正文。键一个都没有，调用方要 name 时自然失败并说清缺什么。
            return new FrontMatter(Map.of(), Map.of(), markdown.trim());
        }

        int closing = opening + 1;
        while (closing < lines.size() && !DELIMITER.equals(lines.get(closing).trim())) {
            closing++;
        }
        if (closing == lines.size()) {
            throw new IllegalStateException("定义文件 " + source + " 的 front matter 没有收尾的 ---："
                    + "从第 " + (opening + 1) + " 行到文件末尾都被当成配置了。");
        }

        Map<String, String> values = new LinkedHashMap<>();
        Map<String, List<String>> items = new LinkedHashMap<>();
        String currentKey = null;
        for (int index = opening + 1; index < closing; index++) {
            String raw = lines.get(index);
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (isListItem(line) || isIndented(raw)) {
                if (currentKey == null) {
                    throw new IllegalStateException("定义文件 " + source + " 第 " + (index + 1) + " 行是列表项"
                            + "或续行，但它上面没有任何键：" + line);
                }
                if (!listKeys.contains(currentKey)) {
                    throw new IllegalStateException("定义文件 " + source + " 第 " + (index + 1) + " 行"
                            + "跟在上面的 " + currentKey + " 下面，但 " + currentKey + " 只能写在同一行里"
                            + "（字典里的列表键只有 " + String.join("、", listKeys.stream().sorted().toList())
                            + "）。写在一行里，别折行。");
                }
                String item = isListItem(line) ? line.substring(1).trim() : line;
                if (item.isEmpty()) {
                    throw new IllegalStateException("定义文件 " + source + " 第 " + (index + 1)
                            + " 行是空的列表项：" + currentKey + " 这一节里不能有空的 - 行。");
                }
                items.get(currentKey).add(item);
                continue;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException("定义文件 " + source + " 第 " + (index + 1) + " 行既不是"
                        + "key: value 也不是列表项：" + line);
            }
            currentKey = line.substring(0, colon).trim();
            values.put(currentKey, unquote(line.substring(colon + 1).trim()));
            items.put(currentKey, new ArrayList<>());
        }

        String body = String.join("\n", lines.subList(closing + 1, lines.size())).trim();
        return new FrontMatter(values, items, body);
    }

    /** 这一节到底写没写。用来把"没写"与"写了但空着"分开——两者的后果完全不同。 */
    boolean has(String key) {
        return values.containsKey(key);
    }

    /** 同行写的值。写了节名却没给值时是空串，不是 null。 */
    String value(String key) {
        return values.get(key);
    }

    /** {@code - 名字} 逐行写的那些项，按文件里的顺序。 */
    List<String> items(String key) {
        return List.copyOf(items.getOrDefault(key, List.of()));
    }

    String body() {
        return body;
    }

    private static boolean isListItem(String trimmedLine) {
        return trimmedLine.startsWith("-");
    }

    private static boolean isIndented(String rawLine) {
        return !rawLine.isEmpty() && Character.isWhitespace(rawLine.charAt(0));
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
