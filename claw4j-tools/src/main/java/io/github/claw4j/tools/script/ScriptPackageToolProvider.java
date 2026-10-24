package io.github.claw4j.tools.script;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本层这一层的工具来源：包目录里有几个包，模型就多几个工具。
 *
 * <p><b>权限登记的形状就是"这一层的边界"</b>：动作是 {@link #SCRIPT_ACTION}、
 * 表是**空的**。表空着不是"这个工具不需要权限"，而是"它一张业务表都碰不到"——
 * 这两句话在 {@link ToolPermissionRegistry} 上长得一模一样，区别只在于
 * 有没有人显式登记过它。没登记过的工具一律按拒绝处理（ADR-0007），
 * 所以这一张表必须存在，且必须由这一层自己声明。
 *
 * <p>为什么动作不是 {@code read}：只读角色能查数是"只读"的本意，
 * 但只读角色能<b>执行脚本</b>就不是了——脚本可以写文件、可以往外连，
 * 那是比读一张表宽得多的口子。于是"能不能跑脚本"是一个独立的动作，
 * 角色的授权表里不写它，就谁都跑不了。
 */
public final class ScriptPackageToolProvider implements ToolCallbackProvider {

    /**
     * 跑脚本这个动作名。角色的授权表里要写这一条才会被放行；
     * 与 {@code querySales} 的 {@code read} 是两个口径（ADR-0007）。
     */
    public static final String SCRIPT_ACTION = "run_script";

    private final List<ToolCallback> callbacks;

    private final Map<String, ToolPermission> permissions;

    public ScriptPackageToolProvider(List<ScriptPackage> packages) {
        this(packages, new ScriptPackageRunner(packages));
    }

    public ScriptPackageToolProvider(List<ScriptPackage> packages, ScriptPackageRunner runner) {
        Objects.requireNonNull(packages, "packages");
        Objects.requireNonNull(runner, "runner");
        Map<String, ToolPermission> declared = new LinkedHashMap<>();
        List<ToolCallback> collected = new ArrayList<>();
        for (ScriptPackage pkg : packages) {
            collected.add(new ScriptPackageToolCallback(pkg, runner));
            declared.put(pkg.name(), ToolPermission.of(SCRIPT_ACTION));
        }
        this.callbacks = List.copyOf(collected);
        this.permissions = Map.copyOf(declared);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.toArray(new ToolCallback[0]);
    }

    /** 这一层登记的权限：动作是跑脚本，表一张都没有。 */
    public Map<String, ToolPermission> permissions() {
        return permissions;
    }

    public List<String> toolNames() {
        return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
    }
}
