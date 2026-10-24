package io.github.claw4j.agent.subagent;

import org.springaicommunity.agent.utils.MarkdownParser;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一份 Subagent 定义：它是谁、什么时候该找它、以及它能碰哪些工具。
 *
 * <p>定义来自一个 markdown 文件（{@code agents/<name>.md}），front matter 是配置，
 * 正文是它做事时用的系统提示词——<b>正文不进父上下文</b>，与 Skill 的渐进式披露同一个形状：
 * 父 Agent 在工具描述里只看到名字与描述（{@link SubagentDelegation} 的描述就是这么拼的），
 * 正文只在子 Agent 那个独立窗口里出现一次。
 *
 * <p><b>{@code tools} 是白名单，不是"顺便都能用"</b>（票面：委派不等于把全部能力交出去）。
 * 它是这一层唯一的安全边界：子 Agent 那个窗口里出现什么工具，
 * 由这份静态定义决定，不由模型决定，也不由父 Agent 决定。
 * 白名单里点名了、容器里却没有的工具按<b>失败</b>处理——
 * "定义了却没装上"如果静默略过，症状是"它为什么不用那个工具"，
 * 而真正的原因是名字写错或那一层没启用。
 *
 * <p>白名单与 {@code disallowedTools} 同时点名同一个工具按<b>定义错误</b>处理：
 * 两条规则打架时，谁生效取决于实现里过滤的先后顺序，
 * 而"这个子 Agent 到底能不能用那个工具"不该是一个要读代码才知道的答案。
 */
public record SubagentDefinition(String name,
                                 String description,
                                 String systemPrompt,
                                 List<String> tools,
                                 List<String> disallowedTools) {

    public SubagentDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(disallowedTools, "disallowedTools");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Subagent 的 name 不能为空。");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException(
                    "Subagent " + name + " 的 description 不能为空：父 Agent 只靠这一行决定要不要派它。");
        }
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException(
                    "Subagent " + name + " 的正文（系统提示词）不能为空：它做事的方式全写在这里。");
        }
        List<String> conflict = tools.stream().filter(disallowedTools::contains).sorted().toList();
        if (!conflict.isEmpty()) {
            throw new IllegalStateException(
                    "Subagent " + name + " 的定义自相矛盾：" + String.join("、", conflict)
                            + " 同时出现在 tools 与 disallowedTools 里，"
                            + "“它到底能不能用”不该由过滤的先后顺序决定。");
        }
        if (tools.contains(SubagentDelegation.TOOL_NAME)) {
            throw new IllegalStateException(
                    "Subagent " + name + " 的白名单里写了 " + SubagentDelegation.TOOL_NAME
                            + "：委派不嵌套——子 Agent 再去派一个子 Agent，"
                            + "上下文隔离与调用预算都会变成一串说不清的递归。");
        }
        tools = List.copyOf(tools);
        disallowedTools = List.copyOf(disallowedTools);
    }

    /** 从一个 markdown 文件读出定义。文件读不出来或 front matter 缺字段都是配置错误，一律失败。 */
    public static SubagentDefinition parse(Path file) {
        Objects.requireNonNull(file, "file");
        String markdown;
        try {
            markdown = Files.readString(file);
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不了 Subagent 定义文件：" + file, failure);
        }
        MarkdownParser parser = new MarkdownParser(markdown);
        String name = text(parser, "name", file);
        return new SubagentDefinition(
                name,
                text(parser, "description", file),
                parser.getContent(),
                names(parser, "tools"),
                names(parser, "disallowedTools"));
    }

    /**
     * 按这份定义在可用工具里挑出子 Agent 能用的那些。
     *
     * <p>三个失败点都是<b>拒绝执行</b>而不是"少给几个"：白名单点名了但没有、
     * 挑完之后一个都不剩。后者尤其要拦——一个没有工具的 Subagent 只能靠猜来回答，
     * 那正是委派最不该变成的东西（把幻觉搬进一个看起来更正式的流程里）。
     *
     * <p><b>委派工具自己在两条路上都被减掉</b>，这是"委派不嵌套"（GLOSSARY）落在代码里的位置：
     * 白名单那条路由构造方法挡着（点名了它按定义错误失败），
     * 而"继承全部"这条路走的是{@code 可用工具}这一整份，里面就有委派工具自己——
     * 只靠黑名单挡的话，一份没有写 {@code disallowedTools} 的定义就能再派一层，
     * 于是上下文隔离与 3/6 预算变成一串说不清的递归。
     * 两条路都减，是为了让"子 Agent 能不能再派一个"的答案不依赖这份定义写了什么。
     */
    public List<ToolCallback> selectTools(List<ToolCallback> available) {
        Objects.requireNonNull(available, "available");
        List<ToolCallback> selected = tools.isEmpty()
                ? List.copyOf(available)
                : tools.stream()
                        .map(name -> require(available, name))
                        .toList();
        List<ToolCallback> filtered = selected.stream()
                .filter(callback -> !disallowedTools.contains(callback.getToolDefinition().name()))
                .filter(callback -> !SubagentDelegation.TOOL_NAME.equals(callback.getToolDefinition().name()))
                .toList();
        if (filtered.isEmpty()) {
            throw new IllegalStateException("Subagent " + name + " 一个可用工具都没有："
                    + "白名单 " + tools + " 与黑名单 " + disallowedTools + " 在这套工具集上算出来是空的。"
                    + "没有工具的 Subagent 只能靠猜作答——委派不该是这个样子。");
        }
        return filtered;
    }

    /**
     * 给模型看的一行元数据。刻意只有名字与描述：
     * 正文（做事的方式）要等它被派上用场才在它自己的窗口里出现。
     */
    public String registration() {
        return "- " + name + ": " + description;
    }

    private static ToolCallback require(List<ToolCallback> available, String name) {
        return available.stream()
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Subagent 的白名单点名了工具 " + name + "，但当前这套工具里没有它："
                                + "不是名字写错，就是提供它的那一层没启用。"));
    }

    private static String text(MarkdownParser parser, String key, Path file) {
        Object value = parser.getFrontMatter().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("Subagent 定义文件 " + file + " 缺少 " + key
                    + "：front matter 里必须有 name 与 description。");
        }
        return value.toString().trim();
    }

    /** front matter 里的工具名是逗号分隔的一段（与 Claude Code 那份格式一致）。 */
    private static List<String> names(MarkdownParser parser, String key) {
        Object value = parser.getFrontMatter().get(key);
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return List.of(value.toString().split(",")).stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }
}
