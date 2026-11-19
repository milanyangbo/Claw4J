package io.github.claw4j.agent.subagent;

import org.springaicommunity.agent.common.task.subagent.SubagentReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Subagent 定义的加载：一个目录里每个 {@code *.md} 是一个专职 Subagent。
 *
 * <p>配了目录却读不出来即启动失败，与 Skill 目录同一个口径（ADR-0008：配置错误在启动时
 * 暴露，而不是等到有人问到了才暴露）。两个方向都要失败：目录不存在，和目录里一个
 * {@code *.md} 都没有。后者最容易被当成「这一层没启用」而默默略过，那样模型手上没有
 * Subagent 可派，表现是「它怎么什么事都自己干」，而配置那行看起来是对的。
 *
 * <p>排序后返回：文件遍历的顺序在不同文件系统上不一样，而模型看到的第一行是谁会影响它派谁。
 * 稳定的顺序意味着「这次派错了」是可复现的。
 *
 * <p>两个出口：{@link #references} 给库的 {@code TaskTool}（它自己按引用去 resolve），
 * {@link #load} 给"我就要一份现成的定义列表"的调用方（测试、诊断）。两条都走
 * {@link SubagentDefinition#parse}，所以校验只有一份。
 */
public final class SubagentCatalog {

    private static final String EXTENSION = ".md";

    private SubagentCatalog() {
    }

    /**
     * 目录里每一份定义对应的一条库的引用。
     *
     * <p>{@code TaskTool.Builder#subagentReferences(...)} 收的是它，{@code build()} 时用
     * {@link SubagentReferenceResolver} 逐条解析——校验因此发生在启动期，与换库之前一样。
     *
     * @param directory 定义所在目录
     * @return 引用列表，至少一项
     * @throws IllegalStateException 目录不存在、不是目录，或里面没有 {@code *.md}
     */
    public static List<SubagentReference> references(Path directory) {
        List<SubagentReference> references;
        try (var files = Files.list(requireDirectory(directory))) {
            references = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .map(path -> new SubagentReference(
                            path.toAbsolutePath().normalize().toString(), SubagentDefinition.KIND))
                    .toList();
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不了 Subagent 目录：" + directory, failure);
        }
        if (references.isEmpty()) {
            throw emptyDirectory(directory);
        }
        return references;
    }

    /**
     * 读一个目录下的全部定义，按文件名排序。
     *
     * @param directory 定义所在目录
     * @return 定义列表，至少一项
     * @throws IllegalStateException 目录不存在、不是目录，或里面没有 {@code *.md}
     */
    public static List<SubagentDefinition> load(Path directory) {
        List<SubagentDefinition> definitions;
        try (var files = Files.list(requireDirectory(directory))) {
            definitions = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .map(SubagentDefinition::parse)
                    .toList();
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不了 Subagent 目录：" + directory, failure);
        }
        if (definitions.isEmpty()) {
            throw emptyDirectory(directory);
        }
        return definitions;
    }

    private static Path requireDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalStateException(
                    "配置了 claw4j.subagents.directory 但它不存在或不是目录：" + absolute
                            + "。配了就应当有它——这是配置错误，不是“这一层不存在”。");
        }
        return absolute;
    }

    private static IllegalStateException emptyDirectory(Path directory) {
        return new IllegalStateException("Subagent 目录里一个 *.md 都没有："
                + directory.toAbsolutePath().normalize()
                + "。空目录与“没配”不是一回事：配了却空着，说明这些专职 Agent 没被部署到这个位置。");
    }
}
