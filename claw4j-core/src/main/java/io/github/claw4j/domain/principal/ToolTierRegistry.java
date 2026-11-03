package io.github.claw4j.domain.principal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具名 → 能力档位的登记表。由**提供工具的模块**实现，由清单组装处消费（P09）。
 *
 * <p>与 {@link ToolPermissionRegistry} 同构，也同一个理由：只有工具自己知道它最低需要哪一档。
 * 反过来让装配处维护一张"工具名 → 档位"的总表，就等于把每个模块的工具清单抄了一份在别处，
 * 加一个工具要改两个模块，而漏改的表现是**启动失败并点名**（不是静默放行，见
 * {@link ToolProfile#select}）——这是刻意选的失败姿势。
 *
 * <p>查不到即 {@link Optional#empty()}，消费方必须按**配置错误**处理：
 * 一个没声明档位的工具，说明没人判断过它该不该出现在这个部署里，而"默认出现"正是
 * 本机制要避免的那件事。
 */
public interface ToolTierRegistry {

    Optional<ToolTier> tierFor(String toolName);

    /** 用一张固定映射表实现。登记之后不可变，避免运行期被人塞进去一条。 */
    static ToolTierRegistry of(Map<String, ToolTier> tiers) {
        Map<String, ToolTier> declared = Map.copyOf(tiers);
        return toolName -> Optional.ofNullable(declared.get(toolName));
    }

    /**
     * 一组工具同属一档。用于"名字是同一份清单里的那几个"的场合：
     * 脚本包与 MCP 的工具名要到运行时才知道，逐个写死在这里是不可能的。
     *
     * <p>调用方应当把**它自己导出的那份名字**传进来，不要写成"任何名字都算"——
     * 那种实现会让 {@link ToolProfile#select} 的未声明校验永远通过，
     * 而校验失效的表现是"新加的工具默默出现在不该出现的档里"。
     */
    static ToolTierRegistry sameFor(Collection<String> toolNames, ToolTier tier) {
        Map<String, ToolTier> declared = new LinkedHashMap<>();
        toolNames.forEach(name -> declared.put(name, tier));
        return of(declared);
    }
}
