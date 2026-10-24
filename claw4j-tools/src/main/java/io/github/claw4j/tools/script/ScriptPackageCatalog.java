package io.github.claw4j.tools.script;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 从一个目录里读出有哪些脚本包：每个子目录一个包，包里用 {@code package.properties} 说明自己。
 *
 * <p>为什么是 properties 而不是 YAML / 自定义 front matter：这一层在 {@code claw4j-tools}，
 * 它不该为了读一份说明去引一个解析器。{@link Properties} 是 JDK 自带的，
 * 中文描述用 {@code InputStreamReader(UTF-8)} 读进来即可，没有额外依赖，
 * 也就没有"多引一个库换来一份配置"这种账。
 *
 * <p><b>目录不存在即失败，不是"没有脚本包"</b>：目录是显式配出来的
 * （{@code claw4j.scripts.directory}），配了一个不存在的路径属于配置错误，
 * 而配置错误要在启动时炸，不该表现为"脚本工具静静地少了一个"（ADR-0008）。
 */
public final class ScriptPackageCatalog {

    /** 包目录里的说明文件。 */
    public static final String MANIFEST = "package.properties";

    private static final String DESCRIPTION = "description";

    private static final String ENTRYPOINT = "entrypoint";

    private static final String TIMEOUT_MILLIS = "timeoutMillis";

    private ScriptPackageCatalog() {
    }

    public static List<ScriptPackage> load(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalStateException("配置的脚本目录不存在或不是目录：" + directory
                    + "。不配 claw4j.scripts.directory 就没有脚本层，配了就必须存在");
        }
        List<ScriptPackage> packages = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                packages.add(read(child));
            }
        }
        catch (IOException failure) {
            throw new IllegalStateException("读脚本目录 " + directory + " 失败", failure);
        }
        return List.copyOf(packages);
    }

    /**
     * 一个包目录 → 一个脚本包。
     *
     * <p>{@code description} 必填，理由写在 {@link ScriptPackage} 上：
     * 没有描述的包永远不会被调用，而"永远不被调用"不会报错。
     * {@code entrypoint} 与 {@code timeoutMillis} 有默认值，缺省是常见情况，
     * 逼着每个包都写一遍只会让人随手填错。
     */
    private static ScriptPackage read(Path directory) {
        Path manifest = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("脚本包目录 " + directory + " 里没有 " + MANIFEST + "："
                    + "没有它就说不清这个包是干什么的，模型也就无从判断什么时候该调");
        }
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(manifest), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        catch (IOException failure) {
            throw new IllegalStateException("读 " + manifest + " 失败", failure);
        }
        String name = directory.getFileName().toString();
        String description = properties.getProperty(DESCRIPTION);
        String entrypoint = properties.getProperty(ENTRYPOINT, ScriptPackage.DEFAULT_ENTRYPOINT);
        long timeout = Long.parseLong(properties.getProperty(TIMEOUT_MILLIS,
                String.valueOf(ScriptPackage.DEFAULT_TIMEOUT_MILLIS)));
        return ScriptPackage.of(name, directory, entrypoint, description, timeout);
    }
}
