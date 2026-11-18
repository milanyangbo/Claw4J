package io.github.claw4j.capability.script;

import io.github.claw4j.capability.untrusted.UntrustedToolCallback;
import io.github.claw4j.domain.principal.ToolFilter;
import io.github.claw4j.domain.untrusted.ExternalContent;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 脚本层的工具来源：包目录里有几个包，模型就多几个工具。
 *
 * <p>这一层的边界现在只由两件东西表达（ADR-0030）：工具清单（{@code tool-filter} 与画像决定
 * "这一轮给不给看"）与执行闸（脚本跑起来之后碰什么由沙箱与审批管）。标"跑脚本"这个动作
 * 需要什么权限的登记表在票 04 整块下线——那是业务系统的授权问题，属于第二步。
 *
 * <p>清单是现取的（06 号票）：人审通过的包在应用跑起来之后才进清单，快照会让它"注册了但模型
 * 看不见"，而那种失效没有任何报错。
 */
public final class ScriptPackageToolProvider implements ToolCallbackProvider {

    /**
     * 跑脚本这个动作名。角色的授权表里要写这一条才会被放行；
     * 与 {@code querySales} 的 {@code read} 是两个口径（ADR-0007）。
     */

    private final ScriptPackageRegistry registry;

    private final ScriptPackageRunner runner;

    private final ScriptExecutionListener listener;

    /** 哪些包**存在**（票 07）。被它排掉的包不会变成工具，于是也没有权限与画像登记。 */
    private final ToolFilter toolFilter;

    /**
     * 按包清单建工具来源，清单与执行入口都是新建的。
     *
     * @param packages 启动时读出来的包
     */
    public ScriptPackageToolProvider(List<ScriptPackage> packages) {
        this(new ScriptPackageRegistry(packages), new ScriptPackageRunner(packages));
    }

    /**
     * 按包清单建工具来源，并指定哪些包存在。
     *
     * <p>用静态工厂而不是构造器：清单要挂到执行入口上（{@code onRegister}），两者必须是**同一个**
     * runner 对象。写成 {@code this(...)} 的话只能传一份表达式，很容易顺手 new 出两个，
     * 那时候表现是"注册了但跑不起来"，离这里隔着一整条链路。
     *
     * @param packages 启动时读出来的包
     * @param toolFilter 存在性过滤；{@link ToolFilter#none()} 表示全留
     * @return 工具来源
     */
    public static ScriptPackageToolProvider of(List<ScriptPackage> packages, ToolFilter toolFilter) {
        ScriptPackageRunner runner = new ScriptPackageRunner(packages);
        return new ScriptPackageToolProvider(registryOf(packages, runner), runner,
                ScriptExecutionListener.NO_OP, toolFilter);
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
        this(registry, runner, listener, ToolFilter.none());
    }

    /**
     * 用现成的清单与执行入口建工具来源，并指定哪些包存在。
     *
     * @param registry 包清单
     * @param runner 执行入口
     * @param listener 执行结果的观察者
     * @param toolFilter 存在性过滤；被排掉的包不进清单、不进权限登记
     */
    public ScriptPackageToolProvider(ScriptPackageRegistry registry, ScriptPackageRunner runner,
                                     ScriptExecutionListener listener, ToolFilter toolFilter) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.toolFilter = Objects.requireNonNull(toolFilter, "toolFilter");
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
        return packages().stream()
                .map(pkg -> (ToolCallback) new ScriptPackageToolCallback(pkg, runner, listener))
                // 脚本的输出来自"脚本读了什么"，与网页同一种不可信：包边界、带警告句。
                // 每轮现包，不在构造时拍快照——包列表是现取的（见类注释）。
                .map(callback -> UntrustedToolCallback.wrapping(callback,
                        ExternalContent.Source.SCRIPT, true))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 当前清单里的工具名，按登记顺序。
     *
     * <p>取的是**过滤之后**的名字（票 07）：被 {@code tool-filter} 排掉的包不是一个工具，
     * 档位与权限登记都不该再点它的名——否则 {@code allow} 里写它看起来像"能加回来"，
     * 而它根本没有成为工具。
     *
     * @return 工具名的列表
     */
    public List<String> toolNames() {
        return packages().stream().map(ScriptPackage::name).toList();
    }

    /** 过滤之后的包：三个出口（callbacks / 权限 / 名字）都从这里取，少一处就会不一致。 */
    private List<ScriptPackage> packages() {
        return registry.packages().stream()
                .filter(pkg -> toolFilter.matches(pkg.name()))
                .toList();
    }
}
