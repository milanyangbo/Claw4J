package io.github.claw4j.domain.principal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code group:*} 简写：把一个组名展开成具体的工具名（ADR-0030 裁决一）。
 *
 * <p>照 OpenClaw 的简写表，但只保留有对位的六个。它的表里还有 {@code group:memory}、
 * {@code group:sessions}、{@code group:media}、{@code group:ui}、{@code group:nodes}、
 * {@code group:messaging}、{@code group:automation}——我们没有对位的工具，不凭空造组名。
 *
 * <p>两种组性质不同，别混：
 * <ul>
 *   <li><b>静态组</b>（{@code fs} / {@code runtime} / {@code web} / {@code agents}）：成员是内置工具名的
 *       字面量。工具改名时要同步这里，漏改的表现是"组展开成一个不存在的名字"，进启动日志的
 *       {@code unused} 告警——刻意选的失败姿势，不是静默失效。</li>
 *   <li><b>动态组</b>（{@code plugins} / {@code openclaw}）：成员要到运行时才知道。
 *       {@code group:plugins} 是所有**外部来源**的工具（业务模块 + MCP），
 *       {@code group:openclaw} 是其余内置工具。两者互补，并集是全部工具。</li>
 * </ul>
 *
 * <p>为什么 {@code group:openclaw} 不写死：它的对位是"内置核心工具"，而我们往里加一个内置工具
 * 时不该同时改这里。用"清单减去外部工具"表达，加工具不用改第二处。
 */
public final class ToolGroups {

    /** 文件类：读、找、写、改。 */
    public static final String FS = "group:fs";

    /** 跑命令：起、读输出、杀。 */
    public static final String RUNTIME = "group:runtime";

    /** 出网：抓页面、搜。 */
    public static final String WEB = "group:web";

    /** 子 Agent 与计划。 */
    public static final String AGENTS = "group:agents";

    /** 外部来源的工具：业务模块 + MCP。动态。 */
    public static final String PLUGINS = "group:plugins";

    /** 内置工具（不含外部来源）。动态。 */
    public static final String OPENCLAW = "group:openclaw";

    /**
     * 静态组的成员。
     *
     * <p>名字用字面量而不是引用各模块的常量：这个类在 {@code claw4j-core}，引用 {@code claw4j-capability}
     * 会反向依赖。漂移由 {@code ToolProfile#select} 把"展开出来但清单里没有"的名字报进日志兜。
     */
    private static final Map<String, List<String>> STATIC = Map.of(
            FS, List.of("Read", "Grep", "Glob", "Write", "Edit"),
            RUNTIME, List.of("Bash", "BashOutput", "KillShell"),
            WEB, List.of("WebFetch", "WebSearch"),
            AGENTS, List.of("Task", "TodoWrite"));

    /** 动态组，展开要外部工具清单与全量清单。 */
    private static final Set<String> DYNAMIC = Set.of(PLUGINS, OPENCLAW);

    private ToolGroups() {
    }

    /**
     * 这个是不是 {@code group:*} 形式的组名。
     *
     * @param name 待判的名字
     * @return 是组名时为 {@code true}
     */
    public static boolean isGroup(String name) {
        return name != null && name.startsWith("group:");
    }

    /**
     * 已知的组名。
     *
     * @return 全部可用的组名
     */
    public static Set<String> knownGroups() {
        Set<String> known = new LinkedHashSet<>(STATIC.keySet());
        known.addAll(DYNAMIC);
        return Set.copyOf(known);
    }

    /**
     * 展开一个组名。
     *
     * @param group         组名；不是已知组时返回空集
     * @param allToolNames  清单里的全部工具名（{@code group:openclaw} 要用）
     * @param externalNames 外部来源的工具名（{@code group:plugins} 要用）
     * @return 这个组包含的工具名；未知组为空集
     */
    public static Set<String> members(String group, Set<String> allToolNames, Set<String> externalNames) {
        if (group == null) {
            return Set.of();
        }
        List<String> fixed = STATIC.get(group);
        if (fixed != null) {
            return Set.copyOf(fixed);
        }
        if (PLUGINS.equals(group)) {
            return Set.copyOf(externalNames);
        }
        if (OPENCLAW.equals(group)) {
            Set<String> builtin = new LinkedHashSet<>(allToolNames);
            builtin.removeAll(externalNames);
            return Set.copyOf(builtin);
        }
        return Set.of();
    }

    /**
     * 把一组名字里的 {@code group:*} 全部展开，非组名原样保留。
     *
     * @param names         可能含组名的名字集合
     * @param allToolNames  清单里的全部工具名
     * @param externalNames 外部来源的工具名
     * @return 展开后的名字集合，保持输入顺序
     */
    public static Set<String> expandAll(Set<String> names, Set<String> allToolNames, Set<String> externalNames) {
        Set<String> expanded = new LinkedHashSet<>();
        if (names == null) {
            return expanded;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (isGroup(name)) {
                expanded.addAll(members(name, allToolNames, externalNames));
            } else {
                expanded.add(name);
            }
        }
        return expanded;
    }
}
