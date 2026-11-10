package io.github.claw4j.capability.script;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 脚本包的清单：启动时从目录读进来，之后只有人审通过的包能被加进来。
 *
 * <p>"未人审的脚本不在工具清单里"这条边界落在这里：工具清单由 {@link ScriptPackageToolProvider}
 * 从本注册表现取，于是"注册"与"不注册"是同一件事的两面——生成器产出的脚本落在暂存区，
 * 有人签了字之后清单才多出一项，模型才看得见它。
 *
 * <p>不靠"重启时重新扫目录"：重启把"人点了一下通过"变成一次发布，而暂存区里那份脚本在这期间
 * 可以被重新生成，扫描式的读法会把"审的是哪一版"交给文件系统，文件系统答不上来
 * （人审比对的是脚本摘要，见 generator 包）。
 *
 * <p>{@link #onRegister} 给 {@link ScriptPackageRunner} 用：执行后端按包建、建一次缓存，
 * 新包进来要有人把它接上，否则"清单里有它、跑的时候说没有这个包"。
 */
public final class ScriptPackageRegistry {

    private final List<ScriptPackage> packages = new CopyOnWriteArrayList<>();

    private final List<Consumer<ScriptPackage>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 用一份初始清单建注册表。
     *
     * @param initial 启动时读出来的包
     */
    public ScriptPackageRegistry(List<ScriptPackage> initial) {
        Objects.requireNonNull(initial, "initial");
        packages.addAll(initial);
    }

    /**
     * 空清单，用于"包全部由运行时注册"的场合。
     *
     * @return 空的注册表
     */
    public static ScriptPackageRegistry empty() {
        return new ScriptPackageRegistry(List.of());
    }

    /**
     * 新包进来时要被通知的一方（runner 建后端）。
     *
     * @param listener 收到新包的回调
     */
    public void onRegister(Consumer<ScriptPackage> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 人审通过后把它加进清单，然后通知所有监听者。
     *
     * <p>重名直接失败：同名工具会让模型分不清该调哪个，覆盖则让"人审通过的是这一版"说不清。
     *
     * @param pkg 人审通过的包
     * @throws IllegalStateException 清单里已经有同名的包
     */
    public void register(ScriptPackage pkg) {
        Objects.requireNonNull(pkg, "pkg");
        if (contains(pkg.name())) {
            throw new IllegalStateException("已经有一个叫 " + pkg.name() + " 的脚本包："
                    + "同名工具会让模型分不清该调哪个，这里不去重而是直接失败");
        }
        packages.add(pkg);
        for (Consumer<ScriptPackage> listener : listeners) {
            listener.accept(pkg);
        }
    }

    /**
     * 清单里是否有这个名字。
     *
     * @param name 包名
     * @return 有则 {@code true}
     */
    public boolean contains(String name) {
        return packages.stream().anyMatch(pkg -> pkg.name().equals(name));
    }

    /**
     * 当前清单，按登记顺序。
     *
     * @return 包的只读快照
     */
    public List<ScriptPackage> packages() {
        return List.copyOf(packages);
    }
}
