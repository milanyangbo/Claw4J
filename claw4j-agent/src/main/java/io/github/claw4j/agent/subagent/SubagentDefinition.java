package io.github.claw4j.agent.subagent;

import io.github.claw4j.markdown.FrontMatter;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一份 Subagent 定义：它是谁、什么时候该找它、以及它能碰哪些工具。
 *
 * <p>定义来自一个 markdown 文件（{@code agents/<name>.md}），front matter 是配置，正文是它
 * 做事时用的系统提示词。正文不进父上下文，与 Skill 的渐进式披露同一个形状：父 Agent 在工具
 * 描述里只看到名字与描述，正文只在子 Agent 那个独立窗口里出现一次。
 *
 * <p>{@code tools} 是白名单，不是顺便都能用（票面：委派不等于把全部能力交出去）。它是这一层
 * 唯一的安全边界：子 Agent 那个窗口里出现什么工具，由这份静态定义决定，不由模型决定，也不由
 * 父 Agent 决定。白名单里点名了、容器里却没有的工具按失败处理——静默略过的症状是「它为什么
 * 不用那个工具」，而真正的原因是名字写错或那一层没启用。
 *
 * <p>白名单与 {@code disallowedTools} 同时点名同一个工具按定义错误处理：两条规则打架时，
 * 谁生效取决于实现里过滤的先后顺序，而「这个子 Agent 到底能不能用那个工具」不该是要读代码
 * 才知道的答案。
 *
 * <p>「没写」与「写了但解析不出」是两件事：只有前者是继承全部工具，后者是配置错误，按定义
 * 错误失败。一个空名单如果被当成许可，后果是权限变宽，而现象是「它怎么忽然能发邮件了」。
 *
 * @param name 定义文件里点名用的名字，也是子窗口会话桶的键
 * @param description 给模型看的一行说明，父 Agent 只靠它决定要不要派
 * @param systemPrompt 正文，子 Agent 的系统提示词
 * @param tools 工具白名单；空表示继承全部
 * @param disallowedTools 工具黑名单，在白名单或继承结果之上再减一层
 */
public record SubagentDefinition(String name,
                                 String description,
                                 String systemPrompt,
                                 List<String> tools,
                                 List<String> disallowedTools) {

    /** front matter 里允许逐行写列表项的两个键；其余键只认同行值。 */
    private static final String TOOLS = "tools";
    private static final String DISALLOWED_TOOLS = "disallowedTools";
    private static final Set<String> LIST_KEYS = Set.of(TOOLS, DISALLOWED_TOOLS);

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

    /**
     * 从一个 markdown 文件读出定义。
     *
     * <p>文件读不出来、front matter 缺字段、名字冲突、正文为空，都是配置错误，一律在启动时
     * 失败而不是留到第一次委派。
     *
     * @param file 定义文件
     * @return 解析出来的定义
     * @throws IllegalStateException 文件读不出来或 front matter 不完整
     */
    public static SubagentDefinition parse(Path file) {
        Objects.requireNonNull(file, "file");
        String markdown;
        try {
            markdown = Files.readString(file);
        }
        catch (IOException failure) {
            throw new IllegalStateException("读不了 Subagent 定义文件：" + file, failure);
        }
        FrontMatter frontMatter = FrontMatter.parse(markdown, file, LIST_KEYS);
        String name = text(frontMatter, "name", file);
        return new SubagentDefinition(
                name,
                text(frontMatter, "description", file),
                frontMatter.body(),
                names(frontMatter, TOOLS, file),
                names(frontMatter, DISALLOWED_TOOLS, file));
    }

    /**
     * 按这份定义在可用工具里挑出子 Agent 能用的那些。
     *
     * <p>失败点都是拒绝执行而不是少给几个：白名单点名了但没有，或挑完之后一个都不剩。
     * 后者尤其要拦——一个没有工具的 Subagent 只能靠猜来回答，那正是委派最不该变成的东西
     * （把幻觉搬进一个看起来更正式的流程里）。
     *
     * <p>委派工具自己在两条路上都被减掉，这是「委派不嵌套」（见 GLOSSARY）落在代码里的位置：
     * 白名单那条路由构造方法挡着，而继承全部这条路拿到的是可用工具那一整份，里面就有委派工具
     * 自己。只靠黑名单挡的话，一份没写 {@code disallowedTools} 的定义就能再派一层。两条路都减，
     * 是为了让「子 Agent 能不能再派一个」的答案不依赖这份定义写了什么。
     *
     * @param available 当前容器里的全部工具
     * @return 子 Agent 这一趟能用的工具，至少一项
     * @throws IllegalStateException 白名单点名了不存在的工具，或过滤之后一个都不剩
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
     * 给模型看的一行元数据。
     *
     * <p>刻意只有名字与描述：正文（做事的方式）要等它被派上用场，才在它自己的窗口里出现。
     *
     * @return 一行 {@code - 名字: 描述}
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

    private static String text(FrontMatter frontMatter, String key, Path file) {
        String value = frontMatter.value(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Subagent 定义文件 " + file + " 缺少 " + key
                    + "：front matter 里必须有 name 与 description。");
        }
        return value.trim();
    }

    /**
     * 读一节的工具名。两种写法都收：{@code a, b} 与逐行的 {@code - a}。
     *
     * <p>没写这一节返回空名单，含义是继承全部（合法路径，由 {@link #selectTools} 减掉委派
     * 工具）。写了但一个名字都解析不出是配置错误，当场失败：一个空结果不能被下游当成许可。
     * 两者的症状差别很大——前者是设计如此，后者会让一个只该看一条 SQL 的复核员拿到发邮件与
     * 跑脚本的权限，而且不报错。
     *
     * @param frontMatter 定义文件的 front matter
     * @param key 节名，{@code tools} 或 {@code disallowedTools}
     * @param file 定义文件，只用于报错
     * @return 工具名；这一节没写时为空
     * @throws IllegalStateException 写了这一节但解析不出任何名字
     */
    private static List<String> names(FrontMatter frontMatter, String key, Path file) {
        if (!frontMatter.has(key)) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>(splitInline(frontMatter.value(key)));
        names.addAll(frontMatter.items(key));
        if (names.isEmpty()) {
            throw new IllegalStateException("Subagent 定义文件 " + file + " 的 " + key
                    + " 这节解析不出任何工具名。两种写法都行：写在一行（" + key + ": querySales, lookup），"
                    + "或者每行一项（" + key + ": 下面写 - querySales）。"
                    + "空名单不等于“继承全部工具”，所以这里按定义错误处理："
                    + "要继承就别写这一节，要限制就写出工具名。");
        }
        return List.copyOf(names);
    }

    private static List<String> splitInline(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String inline = value.trim();
        if (inline.startsWith("[") && inline.endsWith("]")) {
            inline = inline.substring(1, inline.length() - 1).trim();
        }
        return List.of(inline.split(",")).stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }
}
