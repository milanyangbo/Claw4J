package io.github.claw4j.domain.principal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具存不存在这一层的开关（ADR-0030 裁决二）：{@code include} 非空则必须命中，
 * {@code exclude} 命中即排除。条目支持 {@code *} 通配，按<b>整串</b>匹配。
 *
 * <p>它回答的问题与另外两层不同，三层别混：
 *
 * <ul>
 *   <li>存在性（本类）：这个工具<b>有没有</b>。被排掉的工具不会变成 {@code ToolCallback}，
 *       于是它不进清单、不进权限登记、也不在 {@code toolNames()} 里。</li>
 *   <li>画像（{@link ToolProfile}）：这<b>类</b>工具允不允许。换档或 {@code alsoAllow} 能把它要回来。</li>
 *   <li>策略（{@link ToolPolicyLayer}）：具体哪几个<b>可见</b>。改 {@code allow} / {@code deny} 能要回来。</li>
 * </ul>
 *
 * <p>顺序决定了"能不能要回来"：存在性在第一层，所以<b>被它排掉的工具，{@code allow} 加不回来</b>。
 * 这是刻意的——"这个 server 的这个工具我们根本不要它进模型的手里"与"这一轮先不给它看"是两件事，
 * 用同一个开关表达会让后者顺手变成前者。形状照 OpenClaw 的
 * {@code mcp.servers.<名字>.toolFilter}：*filter discovered MCP tools before they become OpenClaw tools*。
 *
 * <p>{@code include} 与 {@code exclude} 同时命中一个名字时以 {@code exclude} 为准：
 * 写两行互相矛盾的配置，结果是"不给"，不是"给"——排除是保守的那一侧。
 *
 * @param include 要留下的名字或通配；空表示"不按名字挑，全留"
 * @param exclude 要排掉的名字或通配；命中即排除，不看 {@code include} 是否留了它
 */
public record ToolFilter(List<String> include, List<String> exclude) {

    /** 名字里唯一认的通配符。{@code ?} 不认：它的 {@code toolFilter} 也只有这一个。 */
    private static final char WILDCARD = '*';

    public ToolFilter {
        include = normalize(include);
        exclude = normalize(exclude);
    }

    /**
     * 什么都不排的那一份。
     *
     * <p>没配 {@code tool-filter} 时用它，而不是到处写 {@code null}：调用点一条
     * {@code filter.matches(name)} 就够，不必每处先判空——判空漏一处，那一处的过滤就静默失效。
     *
     * @return 空过滤
     */
    public static ToolFilter none() {
        return new ToolFilter(List.of(), List.of());
    }

    /**
     * 一个名字要不要留下。
     *
     * @param toolName 工具名，用 provider 里的原名（MCP 就是 server 导出的那个，不加前缀）
     * @return true 表示留下
     */
    public boolean matches(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (!include.isEmpty() && include.stream().noneMatch(pattern -> globMatches(pattern, toolName))) {
            return false;
        }
        return exclude.stream().noneMatch(pattern -> globMatches(pattern, toolName));
    }

    /**
     * 这一份过滤是不是什么都没做。
     *
     * <p>启动日志与"过滤后一个都不剩"的判断要分开这两种情况：空 {@code tool-filter} 是常态，
     * 而排空了是配置写错，报同一句话会让人去查一个不存在的问题。
     *
     * @return true 表示两个清单都空
     */
    public boolean filtersNothing() {
        return include.isEmpty() && exclude.isEmpty();
    }

    /**
     * 整串匹配，不是包含。
     *
     * <p>{@code runtime*} 匹配 {@code runtimeSnapshot}、不匹配 {@code xcronNext}；反过来
     * {@code *Snapshot} 也不匹配 {@code runtimeSnapshotX}。用 {@code contains} 实现会让
     * {@code include: [add]} 顺手留下 {@code address_book}——那种错在配置里看不出来，
     * 表现是"我明明排掉了它，它还在"。
     *
     * @param pattern 名字或带 {@code *} 的通配
     * @param name 待判的名字
     * @return true 表示命中
     */
    static boolean globMatches(String pattern, String name) {
        if (pattern.indexOf(WILDCARD) < 0) {
            return pattern.equals(name);
        }
        return name.matches(regexOf(pattern));
    }

    /**
     * 通配 → 正则：字面量分段 {@code Pattern.quote}，每段之间是 {@code .*}。
     *
     * <p>不自己拼字符类：工具名里可能出现正则元字符（{@code $}、{@code (}），
     * 拼错一处就是一个永远匹配不上的配置，而它看起来完全正常。
     */
    private static String regexOf(String pattern) {
        StringBuilder regex = new StringBuilder();
        int from = 0;
        for (int index = 0; index < pattern.length(); index++) {
            if (pattern.charAt(index) == WILDCARD) {
                if (index > from) {
                    regex.append(Pattern.quote(pattern.substring(from, index)));
                }
                regex.append(".*");
                from = index + 1;
            }
        }
        if (from < pattern.length()) {
            regex.append(Pattern.quote(pattern.substring(from)));
        }
        return regex.toString();
    }

    private static List<String> normalize(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<String> trimmed = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            trimmed.add(pattern.trim());
        }
        return List.copyOf(trimmed);
    }
}
