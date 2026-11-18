package io.github.claw4j.domain.principal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具名 → 能力画像集合的登记表。由提供工具的模块实现，由清单组装处消费（P09 / ADR-0030）。
 *
 * <p>与"这个 provider 提供哪些工具"同一份清单：只有工具自己知道它属于哪一档。
 * 名字清单由 {@code toolNames()} 给（票 07），不借别的表——权限登记表在票 04 已经整块下线。
 * 反过来让装配处维护一张"工具名 → 画像"的总表，就等于把每个模块的工具清单抄了一份在别处，
 * 加一个工具要改两个模块，而漏改的表现是启动失败并点名（不是静默放行，见
 * {@link ToolProfile#select}）——这是刻意选的失败姿势。
 *
 * <p>查不到即 {@link Optional#empty()}，消费方必须按配置错误处理：一个没声明画像的工具，
 * 说明没人判断过它该不该出现在这个部署里，而"默认出现"正是本机制要避免的那件事。
 *
 * <p>另有一条 {@link #externalToolNames()}：这个模块提供的外部工具（业务工具、MCP 工具）。
 * 它喂给 {@code group:plugins} 的展开——那个组名要能把"所有外部来源的工具"一次点名，
 * 而外部工具的名字要到运行时才知道，静态表列不出来。
 */
public interface ToolTierRegistry {

    /**
     * 查一个工具声明在哪些画像里。
     *
     * @param toolName 工具名
     * @return 声明的画像集合；没登记时为空
     */
    Optional<Set<ToolTier>> profilesFor(String toolName);

    /**
     * 这个模块提供的外部工具名。
     *
     * <p>默认空：内置工具不属于外部来源。外部工具由业务模块与 MCP provider 覆盖这个方法，
     * {@code group:plugins} 的展开取所有登记表的并集。
     *
     * @return 外部工具名，默认空集
     */
    default Set<String> externalToolNames() {
        return Set.of();
    }

    /**
     * 用一张固定映射表实现。登记之后不可变，避免运行期被人塞进去一条。
     *
     * @param profiles 工具名到画像集合的映射
     * @return 只读的登记表
     */
    static ToolTierRegistry of(Map<String, ? extends Set<ToolTier>> profiles) {
        Map<String, Set<ToolTier>> declared = new LinkedHashMap<>();
        profiles.forEach((name, set) -> declared.put(name, freeze(set)));
        Map<String, Set<ToolTier>> immutable = Map.copyOf(declared);
        return toolName -> Optional.ofNullable(immutable.get(toolName));
    }

    /**
     * 一组工具同属这几个画像。用于"名字是同一份清单里的那几个"的场合：MCP 的工具名要到
     * 运行时才知道，逐个写死在这里是不可能的。
     *
     * <p>调用方应当把它自己导出的那份名字传进来，不要写成"任何名字都算"——那种实现会让
     * {@link ToolProfile#select} 的未声明校验永远通过，而校验失效的表现是"新加的工具默默出现
     * 在不该出现的档里"。
     *
     * @param toolNames 这组工具的名字
     * @param profiles  它们共同的画像
     * @return 只读的登记表
     */
    static ToolTierRegistry sameFor(Collection<String> toolNames, ToolTier... profiles) {
        Set<ToolTier> set = freeze(Set.of(profiles));
        Map<String, Set<ToolTier>> declared = new LinkedHashMap<>();
        names(toolNames).forEach(name -> declared.put(name, set));
        return of(declared);
    }

    /**
     * 一组工具属于**全部**画像：地基工具走这个。
     *
     * <p>它等价于 {@code sameFor(names, ToolTier.all())}，单列一个名字是因为"这一组在哪个画像里
     * 都不该少"是登记时最常见的一句话，写成变长参数要点四次反而看不出意图。
     *
     * @param toolNames 这组工具的名字
     * @return 只读的登记表
     */
    static ToolTierRegistry allProfilesFor(Collection<String> toolNames) {
        Map<String, Set<ToolTier>> declared = new LinkedHashMap<>();
        Set<ToolTier> all = ToolTier.all();
        names(toolNames).forEach(name -> declared.put(name, all));
        return of(declared);
    }

    /**
     * 一组**外部**工具同属这几个画像，同时登记进 {@link #externalToolNames()}。
     *
     * <p>业务模块（{@code querySales}）与 MCP 工具走这个工厂：它们既要知道自己在哪几个画像里，
     * 也要能被 {@code group:plugins} 一次点名。两件事必须同源，否则"档里看得见、
     * {@code deny: [group:plugins]} 关不掉"会在某次加工具之后悄悄出现。
     *
     * @param toolNames 这组外部工具的名字
     * @param profiles  它们共同的画像，通常是 {@code {CODING, MESSAGING}}
     * @return 只读的登记表
     */
    static ToolTierRegistry external(Collection<String> toolNames, ToolTier... profiles) {
        Set<ToolTier> set = freeze(Set.of(profiles));
        Set<String> external = Set.copyOf(names(toolNames));
        Map<String, Set<ToolTier>> declared = new LinkedHashMap<>();
        external.forEach(name -> declared.put(name, set));
        Map<String, Set<ToolTier>> immutable = Map.copyOf(declared);
        return new ToolTierRegistry() {

            @Override
            public Optional<Set<ToolTier>> profilesFor(String toolName) {
                return Optional.ofNullable(immutable.get(toolName));
            }

            @Override
            public Set<String> externalToolNames() {
                return external;
            }
        };
    }

    private static Set<String> names(Collection<String> toolNames) {
        Set<String> names = new LinkedHashSet<>();
        if (toolNames != null) {
            toolNames.stream().filter(n -> n != null && !n.isBlank()).forEach(names::add);
        }
        return names;
    }

    private static Set<ToolTier> freeze(Set<ToolTier> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "工具画像集合不能为空：一个不属于任何画像的工具只有 full（不筛）才看得见，"
                            + "写空集等于\"只有把整个部署放开才出现\"——那不是一个能被审阅的决定。");
        }
        return Set.copyOf(profiles);
    }
}
