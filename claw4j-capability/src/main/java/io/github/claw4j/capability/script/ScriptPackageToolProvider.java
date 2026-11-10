package io.github.claw4j.capability.script;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本层的工具来源：包目录里有几个包，模型就多几个工具。
 *
 * <p>权限登记的形状就是这一层的边界：动作是 {@link #SCRIPT_ACTION}，表清单是空的。表空着不是
 * "不需要权限"，而是"它一张业务表都碰不到"；这两种情况在 {@link ToolPermissionRegistry} 上
 * 长得一样，区别只在有没有人显式登记过。没登记的工具一律按拒绝处理（ADR-0007）。
 *
 * <p>动作不取 {@code read}：只读角色能查数是"只读"的本意，但能执行脚本就不是了——脚本可以写文件、
 * 可以往外连。于是"能不能跑脚本"是一个独立动作，角色的授权表里不写它，就谁都跑不了。
 *
 * <p>清单是现取的（06 号票）：人审通过的包在应用跑起来之后才进清单，快照会让它"注册了但模型
 * 看不见"，而那种失效没有任何报错。权限表同样现取，否则新包会出现在清单里却在鉴权那一步被拒。
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

    /**
     * 按包清单建工具来源，清单与执行入口都是新建的。
     *
     * @param packages 启动时读出来的包
     */
    public ScriptPackageToolProvider(List<ScriptPackage> packages) {
        this(new ScriptPackageRegistry(packages), new ScriptPackageRunner(packages));
    }

    /**
     * 按包清单建工具来源，执行入口由调用方给定。
     *
     * @param packages 启动时读出来的包
     * @param runner 执行这些包的入口
     */
    public ScriptPackageToolProvider(List<ScriptPackage> packages, ScriptPackageRunner runner) {
        this(registryOf(packages, runner), runner);
    }

    /**
     * 用现成的清单与执行入口建工具来源，执行留痕不接。
     *
     * @param registry 包清单
     * @param runner 执行入口
     */
    public ScriptPackageToolProvider(ScriptPackageRegistry registry, ScriptPackageRunner runner) {
        this(registry, runner, ScriptExecutionListener.NO_OP);
    }

    /**
     * 用现成的清单与执行入口建工具来源。
     *
     * <p>构造时把清单的 {@code onRegister} 挂到 runner 上：注册一个包与建它的执行后端
     * 必须是同一件事，否则新包"清单里有、跑的时候说没有这个包"。
     *
     * @param registry 包清单
     * @param runner 执行入口
     * @param listener 执行结果的观察者
     */
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

    /**
     * 这一层登记的权限：动作是跑脚本，表一张都没有。
     *
     * @return 工具名到权限的映射，每次调用按当前清单重新生成
     */
    public Map<String, ToolPermission> permissions() {
        Map<String, ToolPermission> declared = new LinkedHashMap<>();
        for (ScriptPackage pkg : registry.packages()) {
            declared.put(pkg.name(), ToolPermission.of(SCRIPT_ACTION));
        }
        return Map.copyOf(declared);
    }

    /**
     * 当前清单里的工具名，按登记顺序。
     *
     * @return 工具名的列表
     */
    public List<String> toolNames() {
        return registry.packages().stream().map(ScriptPackage::name).toList();
    }
}
