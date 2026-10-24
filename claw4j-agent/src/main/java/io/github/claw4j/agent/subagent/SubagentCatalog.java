package io.github.claw4j.agent.subagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Subagent 定义的加载：一个目录里每个 {@code *.md} 是一个专职 Subagent。
 *
 * <p><b>配了目录却读不出来即启动失败</b>，与 Skill 目录同一个口径（ADR-0008：
 * 配置错误在启动时暴露，而不是等到有人问到了才暴露）。两个方向都要失败：
 * 目录不存在，和目录里一个 {@code *.md} 都没有。后者最容易被当成"这一层没启用"
 * 而默默略过——那样模型手上没有 Subagent 可派，表现是"它怎么什么事都自己干"，
 * 而配置那行看起来是对的。
 *
 * <p><b>排序后返回</b>：文件遍历的顺序在不同文件系统上不一样，
 * 而"模型看到的第一行是谁"会影响它派谁。稳定的顺序意味着
 * "这次派错了"是可复现的，不是"这次运气不好"。
 */
public final class SubagentCatalog {

    private static final String EXTENSION = ".md";

    private SubagentCatalog() {
    }

    /**
     * @throws IllegalStateException 目录不存在、不是目录，或里面没有 {@code *.md}
     */
    public static List<SubagentDefinition> load(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalStateException(
                    "配置了 claw4j.subagents.directory 但它不存在或不是目录：" + absolute
                            + "。配了就应当有它——这是配置错误，不是“这一层不存在”。");
        }
        List<SubagentDefinition> definitions;
        try (var files = Files.list(absolute)) {
            definitions = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .map(SubagentDefinition::parse)
                    .toList();
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不了 Subagent 目录：" + absolute, failure);
        }
        if (definitions.isEmpty()) {
            throw new IllegalStateException("Subagent 目录里一个 *.md 都没有：" + absolute
                    + "。空目录与“没配”不是一回事：配了却空着，说明这些专职 Agent 没被部署到这个位置。");
        }
        return definitions;
    }
}
