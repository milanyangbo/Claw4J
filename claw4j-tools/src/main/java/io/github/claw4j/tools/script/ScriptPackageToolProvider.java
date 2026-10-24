package io.github.claw4j.tools.script;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

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
 *
 * <p><b>清单是现取的，不是构造时定下来的</b>（06 号票）：人审通过的包是在应用
 * 跑起来之后才进清单的，快照式的清单会让它"注册了但模型看不见"，
 * 而那种失效没有任何报错——模型只是手上没有这个工具。
 * 权限表同样现取，否则新包会出现在清单里却在鉴权那一步被拒
 * （"没登记"与"不许用"在模型那边看来是同一句拒绝）。
 */
public final class ScriptPackageToolProvider implements ToolCallbackProvider {

    /**
     * 跑脚本这个动作名。角色的授权表里要写这一条才会被放行；
     * 与 {@code querySales} 的 {@code read} 是两个口径（ADR-0007）。
     */
    public static final String SCRIPT_ACTION = "run_script";

    private final ScriptPackageRegistry registry;

    private final ScriptPackageRunner runner;

    private final ScriptExecutionListener listener;

    public ScriptPackageToolProvider(List<ScriptPackage> packages) {
        this(new ScriptPackageRegistry(packages), new ScriptPackageRunner(packages));
    }

    public ScriptPackageToolProvider(List<ScriptPackage> packages, ScriptPackageRunner runner) {
        this(registryOf(packages, runner), runner);
    }

    public ScriptPackageToolProvider(ScriptPackageRegistry registry, ScriptPackageRunner runner) {
        this(registry, runner, ScriptExecutionListener.NO_OP);
    }

    public ScriptPackageToolProvider(ScriptPackageRegistry registry, ScriptPackageRunner runner,
                                     ScriptExecutionListener listener) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.listener = Objects.requireNonNull(listener, "listener");
        // 清单里新增的包要能在 runner 里跑起来：注册与建后端必须是同一件事。
        registry.onRegister(runner::register);
    }

    private static ScriptPackageRegistry registryOf(List<ScriptPackage> packages, ScriptPackageRunner runner) {
        ScriptPackageRegistry registry = new ScriptPackageRegistry(List.of());
        for (ScriptPackage pkg : packages) {
            registry.register(pkg);
        }
        Objects.requireNonNull(runner, "runner");
        return registry;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return registry.packages().stream()
                .map(pkg -> (ToolCallback) new ScriptPackageToolCallback(pkg, runner, listener))
                .toArray(ToolCallback[]::new);
    }

    /** 这一层登记的权限：动作是跑脚本，表一张都没有。 */
    public Map<String, ToolPermission> permissions() {
        Map<String, ToolPermission> declared = new LinkedHashMap<>();
        for (ScriptPackage pkg : registry.packages()) {
            declared.put(pkg.name(), ToolPermission.of(SCRIPT_ACTION));
        }
        return Map.copyOf(declared);
    }

    public List<String> toolNames() {
        return registry.packages().stream().map(ScriptPackage::name).toList();
    }
}
