package io.github.claw4j.capability.script;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 脚本包的**账本**：启动时从目录读进来，之后只有人审通过的包能被加进来。
 *
 * <p><b>2026-11-18 之后它不再决定"谁看得见什么"</b>：脚本包退化成技能，
 * {@code run_script} 工具与它的工具清单来源一起删掉了。现在这个账本只回答一件事——
 * 生成器那层"这个名字是不是已经被占了、这份内容是不是已经发布过"。
 * 注册进来**不会**让模型多出任何能力，这是票 02 留下的缺口（
 * {@code .scratch/oss-alignment/issues/03-generator-emits-skills.md}）。
 *
 * <p>不靠"重启时重新扫目录"：重启把"人点了一下通过"变成一次发布，而暂存区里那份脚本在这期间
 * 可以被重新生成，扫描式的读法会把"审的是哪一版"交给文件系统，文件系统答不上来
 * （人审比对的是脚本摘要，见 generator 包）。
 */
public final class ScriptPackageRegistry {

    private final List<ScriptPackage> packages = new CopyOnWriteArrayList<>();

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
     * 人审通过后把它加进账本。
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
