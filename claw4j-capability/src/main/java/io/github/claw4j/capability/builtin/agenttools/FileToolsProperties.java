package io.github.claw4j.capability.builtin.agenttools;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 文件工具（{@code Read} / {@code Grep} / {@code Glob} / {@code Write} / {@code Edit}）的配置。
 *
 * <p>{@code directory} 是这些工具的边界，也是相对路径的解析起点：模型给的路径落在它外面（含
 * {@code ..} 与软链接跳出去）一律拒。默认 {@code .}，与 {@code claw4j.tools.exec.workspace}
 * 同一个口径——部署里"agent 在哪个目录里动手"只该有一个答案，两个键写两处迟早对不上。
 *
 * <p>{@code writeEnabled} 默认 false：读文件和改文件是两种能力。默认能改磁盘意味着一次误判就是
 * 一次脏工作区，而「只读三件套」已经够回答"这个仓库里有什么"。
 *
 * @param directory 允许访问的根目录；空按 {@code .} 处理
 * @param writeEnabled 是否导出 {@code Write} 与 {@code Edit}
 */
@ConfigurationProperties("claw4j.tools.files")
public record FileToolsProperties(Path directory, boolean writeEnabled) {

    public FileToolsProperties {
        directory = directory == null ? Path.of(".") : directory;
    }

    /**
     * 边界目录的绝对路径。
     *
     * <p>不在这里校验存在性：目录不存在时工具会照常启动，只是每次读都报"文件不存在"。
     * 那不是配置错误该有的样子，但把校验放在这块配置上会让「只想用 WebFetch」的部署也起不来。
     *
     * @return 规范化后的绝对路径
     */
    public Path absoluteDirectory() {
        return directory.toAbsolutePath().normalize();
    }
}
