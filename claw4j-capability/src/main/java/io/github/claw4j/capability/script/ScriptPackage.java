package io.github.claw4j.capability.script;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个脚本包：{@code skills/<name>/} 下的可执行脚本（GLOSSARY）。
 *
 * <p>它给模型的东西只有三样——名字、一句描述、一个参数——脚本本体不进上下文。
 * 换来的是"写个脚本就能扩展"的敏捷性，代价是执行环境的可控性，
 * 所以这张票把代价那部分收进 {@link SandboxPolicy}。
 *
 * <p><b>构造即校验</b>，校验失败就是启动失败。四条约束分别有各自的理由：
 * <ul>
 *   <li><b>名字</b>：它会原样发给模型供应商当函数名，含空格或中文的请求会被 API 拒，
 *       而那种拒绝发生在联调现场，离"包名叫错了"这件事很远。
 *   <li><b>描述</b>：模型靠它决定"什么时候该调这个包"，没有描述的包等于
 *       一个没人知道什么时候用的工具——它不会报错，只会永远不被调用。
 *   <li><b>入口</b>：必须存在且落在包目录内（{@link SandboxPolicy#resolveInside}）。
 *   <li><b>超时</b>：必须在 {@link SandboxPolicy#MAX_TIMEOUT_MILLIS} 内。
 * </ul>
 *
 * @param name 工具名，直接就是模型看到的函数名
 * @param root 包目录，同时是执行时的工作目录与唯一可写的范围
 * @param entrypoint 入口脚本，相对 {@code root}
 * @param description 给模型看的规格说明
 * @param timeoutMillis 单次执行的墙上时钟上限
 */
public record ScriptPackage(String name, Path root, String entrypoint, String description, long timeoutMillis) {

    /** 包目录里没另外指定时的入口文件名。 */
    public static final String DEFAULT_ENTRYPOINT = "run.sh";

    /** 包目录里没另外指定时的超时。15 秒足够"问一句拿个结果"，也足够暴露一个跑飞了的脚本。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 15_000;

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ScriptPackage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(entrypoint, "entrypoint");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "脚本包名必须是 1-64 位的字母、数字、下划线或连字符，拿到的是 " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("脚本包 " + name + " 缺 description："
                    + "模型靠这句描述决定什么时候调它，没有描述的包永远不会被调用，而且不会报错");
        }
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("脚本包 " + name + " 的目录不存在或不是目录：" + root);
        }
        if (!Files.isRegularFile(SandboxPolicy.defaults().resolveInside(root, entrypoint))) {
            throw new IllegalStateException("脚本包 " + name + " 的入口 " + entrypoint + " 在目录 " + root + " 里不存在");
        }
        SandboxPolicy.defaults().requireTimeoutWithinCap(timeoutMillis);
    }

    /**
     * 名字是否合规格。
     *
     * <p>放在这里而不是让调用方各写一份正则，是因为"模型产出的包名"也要过同一道校验
     * （06 号票的生成器）：包名会原样发给模型供应商当函数名，
     * 两处校验不一致的表现是"生成时看着没问题、注册时启动失败"。
     */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public static ScriptPackage of(String name, Path root, String description) {
        return new ScriptPackage(name, root, DEFAULT_ENTRYPOINT, description, DEFAULT_TIMEOUT_MILLIS);
    }

    public static ScriptPackage of(String name, Path root, String entrypoint, String description,
                                   long timeoutMillis) {
        return new ScriptPackage(name, root, entrypoint, description, timeoutMillis);
    }

    /** 入口脚本的绝对路径。走 {@link SandboxPolicy#resolveInside}，于是它一定在沙箱内。 */
    public Path entrypointPath() {
        return SandboxPolicy.defaults().resolveInside(root, entrypoint);
    }
}
